'use strict';
(() => {
  const path = '/data/adb/modules/coloros_wifi_sense/webctl.sh';
  const $ = id => document.getElementById(id);
  let busy = false, counter = 0, status = null, band = null;
  const labels = {PAUSED:'息屏暂停',NO_WIFI:'未连接 Wi-Fi',IDLE:'正常监控',SUSPECT:'检查信号',VERIFY:'弱信号验证',COOLDOWN:'断开冷却',CONFIG_ERROR:'检查阈值配置',STOPPED:'已停止'};
  const metrics = {
    rssi_events:'RSSI 广播',network_events:'网络事件',screen_events:'屏幕事件',rssi_reads:'RSSI 主动读取',
    periodic_runs:'周期任务',countdown_starts:'倒计时启动',countdown_cancels:'倒计时取消',
    disconnect_attempts:'主动断开请求',disconnect_accepted:'系统接受请求',weak_reconnects:'原弱信号 AP 重连',
    fluid_starts:'流体云进程启动',stale_suppressed:'过期操作拦截',read_errors:'读取异常',
    broadcast_deliveries:'独立进程广播接收',cpu_ticks:'进程 CPU 计数（ticks）'
  };
  function message(text, error = false) { $('message').textContent = text; $('message').classList.toggle('error',error); }
  function exec(args) {
    return new Promise((resolve,reject) => {
      if (!window.ksu || typeof window.ksu.exec !== 'function') return reject(new Error('请从支持 WebUI 的模块管理器打开。'));
      const callback = 'wifiSenseReply' + (++counter);
      const timer = setTimeout(() => { delete window[callback]; reject(new Error('管理器未返回结果，请刷新状态确认。')); },args.startsWith('band') ? 30000 : 5000);
      window[callback] = (errno,stdout,stderr) => {
        clearTimeout(timer); delete window[callback];
        Number(errno) === 0 ? resolve(String(stdout)) : reject(new Error(String(stderr || stdout || '操作失败')));
      };
      try { window.ksu.exec('sh ' + path + ' ' + args, '{}', callback); }
      catch (e) { clearTimeout(timer); delete window[callback]; reject(e); }
    });
  }
  function lock(value) {
    busy = value;
    for (const id of ['refresh','save','threshold','fluid','countdown','save-countdown','band5g']) $(id).disabled=value;
    if (!value && status && !status.fluid_installed) $('fluid').disabled=true;
    if (!value && (!band || (!band.supported && !band.requested && !band.restore_pending))) $('band5g').disabled=true;
  }
  function threshold(value) {
    const n=Number(value);
    if (!Number.isInteger(n) || n < -100 || n > -20) throw new Error('阈值必须是 −100 至 −20 的整数。');
    return n;
  }
  function showThreshold() {
    $('threshold-value').textContent = $('threshold').value.replace('-','−') + ' dBm';
    document.querySelectorAll('[data-threshold]').forEach(b => b.classList.toggle('selected',b.dataset.threshold === $('threshold').value));
  }
  function render(data) {
    status=data;
    $('state').textContent=labels[data.state] || '状态未知';
    $('rssi').textContent=data.rssi == null ? '—' : String(data.rssi);
    $('threshold').value=threshold(data.threshold); showThreshold();
    const seconds=Number(data.countdown_seconds ?? 7);
    $('countdown').value=Number.isInteger(seconds)&&seconds>=1&&seconds<=300?seconds:7;
    $('threshold-help').textContent='信号持续低于或等于阈值时，倒计时 '+$('countdown').value+' 秒后断开当前接入点。';
    $('fluid').checked=!!data.fluid_enabled;
    $('fluid').disabled=!data.fluid_installed;
    $('fluid-help').textContent=data.fluid_installed ? '提醒中关闭开关，可立即断开。' : '未安装流体云组件，重新安装时可选择开启。';
    $('updated').textContent='更新于 ' + new Date(data.updated_ms).toLocaleTimeString();
    $('state-detail').textContent=!data.interactive ? '设备未处于交互状态，监控与倒计时已暂停。'
      : data.state === 'CONFIG_ERROR' ? '阈值或倒计时配置无效，请保存有效设置后恢复监控。'
      : data.state === 'VERIFY' ? Math.ceil(data.countdown_ms/1000) + ' 秒后再次确认信号并断开。'
      : data.state === 'COOLDOWN' ? '冷却剩余约 ' + Math.ceil(data.cooldown_ms/1000) + ' 秒。'
      : data.wifi_connections !== 1 ? '等待单一 Wi-Fi 连接，当前不进行信号采样。' : '监听网络变化，亮屏时保留 7 秒信号检查兜底。';
    const list=$('metrics'); list.replaceChildren();
    Object.entries(metrics).forEach(([key,label]) => {
      const dt=document.createElement('dt'), dd=document.createElement('dd');
      dt.textContent=label; dd.textContent=String(data[key] ?? '—'); list.append(dt,dd);
    });
  }
  async function refresh() {
    const nonce='ui_' + Date.now() + '_' + (++counter);
    render(JSON.parse(await exec('status ' + nonce)));
    await refreshBand();
  }
  async function refreshBand() {
    try {
      band=JSON.parse(await exec('bandstatus'));
      $('band5g').checked=!!band.requested;
      $('band-help').textContent=band.message + (band.restore_pending && !band.requested?' 有待还原记录，请重试关闭。':'');
    } catch(e) { band=null; $('band-help').textContent=e.message; }
  }
  async function action(work) {
    if (busy) return;
    lock(true); message('');
    try { await work(); } catch(e) { message(e.message,true); }
    finally { lock(false); }
  }
  $('threshold').addEventListener('input',showThreshold);
  document.querySelectorAll('[data-threshold]').forEach(b => b.addEventListener('click',() => {
    if (busy) return; $('threshold').value=b.dataset.threshold; showThreshold();
  }));
  $('refresh').addEventListener('click',() => action(refresh));
  $('save').addEventListener('click',() => action(async() => {
    const value=threshold($('threshold').value);
    await exec('threshold ' + value); await refresh(); message('阈值已保存。');
  }));
  $('save-countdown').addEventListener('click',() => action(async() => {
    const raw=$('countdown').value, seconds=Number(raw);
    if(!/^[1-9]\d{0,2}$/.test(raw)||!Number.isInteger(seconds)||seconds>300)throw new Error('倒计时必须是 1 至 300 秒的整数。');
    await exec('countdown '+seconds); await refresh(); message('自动断开倒计时已保存。');
  }));
  $('band5g').addEventListener('change',() => action(async() => {
    try { const response=await exec('band5g '+($('band5g').checked?'1':'0')); await refreshBand(); message(response.trim()); }
    catch(e) { await refreshBand(); throw e; }
  }));
  $('fluid').addEventListener('change',() => action(async() => {
    try { await exec('fluid ' + ($('fluid').checked?'1':'0')); await refresh(); message('通知设置已保存，开启后从下一次提醒生效。'); }
    catch(e) { if(status) $('fluid').checked=!!status.fluid_enabled; throw e; }
  }));
  showThreshold(); action(refresh);
})();
