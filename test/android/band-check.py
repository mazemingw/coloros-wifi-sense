"""Temporary REAL driver band toggle, always restore; private module/state fixture."""
import json,re,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
ADB=r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
BASE='/data/local/tmp/cws-band-test'
OUT=ROOT/'build/band-audit'
def shell(cmd,check=True):
    p=subprocess.run([ADB,'shell','su','-c',cmd],capture_output=True,text=True,encoding='utf-8',timeout=30)
    if check and p.returncode:raise RuntimeError(p.stdout+p.stderr)
    return p
def command(args,check=True):return shell('sh '+BASE+'/module/webctl.sh '+args,check)
def status():return json.loads(command('bandstatus').stdout)
def main():
    OUT.mkdir(parents=True,exist_ok=True);fixture=OUT/'fixture';(fixture/'module').mkdir(parents=True,exist_ok=True)
    version=re.search(r'^version=(.+)$',(ROOT/'module.prop').read_text(encoding='utf-8'),re.M)[1].strip()
    with zipfile.ZipFile(ROOT/f'build/coloros-wifi-sense-{version}.zip') as z:
        for name in ['wifi-sense.dex','bin/arm64-v8a/wifi-band']:
            p=fixture/'module'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
    for name in ['webctl.sh','settings.sh']:
        (fixture/'module'/name).write_bytes((ROOT/name).read_text(encoding='utf-8').replace('/data/adb/coloros_wifi_sense',BASE+'/state').replace('\r\n','\n').encode())
    shell('mkdir -p '+BASE+'/state; chmod 700 '+BASE+'/state')
    subprocess.run([ADB,'push',str(fixture/'module'),BASE+'/'],check=True)
    shell('chmod 755 '+BASE+'/module/bin/arm64-v8a/wifi-band')
    result={};original=None
    try:
        result['before']=status();original=result['before']['mask'];assert result['before']['supported'] and not result['before']['requested']
        command('band5g 1');result['enabled']=status();assert result['enabled']['requested'] and result['enabled']['mask']==1
        backup=json.loads(shell('cat '+BASE+'/state/band_original').stdout)
        assert backup['mask']==original
        assert shell('stat -c %a '+BASE+'/state/band_original').stdout.strip()=='600'
        command('band5g 1');assert json.loads(shell('cat '+BASE+'/state/band_original').stdout)['mask']==original
        shell('touch '+BASE+'/module/disable')
        try:assert command('band5g 1',False).returncode!=0
        finally:shell('rm '+BASE+'/module/disable')
        command('band5g 0');result['disabled']=status();assert not result['disabled']['requested'] and result['disabled']['mask']==original and not result['disabled']['restore_pending']
        for args in ['band5g 2','band5g 01','band5g 1 extra','bandstatus extra']:
            assert command(args,False).returncode!=0
        result['result']='PASS';print('PASS real runtime band enable/readback, exact restore, repeated-enable baseline, disabled-module guard, 0600 backup and invalid arguments')
    finally:
        restore=command('band5g 0',False);result['cleanup_exit']=restore.returncode
        result['final']=status()
        (OUT/'band-device-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        if original is not None:assert result['final']['mask']==original,'REAL BAND RESTORATION FAILED'
if __name__=='__main__':main()
