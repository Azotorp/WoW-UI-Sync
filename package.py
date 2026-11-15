#!/usr/bin/env python3
"""Reproducible archive from an explicit deployable/source allowlist. Never includes stores, logs or private fixtures."""
from pathlib import Path
import hashlib
import zipfile
import subprocess

root=Path(__file__).resolve().parent
name='WoW-UI-Sync-1.2.0-alpha'
out=root/'dist'/f'{name}.zip'
files={}
for filename in ['README.md','UPGRADE.md','TEST-REPORT.md','DESIGN.md','DESIGN-1.2.md','AGENTS.md','CLIENT_REFACTOR_PLAN.md','CLIENT-UPDATE.md','DEVLOG.md','LICENSE','pom.xml','build.sh','package.sh','package.py']:
    files[filename]=root/filename
for directory in ['common','client','server','tests','benchmarks']:
    for file in sorted((root/directory).rglob('*')):
        if not file.is_file() or any(part in ['target','__pycache__','.git'] for part in file.parts):continue
        if file.suffix in ['.java','.xml','.json','.py','.sh','.md','.csv']:files[file.relative_to(root).as_posix()]=file
for kind in ['client','server']:
    jar=root/'dist'/f'wow-ui-sync-{kind}.jar'
    if not jar.is_file():raise SystemExit(f'Missing compiled JAR: {jar}; run build.sh')
    with zipfile.ZipFile(jar) as z:
        manifest=z.read('META-INF/MANIFEST.MF').decode()
        if 'Main-Class:' not in manifest or 'Implementation-Version: 1.2.0-alpha' not in manifest:raise SystemExit('Invalid JAR manifest')
    files[f'{kind}/wow-ui-sync.{kind}.jar']=jar
try:
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
    build_info=f'Source commit: {commit}\nBranch: feature/1.2.0-alpha-sync-redesign\nBaseline: main 4076848 (restored 1.0.0)\n'
except (OSError,subprocess.CalledProcessError):
    info=root/'BUILD-INFO.txt'
    build_info=info.read_text() if info.exists() else 'Source commit unavailable outside Git checkout\n'
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    info=zipfile.ZipInfo(f'{name}/BUILD-INFO.txt',date_time=(2026,11,14,0,0,0))
    info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
    z.writestr(info,build_info)
    for relative,file in sorted(files.items()):
        info=zipfile.ZipInfo(f'{name}/{relative}',date_time=(2026,11,14,0,0,0))
        info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=(0o100755 if file.suffix=='.sh' else 0o100644)<<16
        z.writestr(info,file.read_bytes())
checksum=hashlib.sha256(out.read_bytes()).hexdigest()
(out.with_suffix('.zip.sha256')).write_text(f'{checksum}  {out.name}\n')
print(f'{out}\nSHA256 {checksum}\n{len(files)} files')
