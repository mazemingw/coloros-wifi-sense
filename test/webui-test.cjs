'use strict';
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const code=fs.readFileSync(require('node:path').join(__dirname,'../webroot/app.js'),'utf8');
class Element {
  get value(){return this._value;} set value(value){this._value=String(value);}
  constructor(){this.value='';this.textContent='';this.checked=false;this.disabled=false;this.handlers={};this.children=[];this.dataset={};this.classes=new Set();this.classList={toggle:(s,v)=>v?this.classes.add(s):this.classes.delete(s)};}
  addEventListener(name,f){this.handlers[name]=f;}
  replaceChildren(){this.children=[];} append(...e){this.children.push(...e);}
  async fire(name){if(!this.disabled&&this.handlers[name])await this.handlers[name]();}
}
const initial={state:'IDLE',threshold:-80,rssi:-63,interactive:true,wifi_connections:1,fluid_enabled:true,fluid_installed:true,updated_ms:1,countdown_ms:0,cooldown_ms:0,countdown_seconds:7};
const drain=()=>new Promise(resolve=>setImmediate(resolve));
async function fixture(options={}){
  const elements={},buttons=[-75,-80,-85,-90].map(n=>{const e=new Element();e.dataset.threshold=String(n);return e;});
  const ids=['message','refresh','save','threshold','fluid','threshold-value','state','rssi','fluid-help','updated','state-detail','metrics','countdown','save-countdown','threshold-help','band5g','band-help','avoid','avoid-seconds','probe','probe-url','probe-interval','save-probe','save-avoid','restore-autojoin','connection-help','avoid-help','probe-feedback','avoid-feedback'];
  for(const id of ids)elements[id]=new Element();elements.threshold.value='-80';
  let data={...initial,...options.data};let band={requested:false,supported:true,applied:false,mask:7,message:'当前允许系统选择频段。',...options.band};const config={avoid_enabled:false,avoid_seconds:300,probe_enabled:false,probe_interval:30,probe_url:'http://connect.rom.miui.com/generate_204'};const calls=[];let failure=null;
  const window={};
  if(!options.noBridge)window.ksu={exec:(command,opts,callback)=>{
    calls.push(command);assert.equal(opts,'{}');
    assert.ok(command.startsWith('sh /data/adb/modules/coloros_wifi_sense/webctl.sh '));
    const args=command.split(' ').slice(2);
    queueMicrotask(()=>{
      if(failure)return window[callback](1,'',failure);
      if(['probeconfig','avoidconfig'].includes(args[0]))Object.assign(config,JSON.parse(Buffer.from(args[1],'base64').toString('utf8')));
      if(args[0]==='threshold'){assert.match(args[1],/^-\d+$/);data.threshold=Number(args[1]);}
      if(args[0]==='countdown'){data.countdown_seconds=Number(args[1]);}
      if(args[0]==='band5g'){assert.match(args[1],/^[01]$/);band.requested=args[1]==='1';band.applied=band.requested;band.mask=band.requested?1:7;}
      if(args[0]==='fluid'){assert.match(args[1],/^[01]$/);data.fluid_enabled=args[1]==='1';}
      window[callback](0,args[0]==='status'?JSON.stringify({...data,request:args[1]}):args[0]==='bandstatus'?JSON.stringify(band):args[0]==='connectionstatus'?JSON.stringify(config):'saved','');
    });
  }};
  const context=vm.createContext({window,document:{getElementById:id=>elements[id],querySelectorAll:()=>buttons,createElement:()=>new Element()},setTimeout,clearTimeout,Date,console,URL,btoa:s=>Buffer.from(s,'binary').toString('base64'),unescape,encodeURIComponent});
  vm.runInContext(code,context);await drain();await drain();
  return {elements,buttons,calls,data,band,config,fail:text=>failure=text};
}
(async()=>{
  let passed=0;
  const test=async(name,f)=>{await f();passed++;console.log('PASS '+name);};
  await test('initial status only, no background polling',async()=>{const f=await fixture();assert.equal(f.calls.length,3);await drain();assert.equal(f.calls.length,3);assert.equal(f.elements.rssi.textContent,'-63');assert.equal(f.elements.message.textContent,'');assert.match(f.elements['state-detail'].textContent,/7 秒/);});
  await test('preset saves validated threshold then refreshes',async()=>{const f=await fixture();await f.buttons[2].fire('click');assert.equal(f.calls.length,3);await f.elements.save.fire('click');assert.equal(f.data.threshold,-85);assert.match(f.calls[3],/threshold -85$/);});
  await test('invalid threshold cannot reach root bridge',async()=>{const f=await fixture();f.elements.threshold.value='-80; id';await f.elements.save.fire('click');assert.equal(f.calls.length,3);assert.match(f.elements.message.textContent,/阈值必须/);});
  await test('notification toggle uses fixed numeric argument',async()=>{const f=await fixture();f.elements.fluid.checked=false;await f.elements.fluid.fire('change');assert.match(f.calls[3],/fluid 0$/);assert.equal(f.data.fluid_enabled,false);});
  await test('core-only installation prevents enabling missing DEX',async()=>{const f=await fixture({data:{fluid_installed:false,fluid_enabled:false}});assert.equal(f.elements.fluid.disabled,true);assert.match(f.elements['fluid-help'].textContent,/未安装/);});
  await test('screen-off status shows pause',async()=>{const f=await fixture({data:{state:'PAUSED',interactive:false,rssi:null}});assert.equal(f.elements.state.textContent,'息屏暂停');assert.equal(f.elements.rssi.textContent,'—');});
  await test('manager errors render as text and unlock controls',async()=>{const f=await fixture();f.fail('<img src=x onerror=alert(1)>');await f.elements.refresh.fire('click');assert.equal(f.elements.message.textContent,'<img src=x onerror=alert(1)>');assert.equal(f.elements.refresh.disabled,false);});
  await test('ordinary browser fails clearly without invented data',async()=>{const f=await fixture({noBridge:true});assert.match(f.elements.message.textContent,/模块管理器/);assert.equal(f.calls.length,0);});
  await test('countdown saves validated seconds',async()=>{const f=await fixture();f.elements.countdown.value='30';await f.elements['save-countdown'].fire('click');assert.equal(f.data.countdown_seconds,30);assert.match(f.elements['threshold-help'].textContent,/30 秒/);});
  await test('invalid countdown never reaches bridge',async()=>{for(const value of ['0','301','2.5','07','1;id','']){const f=await fixture();f.elements.countdown.value=value;await f.elements['save-countdown'].fire('click');assert.equal(f.calls.length,3);assert.match(f.elements.message.textContent,/1 至 300/);}});
  await test('band toggle applies and reads back actual state',async()=>{const f=await fixture();f.elements.band5g.checked=true;await f.elements.band5g.fire('change');assert.match(f.calls[3],/band5g 1$/);assert.equal(f.band.mask,1);assert.equal(f.elements.band5g.checked,true);});
  await test('unsupported driver disables new restriction',async()=>{const f=await fixture({band:{supported:false}});assert.equal(f.elements.band5g.disabled,true);});
  await test('an existing band request can still be turned off when probe fails',async()=>{const f=await fixture({band:{supported:false,requested:true}});assert.equal(f.elements.band5g.disabled,false);});
  await test('connection switches are independent and save a safe encoded payload',async()=>{const f=await fixture();f.elements.probe.checked=true;await f.elements.probe.fire('change');assert.equal(f.config.probe_enabled,true);assert.equal(f.config.avoid_enabled,false);assert.match(f.calls[3],/probeconfig [A-Za-z0-9+/=]+$/);});
  await test('SSID duration persists while probe stays off',async()=>{const f=await fixture();f.elements.avoid.checked=true;f.elements['avoid-seconds'].value='600';await f.elements.avoid.fire('change');assert.equal(f.config.avoid_seconds,600);assert.equal(f.config.avoid_enabled,true);assert.equal(f.config.probe_enabled,false);});
  await test('short probe interval saves independently and reads back',async()=>{
    const f=await fixture();
    f.elements['avoid-seconds'].value='9';
    f.elements['probe-interval'].value='5';
    await f.elements['save-probe'].fire('click');
    assert.equal(f.config.probe_interval,5);
    assert.equal(f.config.avoid_seconds,300);
    assert.equal(f.elements['avoid-seconds'].value,'9');
    assert.equal(f.elements['probe-interval'].value,'5');
    assert.match(f.elements['probe-feedback'].textContent,/5 秒/);
    assert.equal(f.calls.length,5);
  });
  await test('invalid duration and URL are shown beside the corresponding save',async()=>{
    for(const [section,id,value] of [['avoid','avoid-seconds','9'],['avoid','avoid-seconds','86401'],['probe','probe-interval','0'],['probe','probe-url','file:///data/test'],['probe','probe-url','http://u:p@example.com/']]){
      const f=await fixture();
      f.elements[id].value=value;
      await f.elements['save-'+section].fire('click');
      assert.equal(f.calls.length,3);
      assert.ok(f.elements[section+'-feedback'].classes.has('error'));
    }
  });
  await test('manual restore uses fixed command',async()=>{const f=await fixture();await f.elements['restore-autojoin'].fire('click');assert.match(f.calls[3],/restoreautojoin$/);});
  console.log('TOTAL '+passed+' WebUI tests passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
