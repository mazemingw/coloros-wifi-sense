'use strict';
(() => {
  const path = '/data/adb/modules/coloros_wifi_sense/webctl.sh';
  const $ = id => document.getElementById(id);
  let busy = false, counter = 0, status = null, band = null, connection = null;
  const labels = {PAUSED:'息屏暂停',NO_WIFI:'未连接 Wi-Fi',IDLE:'正常监控',SUSPECT:'检查信号',VERIFY:'弱信号验证',COOLDOWN:'断开冷却',CONFIG_ERROR:'检查阈值配置',STOPPED:'已停止'};
  const metrics = {
    rssi_events:'RSSI 广播',network_events:'网络事件',screen_events:'屏幕事件',rssi_reads:'RSSI 主动读取',
    periodic_runs:'周期任务',countdown_starts:'倒计时启动',countdown_cancels:'倒计时取消',
    disconnect_attempts:'主动断开请求',disconnect_accepted:'系统接受请求',weak_reconnects:'原弱信号 AP 重连',
    fluid_starts:'流体云进程启动',stale_suppressed:'过期操作拦截',read_errors:'读取异常',
    broadcast_deliveries:'独立进程广播接收',cpu_ticks:'进程 CPU 计数（ticks）',
    probe_requests:'通网请求',probe_successes:'通网成功',probe_failures:'通网失败',probe_discarded:'过期探测丢弃',held_networks:'暂避配置数'
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
    for (const id of ['refresh','save','threshold','recovery','save-recovery','screenoff','fluid','countdown','save-countdown','band5g','avoid','avoid-seconds','probe','probe-url','probe-interval','save-avoid','save-probe','restore-autojoin']) $(id).disabled=value;
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
    showRecovery();
  }
  function showRecovery() {
    const margin = Number($('recovery').value);
    const level = Number($('threshold').value) + margin;
    $('recovery-help').textContent = Number.isInteger(margin) && margin >= 1 && margin <= 10
      ? `信号回到 ${String(level).replace('-', '−')} dBm 就取消倒计时。` : '';
  }
  function render(data) {
    status=data;
    $('state').textContent=labels[data.state] || '状态未知';
    $('rssi').textContent=data.rssi == null ? '—' : String(data.rssi);
    $('threshold').value=threshold(data.threshold); showThreshold();
    const seconds=Number(data.countdown_seconds ?? 7);
    $('countdown').value=Number.isInteger(seconds)&&seconds>=1&&seconds<=300?seconds:7;
    $('threshold-help').textContent='信号降到阈值后，开始 '+$('countdown').value+' 秒倒计时。';
    $('fluid').checked=!!data.fluid_enabled;
    $('fluid').disabled=!data.fluid_installed;
    $('fluid-help').textContent=data.fluid_installed ? '弱信号时提醒，可点「立即断开」。' : '安装模块时可选装流体云。';
    $('updated').textContent='更新于 ' + new Date(data.updated_ms).toLocaleTimeString();
    $('state-detail').textContent=data.state === 'CONFIG_ERROR' ? '请重新保存阈值和倒计时。'
      : data.state === 'VERIFY' ? Math.ceil(data.countdown_ms/1000) + ' 秒后检查是否断开。'
      : data.state === 'COOLDOWN' ? '还需等 ' + Math.ceil(data.cooldown_ms/1000) + ' 秒才能再次断开。'
      : data.state === 'PAUSED' ? '亮屏后继续检测。'
      : data.wifi_connections !== 1 ? '连接 Wi-Fi 后开始检测。'
      : !data.interactive && data.screen_off_enabled ? '正在息屏检测。' : '';
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
    await refreshConnection();
  }
  async function refreshConnection(section) {
    connection=JSON.parse(await exec('connectionstatus'));
    if (!section || section === 'screenoff') $('screenoff').checked = !!connection.screen_off_enabled;
    if (!section || section === 'recovery') {
      $('recovery').value = connection.recovery_db ?? 3;
      showRecovery();
    }
    if (!section || section === 'avoid') {
      $('avoid').checked=!!connection.avoid_enabled;
      $('avoid-seconds').value=connection.avoid_seconds;
      $('avoid-help').textContent=[connection.error,status?.connection_error].filter(Boolean).join(' · ');
    }
    if (!section || section === 'probe') {
      $('probe').checked=!!connection.probe_enabled;
      $('probe-url').value=connection.probe_url;
      $('probe-interval').value=connection.probe_interval;
      $('connection-help').textContent=connection.probe_enabled ? status?.probe_result || '' : '';
    }
  }
  function readSeconds(id, min, max, name) {
    const raw = $(id).value;
    const seconds = Number(raw);
    if (!/^\d+$/.test(raw) || !Number.isInteger(seconds) || seconds < min || seconds > max) {
      throw new Error(`${name}必须是 ${min}–${max} 秒的整数。`);
    }
    return seconds;
  }
  function connectionValues(section) {
    if (section === 'avoid') return {
      avoid_enabled: $('avoid').checked,
      avoid_seconds: readSeconds('avoid-seconds', 10, 86400, '暂避时长')
    };
    const interval = readSeconds('probe-interval', 1, 3600, '重试间隔');
    let url;
    try { url = new URL($('probe-url').value.trim()); }
    catch (error) { throw new Error('请输入有效的 HTTP/HTTPS 探测地址。'); }
    if (!/^https?:$/.test(url.protocol) || url.username || url.password || url.href.includes('#') || url.href.length > 2048) {
      throw new Error('探测地址无效，不能包含账号密码或片段。');
    }
    return {probe_enabled: $('probe').checked, probe_interval: interval, probe_url: url.href};
  }
  async function saveConnection(section, feedback) {
    let config;
    try {
      config = connectionValues(section);
      const encoded = btoa(unescape(encodeURIComponent(JSON.stringify(config))));
      await exec(section + 'config ' + encoded);
    } catch (error) {
      if (config) {
        try { await refreshConnection(section); } catch (ignored) { }
      }
      if (connection) $(section).checked = !!connection[section + '_enabled'];
      throw error;
    }
    try { await refreshConnection(section); }
    catch (error) { throw new Error('设置已保存，暂时无法读回确认。'); }
    feedback(section === 'probe' ? `已保存，${connection.probe_interval} 秒后重试。`
      : `已保存，暂避 ${connection.avoid_seconds} 秒。`);
  }
  async function refreshBand() {
    try {
      band=JSON.parse(await exec('bandstatus'));
      $('band5g').checked=!!band.requested;
      $('band-help').textContent=band.message + (band.restore_pending && !band.requested?' 有待还原记录，请重试关闭。':'');
    } catch(e) { band=null; $('band-help').textContent=e.message; }
  }
  async function action(work, feedback = message) {
    if (busy) return;
    lock(true); feedback('');
    try { await work(); } catch(e) { feedback(e.message,true); }
    finally { lock(false); }
  }
  function settingFeedback(section, text, error = false) {
    const element = $(section + '-feedback');
    element.textContent = text;
    element.classList.toggle('error', error);
  }
  $('threshold').addEventListener('input',showThreshold);
  $('recovery').addEventListener('input',showRecovery);
  document.querySelectorAll('[data-threshold]').forEach(b => b.addEventListener('click',() => {
    if (busy) return; $('threshold').value=b.dataset.threshold; showThreshold();
  }));
  $('refresh').addEventListener('click',() => action(refresh));
  const screenFeedback = (text, error) => settingFeedback('screenoff', text, error);
  $('screenoff').addEventListener('change', () => action(async () => {
    const enabled = $('screenoff').checked;
    try { await exec('screenoff ' + (enabled ? '1' : '0')); }
    catch (error) { $('screenoff').checked = !!connection?.screen_off_enabled; throw error; }
    await refreshConnection('screenoff');
    screenFeedback(connection.screen_off_enabled ? '息屏检测已开启。' : '息屏时暂停检测。');
  }, screenFeedback));
  const recoveryFeedback = (text, error) => settingFeedback('recovery', text, error);
  $('save-recovery').addEventListener('click', () => action(async () => {
    const margin = readSeconds('recovery', 1, 10, '回升余量');
    await exec('recovery ' + margin);
    await refreshConnection('recovery');
    recoveryFeedback('已保存。');
  }, recoveryFeedback));
  for (const section of ['avoid', 'probe']) {
    const feedback = (text, error) => settingFeedback(section, text, error);
    const save = () => action(() => saveConnection(section, feedback), feedback);
    $('save-' + section).addEventListener('click', save);
    $(section).addEventListener('change', save);
  }
  $('restore-autojoin').addEventListener('click',()=>action(async()=>{const reply=await exec('restoreautojoin');await refresh();message(reply.trim());}));
  $('save').addEventListener('click',() => action(async() => {
    const value=threshold($('threshold').value);
    await exec('threshold ' + value); await refresh(); message('阈值已保存。');
  }));
  $('save-countdown').addEventListener('click',() => action(async() => {
    const raw=$('countdown').value, seconds=Number(raw);
    if(!/^[1-9]\d{0,2}$/.test(raw)||!Number.isInteger(seconds)||seconds>300)throw new Error('倒计时必须是 1 至 300 秒的整数。');
    await exec('countdown '+seconds); await refresh(); message('倒计时已保存。');
  }));
  $('band5g').addEventListener('change',() => action(async() => {
    try { const response=await exec('band5g '+($('band5g').checked?'1':'0')); await refreshBand(); message(response.trim()); }
    catch(e) { await refreshBand(); throw e; }
  }));
  $('fluid').addEventListener('change',() => action(async() => {
    try { await exec('fluid ' + ($('fluid').checked?'1':'0')); await refresh(); message('提醒设置已保存。'); }
    catch(e) { if(status) $('fluid').checked=!!status.fluid_enabled; throw e; }
  }));
  showThreshold(); action(refresh);
})();
