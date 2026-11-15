import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('service_manager',Path(__file__).resolve().parents[1]/'server/service_manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class ServiceTests(unittest.TestCase):
    def test_names(self):
        self.assertEqual('wow-ui-sync-1.2.service',m.service_name('wow-ui-sync-1.2'))
        for name in ['../production','name;rm','bad\nname','']:
            with self.assertRaises(ValueError):m.service_name(name)
    def test_quote_and_paths(self):
        unit=m.unit_text('test','/usr/bin/java','/tmp/space name/x%.jar','/tmp/data $name','example','example')
        self.assertIn('"/tmp/space name/x%%.jar"',unit);self.assertIn('"/tmp/data $$name"',unit)
        self.assertNotIn('/opt/minecraft',unit)
        with self.assertRaises(ValueError):m.path_value('relative/path')
        with self.assertRaises(ValueError):m.quote('bad\npath')
    def test_store_and_port_collision(self):
        with tempfile.TemporaryDirectory() as directory:
            other=Path(directory)/'production';other.mkdir();(other/'server.json').write_text(json.dumps({'port':2026}))
            with self.assertRaises(ValueError):m.assert_separate(other/'child',2027,{'production':other})
            with self.assertRaises(ValueError):m.assert_separate(Path(directory)/'test',2026,{'production':other})
            m.assert_separate(Path(directory)/'test',2027,{'production':other})
    def test_occupied_socket(self):
        with socket.socket() as s:
            s.bind(('127.0.0.1',0))
            with self.assertRaises(OSError):m.check_port('127.0.0.1',s.getsockname()[1])
    def test_atomic_write_and_backups(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'file';p.write_text('before');backup=m.backup_file(p);m.atomic_write(p,'after')
            self.assertEqual('before',backup.read_text());self.assertEqual('after',p.read_text())
    def test_declining_existing_service_does_not_mutate(self):
        result=type('Result',(),{'returncode':0,'stdout':'[Service]\nExecStart=/production/java'})()
        with patch.object(m.shutil,'which',return_value='/usr/bin/java'),patch.object(m,'ask',side_effect=lambda label,default:str(default)),patch.object(m.subprocess,'check_output',return_value='openjdk version "21"'),patch.object(m.subprocess,'run',return_value=result),patch.object(m,'confirm',return_value=False),patch.object(m,'atomic_write') as write:
            m.setup();write.assert_not_called()

    def test_update_failure_restores_original_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            jar=Path(directory)/'active.jar';candidate=Path(directory)/'candidate.jar';jar.write_bytes(b'old');candidate.write_bytes(b'new')
            unit=f'[Service]\nExecStart=/usr/bin/java -jar {jar} run --data {directory}/data\n'
            def output(args,**kwargs):return unit if 'cat' in args else 'WoW UI Sync server 1.2.0-alpha (protocol 2)'
            with patch.object(m,'require_root'),patch.object(m,'confirm',return_value=True),patch.object(m,'active',return_value=True),patch.object(m.subprocess,'check_output',side_effect=output),patch.object(m,'run'),patch.object(m.subprocess,'run'),patch.object(m.time,'sleep'),patch.object(m,'status',side_effect=ValueError('failed health check')):
                with self.assertRaises(ValueError):m.update('test',str(candidate))
            self.assertEqual(b'old',jar.read_bytes())
    def test_uninstall_preserves_data_and_other_units(self):
        with tempfile.TemporaryDirectory() as directory:
            units=Path(directory);selected=units/'test.service';other=units/'production.service';data=units/'data';data.mkdir();(data/'keep').write_text('keep')
            selected.write_text('[Service]');other.write_text('unchanged')
            with patch.object(m,'UNITS',units),patch.object(m,'require_root'),patch.object(m,'confirm',return_value=True),patch.object(m,'run'):
                m.uninstall('test')
            self.assertFalse(selected.exists());self.assertEqual('unchanged',other.read_text());self.assertEqual('keep',(data/'keep').read_text())
    def test_edit_validation_failure_preserves_unit(self):
        with tempfile.TemporaryDirectory() as directory:
            unit=Path(directory)/'test.service';unit.write_text('before')
            with patch.object(m,'UNITS',Path(directory)),patch.object(m,'require_root'),patch.object(m.shutil,'which',return_value='/bin/nano'),patch.object(m,'run'),patch.object(m,'validate_unit',side_effect=ValueError('invalid unit')):
                with self.assertRaises(ValueError):m.edit('test')
            self.assertEqual('before',unit.read_text())
    def test_nonroot_receives_sudo_guidance(self):
        with patch.object(m.os,'geteuid',return_value=1000):
            with self.assertRaisesRegex(ValueError,'sudo'):m.require_root()
    def test_record_lock_refuses_another_process(self):
        import subprocess,sys
        with tempfile.TemporaryDirectory() as directory:
            code='import fcntl,sys;f=open(sys.argv[1],"a+b");fcntl.lockf(f.fileno(),fcntl.LOCK_EX);print("locked",flush=True);sys.stdin.read()'
            child=subprocess.Popen([sys.executable,'-c',code,str(Path(directory)/'.store.lock')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
            try:
                self.assertEqual('locked',child.stdout.readline().strip())
                with self.assertRaises(ValueError):m.store_lease(directory)
            finally:child.stdin.close();child.wait(timeout=5);child.stdout.close()
            with m.store_lease(directory):pass


class FirewallTests(unittest.TestCase):
    def result(self, text):
        import subprocess
        return subprocess.CompletedProcess([], 0, stdout=text, stderr='')
    def test_loopback_never_touches_ufw(self):
        for bind in ['127.0.0.1','::1','localhost']:
            with patch.object(m.shutil,'which') as which,patch.object(m,'run') as run:
                self.assertFalse(m.offer_firewall(bind,2027,'wow-ui-sync'));which.assert_not_called();run.assert_not_called()
    def test_missing_ufw_does_not_change_anything(self):
        with patch.object(m.shutil,'which',return_value=None),patch.object(m,'run') as run:
            self.assertFalse(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'));run.assert_not_called()
    def test_inactive_ufw_never_enables_it(self):
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',return_value=self.result('Status: inactive\n')),patch.object(m,'run') as run,patch.object(m,'confirm') as confirm:
            self.assertFalse(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'));run.assert_not_called();confirm.assert_not_called()
    def test_declining_leaves_rules_unchanged(self):
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',return_value=self.result('Status: active\n22/tcp ALLOW Anywhere\n')),patch.object(m,'run') as run,patch.object(m,'confirm',return_value=False):
            self.assertFalse(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'));run.assert_not_called()
    def test_confirm_adds_only_selected_tcp_port_and_checks_result(self):
        before=self.result('Status: active\n22/tcp ALLOW Anywhere\n')
        after=self.result('Status: active\n22/tcp ALLOW Anywhere\n2027/tcp ALLOW Anywhere\n2027/tcp (v6) ALLOW Anywhere (v6)\n')
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',side_effect=[before,after]),patch.object(m,'run') as run,patch.object(m,'confirm',return_value=True):
            self.assertTrue(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'))
            self.assertEqual(['/usr/sbin/ufw','allow','2027/tcp','comment','WoW UI Sync wow-ui-sync.service'],run.call_args.args[0]);run.assert_called_once()
    def test_existing_rule_not_duplicated(self):
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',return_value=self.result('Status: active\n2027/tcp ALLOW Anywhere\n')),patch.object(m,'run') as run,patch.object(m,'confirm') as confirm:
            self.assertTrue(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'));run.assert_not_called();confirm.assert_not_called()
    def test_conflicting_deny_not_overridden(self):
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',return_value=self.result('Status: active\n2027/tcp DENY Anywhere\n')),patch.object(m,'run') as run:
            self.assertFalse(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'));run.assert_not_called()
    def test_command_failure_does_not_raise_or_claim_success(self):
        import subprocess
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',return_value=self.result('Status: active\n')),patch.object(m,'run',side_effect=subprocess.CalledProcessError(1,'ufw')),patch.object(m,'confirm',return_value=True):
            self.assertFalse(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'))
    def test_missing_rule_after_command_is_not_reported_as_success(self):
        with patch.object(m.shutil,'which',return_value='/usr/sbin/ufw'),patch.object(m.subprocess,'run',return_value=self.result('Status: active\n')),patch.object(m,'run'),patch.object(m,'confirm',return_value=True):
            self.assertFalse(m.offer_firewall('0.0.0.0',2027,'wow-ui-sync'))

if __name__=='__main__':unittest.main()
