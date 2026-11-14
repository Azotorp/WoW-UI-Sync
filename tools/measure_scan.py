#!/usr/bin/env python3
"""Linux process RSS sampling, not physical I/O or a native Windows benchmark."""
import subprocess, sys, time
jar, root, workers, log = sys.argv[1:]
with open(log, 'w') as out:
    p = subprocess.Popen(['java','-Xmx512m',f'-Dwowuisync.scanWorkers={workers}','-cp',jar,'tools/ScanWorkload.java',root], stdout=out, stderr=subprocess.STDOUT)
    peak=0
    while p.poll() is None:
        try:
            for line in open(f'/proc/{p.pid}/status'):
                if line.startswith('VmRSS:'): peak=max(peak,int(line.split()[1]))
        except FileNotFoundError: pass
        time.sleep(.05)
    out.write(f'PROCESS exit={p.returncode} sampledPeakRssKiB={peak}\n')
    sys.exit(p.returncode)
