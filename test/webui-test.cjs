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
const initial={state:'IDLE',threshold:-80,rssi:-63,interactive:true,wifi_connections:1,fluid_enabled:true,fluid_installed:true,updated_ms:1,countdown_ms:0,cooldown_ms:0};
const drain=()=>new Promise(resolve=>setImmediate(resolve));
async function fixture(options={}){
  const elements={},buttons=[-75,-80,-85,-90].map(n=>{const e=new Element();e.dataset.threshold=String(n);return e;});
  const ids=['message','refresh','save','threshold','fluid','threshold-value','state','rssi','fluid-help','updated','state-detail','metrics'];
  for(const id of ids)elements[id]=new Element();elements.threshold.value='-80';
  let data={...initial,...options.data};const calls=[];let failure=null;
  const window={};
  if(!options.noBridge)window.ksu={exec:(command,opts,callback)=>{
    calls.push(command);assert.equal(opts,'{}');
    assert.ok(command.startsWith('sh /data/adb/modules/coloros_wifi_sense/webctl.sh '));
    const args=command.split(' ').slice(2);
    queueMicrotask(()=>{
      if(failure)return window[callback](1,'',failure);
      if(args[0]==='threshold'){assert.match(args[1],/^-\d+$/);data.threshold=Number(args[1]);}
      if(args[0]==='fluid'){assert.match(args[1],/^[01]$/);data.fluid_enabled=args[1]==='1';}
      window[callback](0,args[0]==='status'?JSON.stringify({...data,request:args[1]}):'saved','');
    });
  }};
  const context=vm.createContext({window,document:{getElementById:id=>elements[id],querySelectorAll:()=>buttons,createElement:()=>new Element()},setTimeout,clearTimeout,Date,console});
  vm.runInContext(code,context);await drain();await drain();
  return {elements,buttons,calls,data,fail:text=>failure=text};
}
(async()=>{
  let passed=0;
  const test=async(name,f)=>{await f();passed++;console.log('PASS '+name);};
  await test('initial status only, no background polling',async()=>{const f=await fixture();assert.equal(f.calls.length,1);await drain();assert.equal(f.calls.length,1);assert.equal(f.elements.rssi.textContent,'-63');assert.equal(f.elements.message.textContent,'');assert.match(f.elements['state-detail'].textContent,/7 秒/);});
  await test('preset saves validated threshold then refreshes',async()=>{const f=await fixture();await f.buttons[2].fire('click');assert.equal(f.calls.length,1);await f.elements.save.fire('click');assert.equal(f.data.threshold,-85);assert.match(f.calls[1],/threshold -85$/);});
  await test('invalid threshold cannot reach root bridge',async()=>{const f=await fixture();f.elements.threshold.value='-80; id';await f.elements.save.fire('click');assert.equal(f.calls.length,1);assert.match(f.elements.message.textContent,/阈值必须/);});
  await test('notification toggle uses fixed numeric argument',async()=>{const f=await fixture();f.elements.fluid.checked=false;await f.elements.fluid.fire('change');assert.match(f.calls[1],/fluid 0$/);assert.equal(f.data.fluid_enabled,false);});
  await test('core-only installation prevents enabling missing DEX',async()=>{const f=await fixture({data:{fluid_installed:false,fluid_enabled:false}});assert.equal(f.elements.fluid.disabled,true);assert.match(f.elements['fluid-help'].textContent,/未安装/);});
  await test('screen-off status shows pause',async()=>{const f=await fixture({data:{state:'PAUSED',interactive:false,rssi:null}});assert.equal(f.elements.state.textContent,'息屏暂停');assert.equal(f.elements.rssi.textContent,'—');});
  await test('manager errors render as text and unlock controls',async()=>{const f=await fixture();f.fail('<img src=x onerror=alert(1)>');await f.elements.refresh.fire('click');assert.equal(f.elements.message.textContent,'<img src=x onerror=alert(1)>');assert.equal(f.elements.refresh.disabled,false);});
  await test('ordinary browser fails clearly without invented data',async()=>{const f=await fixture({noBridge:true});assert.match(f.elements.message.textContent,/模块管理器/);assert.equal(f.calls.length,0);});
  console.log('TOTAL '+passed+' WebUI tests passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
