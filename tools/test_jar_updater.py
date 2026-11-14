"""Isolated updater contract tests: no real systemd or production data."""
import os,pathlib,subprocess,tempfile,unittest,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Updater(unittest.TestCase):
 def fixture(self,ready=True):
  tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);d=pathlib.Path(tmp.name);b=d/'bin';b.mkdir();data=d/'data';data.mkdir();(data/'config').write_text('preserve');old=d/'actual-name.jar';old.write_bytes(b'old');old.chmod(0o640)
  new=d/'new.jar'
  with zipfile.ZipFile(new,'w') as z:
   z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nMain-Class: io.github.azotorp.wowuisync.server.ServerMain\nImplementation-Version: 1.1.1-alpha\n')
   z.writestr('io/github/azotorp/wowuisync/server/maintenance/Readiness.class',b'test')
  systemctl=b/'systemctl';systemctl.write_text('#!/bin/sh\necho "$*" >> "$TEST_LOG"\ncase "$1" in show) printf "LoadState=loaded\\nExecStart={ path=/usr/bin/java ; argv[]=java -jar '+str(old)+' run --data '+str(data)+' ; }\\n";; esac\n');systemctl.chmod(0o755)
  java=b/'java';java.write_text('#!/bin/sh\ncase "$3" in version) echo "WoW UI Sync server 1.1.1-alpha (protocol 2)";; ready) '+('echo ready' if ready else 'exit 1')+';; *) exit 2;; esac\n');java.chmod(0o755)
  env=dict(os.environ,PATH=str(b)+':'+os.environ['PATH'],WUS_JAVA=str(java),TEST_LOG=str(d/'actions'))
  return d,data,old,new,env
 def execute(self,f):
  d,data,old,new,env=f
  return subprocess.run(['sh',str(ROOT/'packaging/server/update.sh'),'--jar',str(new),'--service','my-real-unit.service','--data',str(data),'--timeout','1'],env=env,text=True,capture_output=True)
 def test_success_preserves_name_permissions_config_and_previous_binary(self):
  f=self.fixture();d,data,old,new,env=f;r=self.execute(f);self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(old.read_bytes(),new.read_bytes());self.assertEqual(old.stat().st_mode&0o777,0o640);self.assertEqual((data/'config').read_text(),'preserve');self.assertEqual(next(d.glob('*.previous-*')).read_bytes(),b'old');self.assertIn('my-real-unit.service',(d/'actions').read_text());self.assertIn('Updated',r.stdout)
 def test_unhealthy_stops_service_without_unsafe_rollback(self):
  f=self.fixture(False);d,data,old,new,env=f;r=self.execute(f);self.assertNotEqual(r.returncode,0);self.assertIn('No automatic rollback',r.stderr);self.assertEqual(old.read_bytes(),new.read_bytes());self.assertEqual((data/'config').read_text(),'preserve');self.assertEqual((d/'actions').read_text().splitlines()[-1],'stop my-real-unit.service')
 def test_invalid_jar_never_stops_existing_service(self):
  f=self.fixture();d,data,old,new,env=f;new.write_bytes(b'bad');r=self.execute(f);self.assertNotEqual(r.returncode,0);self.assertEqual(old.read_bytes(),b'old');self.assertNotIn('stop ',(d/'actions').read_text())
if __name__=='__main__':unittest.main()
