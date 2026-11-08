import json,queue,subprocess,threading,time
from pathlib import Path
adb=r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
args=[adb,'shell','su','-c','CLASSPATH=/data/local/tmp/wifi-platform-probe.dex app_process /system/bin --nice-name=cws-broadcast-probe WifiPlatformProbe']
proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
lines=[];ready=queue.Queue();pid=None
def reader():
    for line in proc.stdout:
        lines.append(line.rstrip())
        if line.startswith('PROBE_PID='):ready.put(('pid',int(line.split('=')[1])))
        if line.startswith('PROBE_READY'):ready.put(('ready',True))
threading.Thread(target=reader,daemon=True).start()
try:
    while True:
        k,v=ready.get(timeout=10)
        if k=='pid':pid=v
        if k=='ready':break
    subprocess.run([adb,'shell','su','-c','am broadcast -a com.nvmex.wifisense.PLATFORM_PROBE'],capture_output=True,check=True)
    time.sleep(2)
    dump=subprocess.check_output([adb,'shell','su','-c','dumpsys activity broadcasts'],text=True,encoding='utf-8')
    registered=dump.split('Historical broadcasts',1)[0]
    result={'probe_pid':pid,'callback_delivered':any('BROADCAST=com.nvmex.wifisense.PLATFORM_PROBE' in s for s in lines),
            'action_in_registered_section':'com.nvmex.wifisense.PLATFORM_PROBE' in registered,
            'registration_returned_null':'REGISTER=null' in lines,'log':lines}
    Path('build/power-audit/broadcast-probe.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='log'},ensure_ascii=False))
finally:
    if pid:
        name=subprocess.run([adb,'shell','su','-c',f'cat /proc/{pid}/cmdline'],capture_output=True).stdout.split(b'\0')[0]
        if name==b'cws-broadcast-probe':subprocess.run([adb,'shell','su','-c',f'kill {pid}'],capture_output=True)
    try:proc.wait(timeout=5)
    except subprocess.TimeoutExpired:proc.terminate()
