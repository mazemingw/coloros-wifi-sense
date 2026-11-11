# v1.4.0：SSID 暂避与有限通网检测

日期：2026-11-11。版本 v1.4.0 / versionCode 6。

## 实现范围

两项独立选择，默认均关闭。`connection.json` 保存在原配置目录 `/data/adb/coloros_wifi_sense`，不改变 threshold/countdown/force5g 路径及旧配置，不改安装按键选择、action.sh/settings.sh/service.sh。Web 开关保存即生效，数值及地址可点击「保存连接策略」保存。

自动弱信号断开和自动断网断开可使用同一暂避时长。暂避针对 SSID，而非仅当前 BSSID；同名热点漫游会受影响。手动断开不新增暂避。只修改原本允许自动连接的普通已保存网络，原本禁用的项目保持禁用。

通网检测不是常驻轮询：每连接最多两次；首次成功即结束，连续两次失败才申请断开。配置的间隔只用于第二次重试。已完成后亮屏、RSSI 广播和 Web 刷新不会重启探测；重新连接或主动改变探测开关/地址/间隔才开始新的一轮。取消的在途请求占用次数，保守放弃不能确认的断开。接入点变化丢弃旧结果并结束该轮，不拿旧 AP 失败断开新 AP。

请求绑定当前 Android Wi-Fi Network，关闭重定向，只有 HTTP 204 算成功，连接/读取超时各 3 秒。默认地址来自用户提供的 `2404校园网/install.sh`；没有运行其安装、认证或密码处理部分。DNS 解析实际耗时仍依系统网络栈；息屏使结果失效并关闭连接，不保证 Java DNS 调用立即结束。每次连接默认不开启任何 HTTP 请求。

保持亮屏/冷却/新事件代次/当前网络、SSID、BSSID 的最终门禁；断网分支允许强信号 Wi-Fi 断开，弱信号分支保留最终 RSSI 和阈值检查。流体云沿用一个短时子进程、固定卡片标识和 15 秒结果通知；新断网结果可独立创建通知，不依赖之前存在弱信号倒计时。

## 自动连接还原

使用系统 `allowAutojoin(networkId, false)`，先原子写入 0600 还原记录并 fsync，再修改和回读；失败或最后门禁失效则回滚。到期恢复、关闭选择、正常退出、卸载、下次启动恢复均使用同一还原路径。文件锁串行化守护进程及 Web CLI。恢复会检查 SSID/安全类型身份，跨启动网络 ID 变化时只匹配唯一保存配置，不打开复用 ID 的其他网络。

这个接口是持久的自动连接选择，不是硬件临时黑名单，所以必须有可恢复记录；手动连接可能覆盖它，不能称为禁止一切连接。进程 SIGKILL 后再停用模块会阻断自动恢复，需要重新运行模块或 `webctl.sh restoreautojoin`。系统休眠不安排唤醒，可能晚于时限恢复。建议网络/Passpoint/临时配置不处理。用户后来改变同一网络的自动连接选择与模块原记录之间没有系统级所有权令牌，记录存续期间应优先使用模块提供的恢复按钮。

接口依据：[AOSP WifiServiceImpl.allowAutojoin](https://android.googlesource.com/platform/packages/modules/Wifi/+/29eb2604539a96b84b7e1817306a279865699fec/service/java/com/android/server/wifi/WifiServiceImpl.java)、[AOSP WifiConfigManager](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiConfigManager.java)、[Android Network.openConnection](https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL,java.net.Proxy))。厂商接口及权限策略仍需实机确认。

## 自动验证

在项目根目录运行：

```powershell
./test.ps1
./build.ps1 -AndroidSdk D:/Android/SDK -Jdk 'C:/Program Files/Java/jdk-17'
python -X utf8 test/package-check.py
```

策略/生命周期与断网最终门禁 37 场景；频段事务 14 场景；连接配置、最多两次请求、息屏废弃旧失败、同 SSID、到期、回滚、磁盘故障、恢复失败与网络 ID 复用/重分配 19 场景；WebUI 17 场景，另保留原弱信号策略断言组。Android Java/DEX 与四 ABI 原生组件通过编译。ZIP 验证元数据、源文件一致性、DEX 分离、四 ABI ELF、脚本权限、旧入口一致性及无测试注入负载。完整命令输出保存在本目录 evidence 文件中。

这些测试中的系统接口是可审查的 Fake，不是 Android 上实际执行 autojoin 或流体云；没有把模拟通过当作实机验证。未重新安装虚拟机、未下载新的 SDK/依赖。

## 实机剩余项

本轮 `adb devices -l` 无设备，未刷入手机。仍需 ColorOS 验证：root 是否可读取保存网络和调用 allowAutojoin、Wi-Fi 请求实际路由及蜂窝不代答、同名 AP 暂避/漫游、到期及跨重启还原、模块停用/卸载与进程异常恢复、断网流体云强提醒及 15 秒显示，以及真实功耗。先前 v1.3.0 的手机验证不能替代新增功能验证。
