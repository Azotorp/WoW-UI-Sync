#!/usr/bin/env python3
"""Linux systemd lifecycle tools. No credentials are accepted on the command line."""
import argparse
import ipaddress
import grp
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
UNITS = Path('/etc/systemd/system')

def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)

def service_name(value):
    value = value.removesuffix('.service')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', value):
        raise ValueError('Service name must contain letters, numbers, _ or -')
    return value + '.service'

def path_value(value):
    if not value or any(c in value for c in '\n\r\x00'):
        raise ValueError('Empty paths and control characters are not supported')
    p = Path(value).expanduser()
    if not p.is_absolute():
        raise ValueError('Use an absolute path')
    return p.resolve()

def quote(value, exec_argument=True):
    value = str(value)
    if any(c in value for c in '\n\r\x00'):
        raise ValueError('Control characters are not allowed in a unit value')
    escaped = value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%')
    if exec_argument:
        escaped = escaped.replace('$', '$$')
    return '"' + escaped + '"'

def overlaps(a, b):
    a, b = Path(a).resolve(), Path(b).resolve()
    return a == b or a in b.parents or b in a.parents

def unit_text(name, java, jar, data, user, group):
    name = service_name(name)
    for identity in [user, group]:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', identity):
            raise ValueError('Unsupported OS account/group name')
    return '\n'.join([
        '[Unit]', 'Description=WoW UI Sync ' + name.removesuffix('.service'), 'After=network.target', '',
        '[Service]', 'Type=simple', 'User=' + user, 'Group=' + group,
        'WorkingDirectory=' + quote(Path(jar).parent, False),
        'ExecStart=' + ' '.join([quote(java), '-jar', quote(jar), 'run', '--data', quote(data)]),
        'Restart=on-failure', 'RestartSec=10', 'TimeoutStopSec=30', 'UMask=0077',
        'NoNewPrivileges=true', 'PrivateTmp=true', '', '[Install]', 'WantedBy=multi-user.target', ''
    ])

def ask(label, default):
    result = input(f'{label} [{default}]: ').strip()
    return result or str(default)

def confirm(label):
    return input(label + ' Type YES: ').strip() == 'YES'

def require_root():
    if os.geteuid() != 0:
        raise ValueError('Run this script with sudo; no service or files were changed')

def backup_file(path):
    if path.exists():
        destination = path.with_name(path.name + '.before-' + str(time.time_ns()))
        shutil.copy2(path, destination)
        return destination
    return None

def atomic_write(path, data, mode=0o600):
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(name, mode)
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)

def check_port(bind, port):
    if not 1025 <= port <= 65535: raise ValueError('Port must be 1025-65535')
    for family, kind, proto, _, address in socket.getaddrinfo(bind, port, type=socket.SOCK_STREAM, flags=socket.AI_PASSIVE):
        with socket.socket(family, kind, proto) as sock:
            sock.bind(address)

def other_stores(selected):
    """Inspect installed unit ExecStart data paths without executing unit text."""
    found = {}
    for directory in [Path('/usr/lib/systemd/system'), Path('/lib/systemd/system'), UNITS]:
        if not directory.is_dir(): continue
        for unit in directory.glob('*.service'):
            if unit.name == selected: continue
            try:
                text = unit.read_text()
                starts = re.findall(r'^ExecStart=(.*)$', text, re.M)
                for line in starts:
                    args = shlex.split(line)
                    if '--data' in args:
                        data = Path(args[args.index('--data') + 1].replace('%%', '%').replace('$$', '$')).resolve()
                        if (data / 'server.json').is_file(): found[unit.name] = data
                    elif any('wow-ui-sync' in a and a.endswith('.jar') for a in args):
                        jar = next(a for a in args if 'wow-ui-sync' in a and a.endswith('.jar'))
                        cfg = json.loads((Path(jar).parent / 'server.json').read_text())
                        if cfg.get('dataDir'): found[unit.name] = Path(cfg['dataDir']).resolve()
            except (OSError, ValueError, IndexError, StopIteration):
                continue
    return found

def assert_separate(data, port, stores):
    for name, other in stores.items():
        if overlaps(data, other): raise ValueError(f'Data directory overlaps {name}; choose an independent directory')
        config = json.loads((other / 'server.json').read_text())
        if int(config['port']) == port: raise ValueError(f'Port is already configured for {name}; use a separate port')

def active(name):
    return subprocess.run(['systemctl', 'is-active', '--quiet', name]).returncode == 0

def status(name):
    run(['systemctl', '--no-pager', '--full', 'status', name])

def validate_unit(path):
    run(['systemd-analyze', 'verify', path])

def store_lease(data):
    # FileChannel uses POSIX record locks; flock would be a different, incompatible lock family.
    import fcntl
    handle = open(Path(data) / '.store.lock', 'a+b')
    try:
        fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BaseException:
        handle.close()
        raise ValueError('Data directory is locked by a running server; no configuration was changed')
    return handle

def ufw_allows_port(text, port):
    return re.search(r'^\s*' + str(port) + r'/tcp(?:\s+\(v6\))?\s+ALLOW(?:\s|$)', text, re.M) is not None

def offer_firewall(bind, port, name):
    """Optional post-install step. Never enable/reload UFW or alter unrelated rules."""
    if not 1025 <= port <= 65535:
        raise ValueError('Port must be 1025-65535')
    try:
        loopback = ipaddress.ip_address(bind.strip('[]')).is_loopback
    except ValueError:
        loopback = bind.lower() == 'localhost'
    if loopback:
        print('Loopback-only bind: no incoming firewall port is needed. Use an external bind address for remote clients.')
        return False
    ufw = shutil.which('ufw')
    manual = f'sudo ufw allow {port}/tcp'
    if not ufw:
        print('UFW is not installed; no firewall changes made. Allow TCP ' + str(port) + ' in your firewall if needed.')
        return False
    env = dict(os.environ, LC_ALL='C')
    try:
        before = subprocess.run([ufw, 'status'], capture_output=True, text=True, env=env, check=True)
        if not re.search(r'^Status: active\s*$', before.stdout, re.M):
            print('UFW is inactive or its status is unknown. It was not enabled or changed. If you use another firewall, allow TCP ' + str(port) + ' there.')
            return False
        denied = re.search(r'^\s*' + str(port) + r'/tcp(?:\s+\(v6\))?\s+(?:DENY|REJECT)(?:\s|$)', before.stdout, re.M)
        if denied:
            print('UFW has a conflicting DENY/REJECT rule for this port. No rule was changed; review sudo ufw status numbered before allowing access.')
            return False
        if ufw_allows_port(before.stdout, port):
            print(f'UFW already reports a TCP {port} ALLOW rule. Existing rules were left unchanged.')
            return True
        print(f'UFW is active. This will allow incoming TCP {port} from any source; SSH and other port rules stay unchanged.')
        if not confirm(f'Allow TCP {port} in UFW for {name}?'):
            print(f'Firewall unchanged. If needed later: {manual}')
            return False
        run([ufw, 'allow', f'{port}/tcp', 'comment', f'WoW UI Sync {service_name(name)}'], env=env)
        after = subprocess.run([ufw, 'status'], capture_output=True, text=True, env=env, check=True)
        if re.search(r'^Status: active\s*$', after.stdout, re.M) and ufw_allows_port(after.stdout, port):
            print(f'Verified: UFW reports a TCP {port} ALLOW rule. Router/provider firewalls may still need separate configuration.')
            return True
        print(f'UFW did not confirm the expected rule. The service remains installed; check sudo ufw status numbered. Requested command: {manual}')
    except (OSError, subprocess.CalledProcessError):
        print(f'Firewall configuration failed. The service remains installed and running; check UFW separately. Manual command: {manual}')
    return False

def assert_dedicated_jar(jar, selected, directories=None):
    """Unrelated unit syntax must not abort setup; ambiguous sync units still fail closed."""
    if directories is None:
        directories = [Path('/usr/lib/systemd/system'), Path('/lib/systemd/system'), UNITS]
    seen = set()
    for directory in directories:
        if not directory.is_dir():
            continue
        for other in directory.glob('*.service'):
            if other.name == selected or other.resolve() in seen:
                continue
            seen.add(other.resolve())
            try:
                contents = other.read_text(errors='replace')
            except OSError as error:
                if 'wow-ui-sync' in other.name.lower() or 'wowuisync' in other.name.lower():
                    raise ValueError(f'Cannot read sync service {other.name}; run setup with sudo and inspect that unit') from error
                continue
            # Systemd supports continuation lines; they are not separate shell commands.
            contents = re.sub(r'\\\r?\n[ \t]*', ' ', contents)
            for line in re.findall(r'^[ \t]*ExecStart[ \t]*=(.*)$', contents, re.M):
                try:
                    args = shlex.split(line)
                except ValueError as error:
                    identity = (other.name + ' ' + line).lower()
                    if 'wow-ui-sync' in identity or 'wowuisync' in identity:
                        raise ValueError(f'Cannot safely inspect sync service {other.name}: unsupported or unmatched ExecStart quoting. Review that unit before installing.') from error
                    # Systemd and shell quoting differ. An unrelated service must not break our setup.
                    continue
                try:
                    other_jar = Path(args[args.index('-jar') + 1]).resolve() if '-jar' in args else None
                except (IndexError, OSError):
                    continue
                if other_jar == jar.resolve():
                    raise ValueError(f'JAR is shared with {other.name}; use a dedicated test installation directory')

def usable_bind_ip(value):
    try:
        address = ipaddress.ip_address(value)
        return not (address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local)
    except ValueError:
        return False

def detect_primary_ip():
    """Find the assigned source IP of the default route, without a public-IP web lookup."""
    ip = shutil.which('ip')
    if ip:
        for version, destination in [('-4', '1.1.1.1'), ('-6', '2606:4700:4700::1111')]:
            try:
                result = subprocess.run([ip, '-j', version, 'route', 'get', destination], capture_output=True, text=True, check=True, timeout=3)
                for route in json.loads(result.stdout):
                    candidate = route.get('prefsrc') or route.get('src')
                    if candidate and usable_bind_ip(candidate):
                        return candidate
            except (OSError, ValueError, subprocess.SubprocessError):
                pass
    # UDP connect selects the outbound interface locally; no packet is transmitted.
    for family, destination in [(socket.AF_INET, ('1.1.1.1', 80)),
                                (socket.AF_INET6, ('2606:4700:4700::1111', 80))]:
        try:
            with socket.socket(family, socket.SOCK_DGRAM) as probe:
                probe.connect(destination)
                candidate = probe.getsockname()[0]
                if usable_bind_ip(candidate):
                    return candidate
        except OSError:
            pass
    return None

def default_service_user():
    return os.environ.get('SUDO_USER') or pwd.getpwuid(os.getuid()).pw_name


def setup(dry_run=False):
    java = shutil.which('java')
    if not java: raise ValueError('Java 21+ is required')
    java = path_value(ask('Java executable', Path(java).resolve()))
    version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True)
    match = re.search(r'version "(\d+)', version)
    if not match or int(match[1]) < 21: raise ValueError('Java 21+ is required')
    name = service_name(ask('Service name', 'wow-ui-sync'))
    unit = UNITS / name
    # Existing service must be approved before any installation or config mutation.
    exists = subprocess.run(['systemctl', 'cat', name], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    existing = exists.returncode == 0
    if existing:
        print(exists.stdout)
        if not confirm('Reconfigure this existing service?'):
            print('Declined; existing service was left untouched.')
            return
    detected_ip = detect_primary_ip()
    if detected_ip:
        print(f'Detected primary interface IP: {detected_ip}')
    else:
        print('Could not detect the primary interface IP. Enter an IP assigned to this server; no localhost default will be used.')
    bind = ask('Bind address', detected_ip or '')
    if not bind:
        raise ValueError('A bind address is required because primary-IP detection failed')
    port = int(ask('Port', 2027))
    jar = path_value(ask('Installation JAR path', HERE / 'wow-ui-sync.server.jar'))
    data = path_value(ask('Data directory', jar.parent / 'data'))
    if not jar.is_file(): raise ValueError('JAR does not exist')
    if overlaps(data, jar): raise ValueError('JAR must stay outside the data directory')
    default_user = default_service_user()
    user = ask('Existing OS service user', default_user)
    owner = pwd.getpwnam(user)
    group = ask('OS service group', grp.getgrgid(owner.pw_gid).gr_name)
    group_id = grp.getgrnam(group).gr_gid
    assert_separate(data, port, other_stores(name))
    assert_dedicated_jar(jar, name)
    running = existing and active(name)
    if not running: check_port(bind, port)
    text = unit_text(name, java, jar, data, user, group)
    print(f'\n{name}\nBind: {bind}:{port}\nJAR: {jar}\nData: {data}\nOwner: {user}:{group}\n\n{text}')
    if dry_run:
        print('Dry run: no files or services changed.')
        return
    require_root()
    if not confirm('Install this configuration?'): return
    UNITS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='wowuisync-unit-') as temporary:
        proposed = Path(temporary) / name
        proposed.write_text(text)
        validate_unit(proposed)
        old_unit = backup_file(unit)
        old_cfg = backup_file(data / 'server.json') if data.exists() else None
        had_cfg = (data / 'server.json').exists()
        # Ensure newly created store belongs to the selected account; never chown a populated existing store recursively.
        data.mkdir(parents=True, exist_ok=True)
        if not had_cfg: os.chown(data, owner.pw_uid, group_id)
        if running: run(['systemctl', 'stop', name])
        lease = None
        config_changed = unit_changed = False
        try:
            if not had_cfg:
                run(['runuser', '-u', user, '-g', group, '--', java,
                     '-Dwowuisync.noStart=true', f'-Dwowuisync.installPort={port}', f'-Dwowuisync.installBind={bind}',
                     '-jar', jar, 'install', '--data', data])
            lease = store_lease(data)
            cfg_path = data / 'server.json'
            if not cfg_path.exists(): raise ValueError('Installation did not produce server.json; service was not installed')
            cfg = json.loads(cfg_path.read_text())
            cfg.update(dataDir=str(data), bindAddress=bind, port=port)
            check_port(bind, port)
            atomic_write(cfg_path, json.dumps(cfg, indent=2) + '\n')
            config_changed = True
            os.chown(cfg_path, owner.pw_uid, group_id)
            run(['runuser', '-u', user, '-g', group, '--', 'test', '-r', jar])
            atomic_write(unit, text, 0o644)
            unit_changed = True
            lease.close()
            lease = None
            run(['systemctl', 'daemon-reload'])
            run(['systemctl', 'enable', '--now', name])
            time.sleep(2)
            status(name)
            print(f'Installed and running. Connect the test client to {bind}:{port}.')
        except BaseException:
            subprocess.run(['systemctl', 'stop', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if lease is not None: lease.close()
            if unit_changed:
                if old_unit: shutil.copy2(old_unit, unit)
                elif unit.exists(): unit.unlink()
            if config_changed and old_cfg: shutil.copy2(old_cfg, data / 'server.json')
            subprocess.run(['systemctl', 'daemon-reload'])
            if running: subprocess.run(['systemctl', 'start', name])
            print('Setup failed. Previous unit/config restored where present; data and account keys preserved.', file=sys.stderr)
            raise

    # Firewall failure/cancellation must never roll back a healthy installed service.
    try:
        offer_firewall(bind, port, name)
    except (KeyboardInterrupt, EOFError):
        print('Firewall step canceled. Installed service is unchanged; no UFW enable/reset was performed.')

def update(name, candidate):
    require_root()
    name = service_name(name)
    # Use the authoritative unit file rather than guessing a jar pathname from status output.
    text = subprocess.check_output(['systemctl', 'cat', name], text=True)
    starts = re.findall(r'^ExecStart=(.*)$', text, re.M)
    if len(starts) != 1: raise ValueError('Expected one simple ExecStart; use manual stop/copy/start for a custom unit')
    args = [a.replace('%%', '%').replace('$$', '$') for a in shlex.split(starts[0])]
    if '-jar' not in args: raise ValueError('Selected unit is not a direct Java JAR service')
    jar = Path(args[args.index('-jar') + 1])
    candidate = path_value(candidate)
    if candidate == jar.resolve(): raise ValueError('Candidate must be separate from the active JAR')
    result = subprocess.check_output([args[0], '-jar', str(candidate), 'version'], text=True)
    if 'server 1.2.0-alpha' not in result: raise ValueError('Candidate is not the expected 1.2.0-alpha server')
    print(result.strip())
    print(f'Updating {name}: {jar}. Data and keys stay in place.')
    if not confirm('Stop selected service, replace JAR, and restart?'): return
    previous = backup_file(jar)
    was_running = active(name)
    run(['systemctl', 'stop', name])
    try:
        staging = jar.with_name('.' + jar.name + '.update')
        shutil.copy2(candidate, staging)
        st = jar.stat()
        os.chown(staging, st.st_uid, st.st_gid)
        os.replace(staging, jar)
        run(['systemctl', 'start', name])
        time.sleep(2)
        status(name)
    except BaseException:
        subprocess.run(['systemctl', 'stop', name])
        if previous: shutil.copy2(previous, jar)
        if was_running: subprocess.run(['systemctl', 'start', name])
        print('Update failed; previous JAR restored. Store and keys were not replaced.', file=sys.stderr)
        raise

def edit(name):
    require_root()
    name = service_name(name)
    unit = UNITS / name
    if not unit.is_file(): raise ValueError('No local unit found for selected name')
    if not shutil.which('nano'): raise ValueError('Install nano before editing')
    previous = backup_file(unit)
    with tempfile.TemporaryDirectory(prefix='wowuisync-edit-') as temporary:
        proposed = Path(temporary) / name
        shutil.copy2(unit, proposed)
        run(['nano', proposed])
        validate_unit(proposed)
        atomic_write(unit, proposed.read_text(), 0o644)
    run(['systemctl', 'daemon-reload'])
    print(f'Unit validated and reloaded. Restart required to apply: sudo systemctl restart {name}')

def uninstall(name):
    require_root()
    name = service_name(name)
    unit = UNITS / name
    if not unit.is_file(): raise ValueError('Selected local unit does not exist')
    print(unit.read_text())
    if not confirm(f'Remove ONLY {name}, preserving data, keys and JAR?'): return
    backup_file(unit)
    run(['systemctl', 'disable', '--now', name])
    unit.unlink()
    run(['systemctl', 'daemon-reload'])
    print('Selected service removed. Data, backups, keys and JAR preserved. No data deletion is performed by this tool.')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('setup'); p.add_argument('--dry-run', action='store_true')
    p = sub.add_parser('update');p.add_argument('service');p.add_argument('candidate')
    for command in ['edit', 'uninstall']:
        p = sub.add_parser(command);p.add_argument('service')
    args = parser.parse_args()
    try:
        if args.command == 'setup': setup(args.dry_run)
        elif args.command == 'update': update(args.service, args.candidate)
        elif args.command == 'edit': edit(args.service)
        else: uninstall(args.service)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'Error: {error}', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
