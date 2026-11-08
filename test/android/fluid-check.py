import json,queue,subprocess,threading,time
from pathlib import Path
adb=r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
result=[]
def screen(code):subprocess.run([adb,'shell','su','-c','input keyevent '+str(code)],check=True,capture_output=True)
def launch(token):
    command='CLASSPATH=/data/local/tmp/cws-event-test/module/wifi-fluid.dex app_process /system/bin --nice-name=cws-fluid-device-probe WifiFluidSession demo '+token+' -84 -80 7000 0'
    p=subprocess.Popen([adb,'shell','su','-c',command],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
    q=queue.Queue();lines=[]
    def read():
        for line in p.stdout:lines.append(line.rstrip());q.put(line)
    threading.Thread(target=read,daemon=True).start()
    end=time.monotonic()+10
    while time.monotonic()<end:
        s=q.get(timeout=10)
        if 'WIFI_SENSE ready '+token in s:return p,lines
        if p.poll() is not None:raise AssertionError(lines)
    raise AssertionError('renderer not ready')
try:
    screen(224)
    p,lines=launch('screen_probe')
    time.sleep(1)
    started=time.monotonic();screen(223)
    code=p.wait(timeout=4)
    assert code==0,(code,lines)
    result.append({'case':'real screen off closes native fluid renderer','exit':code,'elapsed_seconds':round(time.monotonic()-started,3),'lines':lines})
    print('PASS screen-off renderer cleanup',flush=True)
    screen(224);time.sleep(.5)
    p,lines=launch('retention_probe')
    started=time.monotonic();p.stdin.write('result auto\n');p.stdin.flush()
    time.sleep(1);assert p.poll() is None,'result withdrawn immediately'
    code=p.wait(timeout=18);elapsed=time.monotonic()-started
    assert code==0 and 14<elapsed<18,(code,elapsed,lines)
    result.append({'case':'native result retained 15 seconds','exit':code,'elapsed_seconds':round(elapsed,3),'lines':lines})
    print('PASS 15-second result lifetime',flush=True)
finally:
    screen(224)
    Path('build/power-audit/fluid-device-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
