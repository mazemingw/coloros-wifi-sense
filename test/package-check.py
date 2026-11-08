"""Check the release ZIP, including source/payload identity and test isolation."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
version = re.search(r'^version=(.+)$', (ROOT/'module.prop').read_text(encoding='utf-8'), re.M)[1].strip()
archive = ROOT/f'build/coloros-wifi-sense-{version}.zip'
with zipfile.ZipFile(archive) as z:
    entries = z.infolist()
    names = [i.filename for i in entries]
    assert len(names) == len(set(names)) and len(names) <= 20_000
    assert archive.stat().st_size <= 100*1024**2
    assert sum(i.file_size for i in entries) <= 512*1024**2
    assert sum(i.file_size for i in entries) <= max(1,sum(i.compress_size for i in entries))*200
    for i in entries:
        p=PurePosixPath(i.filename)
        assert not p.is_absolute() and '..' not in p.parts and '\\' not in i.filename
        assert str(p)==i.filename and not i.flag_bits & 1
        assert i.compress_type in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED)
        data=z.read(i)
        if i.filename.startswith('bin/'):
            machines={'arm64-v8a':(2,183),'armeabi-v7a':(1,40),'x86_64':(2,62),'x86':(1,3)}
            elfclass,machine=machines[i.filename.split('/')[1]]
            assert data[:4]==b'\x7fELF' and data[4]==elfclass and int.from_bytes(data[18:20],'little')==machine
            assert data==(ROOT/'build/coloros-wifi-sense-work'/i.filename).read_bytes()
            assert (i.external_attr>>16)&0o111
        elif not i.filename.endswith('.dex'):
            assert b'\r' not in data and not data.startswith(b'\xef\xbb\xbf')
            assert data == (ROOT/i.filename).read_text(encoding='utf-8').replace('\r\n','\n').encode('utf-8')
        if i.filename.endswith('.sh'):assert (i.external_attr >> 16) & 0o111
    prop=z.read('module.prop').decode('utf-8')
    for key in ['id','name','version','versionCode','author','description']:
        assert len(re.findall('^'+key+'=',prop,re.M))==1
    assert 'id=coloros_wifi_sense\n' in prop
    version_code=int(re.search(r'^versionCode=(\d+)$',prop,re.M)[1]);assert 1<=version_code<=2147483647
    assert 'version='+version+'\n' in prop
    required={'wifi-sense.dex','wifi-fluid.dex','webctl.sh','uninstall.sh','webroot/index.html','webroot/app.js','webroot/style.css'}
    required.update('bin/'+abi+'/wifi-band' for abi in ['arm64-v8a','armeabi-v7a','x86_64','x86'])
    assert required <= set(names)
    core=z.read('wifi-sense.dex');fluid=z.read('wifi-fluid.dex')
    assert core.startswith(b'dex\n') and fluid.startswith(b'dex\n')
    for cls in [b'LWifiMonitor;',b'LWifiSystemEvents;',b'LColorOsWifiSense;',b'LWifiBandControl;',b'LWifiBandPolicy;']:assert cls in core
    assert b'LWifiFluidSession;' in fluid and b'LWifiFluidSession;' not in core
    for data in [core,fluid,z.read('webroot/app.js')]:
        for forbidden in [b'WifiDeviceHarness',b'test_control',b'PLATFORM_PROBE',b'cws-event-test',b'web-preview',b'__mock']:
            assert forbidden not in data,forbidden
    assert not any(n.startswith(('test/','docs/','build/')) for n in names)
    for name in ['action.sh','settings.sh','service.sh']:
        # Installer/runtime API compatibility: no changes to existing entry scripts.
        import subprocess
        old=subprocess.check_output(['git','show','v1.1.0:'+name],cwd=ROOT).replace(b'\r\n',b'\n')
        assert z.read(name)==old,name
result={'result':'PASS','version':version,'versionCode':version_code,'entries':len(entries),
        'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
        'checks':['safe ZIP paths/limits','UTF-8/LF source identity','metadata','executable scripts',
                  'separate production DEX','four executable ELF architectures','no test/mock payload','legacy entry scripts unchanged']}
print(json.dumps(result,ensure_ascii=False,indent=2))
