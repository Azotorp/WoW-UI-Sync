#!/bin/sh
set -eu
case "${1:-}" in --help|-h)
 echo "Usage: update.sh --jar replacement.jar --service EXISTING.service --data /existing/data [--installed-jar /actual/server.jar] [--timeout 60]"
 echo "Or: update.sh --from release.zip --data /existing/data [release verification options]"
 echo "JAR replacement preserves the existing service and config. No automatic binary/database rollback."
 exit 0;; esac
WUS_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "${1:-}" = --jar ]; then
 command -v python3 >/dev/null 2>&1 || { echo "Optional JAR updater requires Python 3. Manual JAR replacement remains supported." >&2; exit 1; }
 exec python3 - "$@" <<'PY'
import argparse, os, pathlib, re, shlex, shutil, stat, subprocess, sys, tempfile, time, zipfile
p=argparse.ArgumentParser();p.add_argument('--jar',required=True);p.add_argument('--service',required=True);p.add_argument('--data',required=True);p.add_argument('--installed-jar');p.add_argument('--timeout',type=int,default=60)
a=p.parse_args();candidate=pathlib.Path(a.jar).resolve();data=pathlib.Path(a.data).resolve();service_stopped=False
def run(*args,check=True):return subprocess.run(args,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=check)
try:
 if not candidate.is_file() or not data.is_dir():raise ValueError('Replacement JAR and existing data directory must exist')
 if a.timeout<1 or a.timeout>3600:raise ValueError('Timeout must be 1-3600 seconds')
 if not re.fullmatch(r'[A-Za-z0-9_.@:-]+(?:\.service)?',a.service):raise ValueError('Invalid existing unit name')
 props=run('systemctl','show',a.service,'--property=LoadState,ExecStart,WorkingDirectory').stdout
 if 'LoadState=loaded' not in props:raise ValueError('Existing service is not loaded')
 # Parse a direct java -jar service; wrapper-based installations need the actual --installed-jar.
 match=re.search(r'argv\[\]=(.*?)(?: ;|\n)',props)
 command=shlex.split(match.group(1)) if match else []
 if '-jar' in command:
  target=pathlib.Path(command[command.index('-jar')+1])
  if not target.is_absolute():
   wd=re.search(r'^WorkingDirectory=(.*)$',props,re.M);target=pathlib.Path(wd.group(1) if wd else '/')/target
  target=target.resolve()
  if a.installed_jar and pathlib.Path(a.installed_jar).resolve()!=target:raise ValueError('--installed-jar differs from existing service ExecStart')
 elif a.installed_jar:target=pathlib.Path(a.installed_jar).resolve()
 else:raise ValueError('Cannot identify JAR through service wrapper; pass --installed-jar with its actual path')
 if not target.is_file() or candidate==target:raise ValueError('Use a separate replacement JAR and an existing installed JAR')
 with zipfile.ZipFile(candidate) as z:
  if z.testzip():raise ValueError('Replacement ZIP CRC verification failed')
  m=z.read('META-INF/MANIFEST.MF').decode()
  if 'Main-Class: io.github.azotorp.wowuisync.server.ServerMain' not in m or 'Implementation-Version:' not in m:raise ValueError('Not a versioned WoW UI Sync server JAR')
  if 'io/github/azotorp/wowuisync/server/maintenance/Readiness.class' not in z.namelist():raise ValueError('Replacement has no application readiness command')
 java=os.environ.get('WUS_JAVA','java');version=run(java,'-jar',str(candidate),'version').stdout.strip()
 if not version.startswith('WoW UI Sync server '):raise ValueError('Replacement version check failed')
 if '--data' in command and pathlib.Path(command[command.index('--data')+1]).resolve()!=data:raise ValueError('--data differs from existing service ExecStart')
 prior=target.stat();backup=target.with_name(target.name+'.previous-'+str(time.time_ns()))
 # A per-installation update lock. Production daemon retains its own data lock during drain.
 import fcntl
 with open(str(target)+'.update.lock','a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  run('systemctl','stop',a.service);service_stopped=True
  shutil.copy2(target,backup);os.chown(backup,prior.st_uid,prior.st_gid)
  fd,tmp=tempfile.mkstemp(prefix=target.name+'.new-',dir=target.parent)
  try:
   with os.fdopen(fd,'wb') as out,open(candidate,'rb') as inp:
    shutil.copyfileobj(inp,out);out.flush();os.fsync(out.fileno());os.fchmod(out.fileno(),stat.S_IMODE(prior.st_mode));os.fchown(out.fileno(),prior.st_uid,prior.st_gid)
   os.replace(tmp,target)
   dfd=os.open(target.parent,os.O_RDONLY)
   try:os.fsync(dfd)
   finally:os.close(dfd)
  finally:
   if os.path.exists(tmp):os.unlink(tmp)
  run('systemctl','start',a.service)
  deadline=time.monotonic()+a.timeout;last=''
  while time.monotonic()<deadline:
   health=run(java,'-jar',str(target),'ready','--data',str(data),check=False);last=health.stdout
   if health.returncode==0 and run('systemctl','is-active','--quiet',a.service,check=False).returncode==0:
    print('Updated '+a.service+' to '+version+'\n'+last+'Previous JAR: '+str(backup));break
   time.sleep(1)
  else:
   run('systemctl','stop',a.service,check=False)
   raise RuntimeError('Application readiness failed. Service stopped. Previous JAR: '+str(backup)+'\n'+last+'\nNo automatic rollback: inspect migration checkpoints before choosing a compatible binary.')
except Exception as e:
 if service_stopped:run('systemctl','stop',a.service,check=False)
 print('Update failed: '+str(e)+'\nExisting configuration and data were not reset.',file=sys.stderr);sys.exit(1)
PY
fi
. "$WUS_SCRIPT_DIR/common.sh"
exec "$WUS_JAVA" "-Dwowuisync.home=$WUS_SCRIPT_DIR" -jar "$WUS_JAR" update "$@"
