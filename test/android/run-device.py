"""Stage an isolated fixture and run a radio-protected Android integration check."""
import argparse,json,queue,re,subprocess,sys,threading,time,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
ADB=r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
BASE='/data/local/tmp/cws-event-test'
def shell(command,check=True):
    p=subprocess.run([ADB,'shell','su','-c',command],capture_output=True,text=True,encoding='utf-8',timeout=10)
    if check and p.returncode:raise RuntimeError(p.stderr or p.stdout)
    return p
def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['lifecycle','config']);args=parser.parse_args()
    if shell('pidof cws-device-harness',False).returncode==0:raise SystemExit('Another test harness is still running; stop it first')
    out=ROOT/'build/device-harness';fixture=out/'fixture';(fixture/'module').mkdir(parents=True,exist_ok=True);(fixture/'state').mkdir(exist_ok=True)
    for name in ['module.prop','settings.sh','webctl.sh']:
        text=(ROOT/name).read_text(encoding='utf-8').replace('/data/adb/coloros_wifi_sense',BASE+'/state')
        (fixture/'module'/name).write_bytes(text.replace('\r\n','\n').encode('utf-8'))
    (fixture/'module/fluid_enabled').write_bytes(b'0\n');(fixture/'state/threshold').write_bytes(b'-80\n')
    with zipfile.ZipFile(ROOT/'build/coloros-wifi-sense-v1.2.0.zip') as z:(fixture/'module/wifi-fluid.dex').write_bytes(z.read('wifi-fluid.dex'))
    shell('mkdir -p '+BASE)
    for local,target in [(fixture/'module',BASE+'/'),(fixture/'state',BASE+'/'),(out/'classes.dex',BASE+'/test.dex')]:
        subprocess.run([ADB,'push',str(local),target],check=True)
    command='CLASSPATH='+BASE+'/test.dex app_process /system/bin --nice-name=cws-device-harness WifiDeviceHarness '+BASE+'/module '+BASE+'/state'
    p=subprocess.Popen([ADB,'shell','su','-c',command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
    lines=[];ready=queue.Queue();pid=None
    def read():
        for line in p.stdout:
            lines.append(line.rstrip())
            if line.startswith('TEST_READY'):ready.put(int(re.search(r'pid=(\d+)',line)[1]))
    threading.Thread(target=read,daemon=True).start()
    try:
        pid=ready.get(timeout=15)
        for _ in range(30):
            try:
                d=json.loads(shell('cat '+BASE+'/state/status.json').stdout)
                if d.get('pid')==pid:break
            except (RuntimeError,json.JSONDecodeError):pass
            time.sleep(.1)
        else:raise RuntimeError('No fresh startup status from this harness')
        script='device-check.py' if args.mode=='lifecycle' else 'config-check.py'
        result=subprocess.run([sys.executable,'-X','utf8',str(Path(__file__).with_name(script))],cwd=ROOT)
        if result.returncode:raise RuntimeError('Device assertions failed')
        assert p.wait(timeout=10)==0,lines
        print('PASS harness exited cleanly; actual radio calls remained intercepted')
    finally:
        shell('echo stop > '+BASE+'/state/test_control',False)
        if p.poll() is None and pid:
            name=subprocess.run([ADB,'shell','su','-c',f'cat /proc/{pid}/cmdline'],capture_output=True).stdout.split(b'\0')[0]
            if name==b'cws-device-harness':shell(f'kill {pid}',False)
        (ROOT/'build/power-audit'/('device-'+args.mode+'-process.log')).write_text('\n'.join(lines)+'\n',encoding='utf-8')
if __name__=='__main__':main()
