import json,subprocess,time
from pathlib import Path
adb=r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
base='/data/local/tmp/cws-event-test'; result={}
def cmd(s):return subprocess.check_output([adb,'shell','su','-c',s],text=True,encoding='utf-8')
def status(name):
    d=json.loads(cmd('sh '+base+'/module/webctl.sh status '+name+'_'+str(time.time_ns())));result[name]=d;return d
try:
    initial=status('config_initial')
    cmd('echo broken > '+base+'/state/threshold');time.sleep(.3)
    invalid=status('config_invalid');assert invalid['state']=='CONFIG_ERROR' and not invalid['sample_scheduled']
    time.sleep(2)
    quiet=status('config_quiet');assert quiet['rssi_reads']==invalid['rssi_reads']
    cmd('sh '+base+'/module/webctl.sh threshold -80')
    repaired=status('config_repaired');assert repaired['state']=='IDLE' and repaired['threshold']==-80
    cmd('sh '+base+'/module/webctl.sh countdown 30')
    duration=status('custom_duration');assert duration['countdown_seconds']==30
    cmd('echo weak > '+base+'/state/test_control');time.sleep(1.2)
    ready=status('config_verify');assert ready['state']=='VERIFY' and ready['countdown_ms']>27000
    cmd('sh '+base+'/module/webctl.sh countdown 2')
    restarted=status('custom_restarted');assert restarted['countdown_seconds']==2 and 500<restarted['countdown_ms']<=2000
    time.sleep(2.3)
    cut=status('config_final_preflight');assert cut['disconnect_attempts']==1 and cut['disconnect_accepted']==0
    cmd('echo broken > '+base+'/state/countdown');time.sleep(.2)
    invalid_duration=status('invalid_duration');assert invalid_duration['state']=='CONFIG_ERROR' and not invalid_duration['sample_scheduled']
    cmd('sh '+base+'/module/webctl.sh countdown 7')
    restored=status('duration_repaired');assert restored['countdown_seconds']==7 and restored['state']=='COOLDOWN'
    print('PASS native malformed-config quiet state, custom duration/restart, automatic expiry protected at final gate and repair')
finally:
    cmd('echo stop > '+base+'/state/test_control')
    Path('build/power-audit/config-device-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
