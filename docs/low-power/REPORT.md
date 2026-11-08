# v1.2.0 交互期弱 Wi-Fi 监控审查与验证

日期：2026-11-08。目标是亮屏时及时处理弱 Wi-Fi 黏连，息屏时停止主动业务；沿用 root `app_process`、Binder 和现有配置。没有引入 APK、常驻 HTTP 服务、第三方运行依赖、WakeLock、主动扫描或网络探测。

## 审查结论与机制变化

原调用链为 `service.sh → ColorOsWifiSense → Handler 固定 7 秒检查 → WifiDisconnectPolicy → disconnect`，流体云模式在倒计时期间另外逐秒确认。原守护进程不检查屏幕交互状态，配置和模块标志在采样中反复访问。新调用链为系统事件/有限兜底 → 单线程 `WifiMonitor` → 策略提议 → 新鲜状态复核 → Android 适配器最终检查 → Binder。流体云仅在需要通知时创建一个可选子进程。

| 情况 | 原机制 | v1.2.0 |
|---|---|---|
| 息屏且仍连接 Wi-Fi | 7 秒检查，可能继续倒计时和断开 | PAUSED；无 RSSI 周期采样、倒计时、断开或通知更新 |
| 亮屏正常连接 | 固定 7 秒 | 事件合并 + 最长 7 秒兜底；事件可提前读取并重置兜底 |
| 可疑信号 | 等待下一次检查 | SUSPECT；事件防抖 250 ms，事件读取间隔至少 1 秒 |
| 达到阈值 | 7 秒倒计时 | VERIFY；仍为 7 秒，两个模式均按需每秒确认 |
| 断开冷却 | 检查仍继续 | COOLDOWN；60 秒保护，一次到期任务；新连接/唤醒按需读一次 |
| 未连接/只有移动数据 | 7 秒任务继续检查状态 | NO_WIFI；无周期任务或 RSSI 读取 |
| 阈值读取 | 周期读取文件 | FileObserver 缓存；唤醒及罕见最终断开前再核对 |
| 流体云 | 可选临时子进程 | 保留外观/开关/进度条；同一时间一个子进程，退出后才替换 |

IDLE/SUSPECT/VERIFY/COOLDOWN/PAUSED 状态集中在 `src/WifiMonitor.java`。非法配置进入 CONFIG_ERROR 静默状态，修复原阈值也能恢复，避免到期倒计时每秒重试。

## 息屏暂停与竞态

使用 `PowerManager.isInteractive()` 作为交互状态依据。屏幕关闭回调立即设置 volatile 闸门并递增事件代次，然后主 Handler 删除采样、确认任务，取消未完成策略和全部流体云反馈。息屏期间网络/RSSI 回调可更新事件计数，但不读取 RSSI、不执行主动断开。可存在最多一次用于终止流体云子进程的有界清理任务；它不是 RSSI 或网络干预任务。

亮屏时重新读取配置和系统维护的网络集合，立即读取一次当前 WifiInfo，重新核对 BSSID；不恢复旧倒计时。读取期间收到新事件、手动开关回调迟到、断开确认迟到、息屏/断开前交错等均受事件代次和交互闸门保护。

每次真正尝试断开前检查：PM 仍交互、仅一个当前 Wi-Fi、最新 RSSI、观察时 BSSID/Network、阈值及文件配置、倒计时/手动条件、60 秒冷却、新事件代次、网络 Wi-Fi transport 和模块 disable/remove。最后在 Binder 调用前再查 PM 与代次。预检查失败不会消费冷却；实际 Binder 尝试后才提交冷却。新事件与系统 Binder 操作之间不可能获得跨系统的原子事务，最后闸门尽量缩小该窗口。

## RSSI 广播与 root 架构边界

[AOSP ClientModeImpl](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/ClientModeImpl.java) 通常在信号等级改变时发送 RSSI 广播；原始 RSSI 变化不保证逐 dBm 广播，调试日志模式存在额外发送条件。新系统还可合并、延迟广播。用户自定义阈值可能处于一个信号等级内部，因此不能仅依赖广播，更不能把广播 extras 作为最终断开依据。

独立 root `app_process` 不是普通 AMS 应用进程。[AOSP BroadcastController](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/BroadcastController.java) 的新注册实现要求 caller 对应 ProcessRecord；返回 null 既可能是无 sticky Intent，也可能是根本未注册。当前实现尝试订阅 SCREEN_ON/OFF、RSSI、连接状态广播，但不将返回值视为成功。

在本次连接的 OnePlus PLK110 上，root 动态广播探针返回 null，注册列表没有该 receiver，实际发送测试广播后没有回调。**不能宣称当前独立 root 架构可持续接收 RSSI_CHANGED_ACTION。** 生产代码同时使用被动 NetworkCallback，过滤信号/BSSID 相同的重复能力回调。

屏幕生命周期通过 ColorOS `OplusPowerManager` 屏幕监听与公开 DisplayManager 回调提供补充，PM 复核是最终依据。本机验证 Oplus 监听可注册，实际熄屏/亮屏能暂停/恢复。其他 ROM 上使用 DisplayManager；两个通道都无法建立则拒绝启动主动监控。公开 API 语义见 [PowerManager.isInteractive](https://developer.android.com/reference/android/os/PowerManager#isInteractive())。AOD、多屏及其他 ROM 仍需设备测试。

## 兜底何时运行

- 仅交互状态、配置有效、恰好一个已连接 Wi-Fi 时，IDLE 最长每 7 秒读一次现有连接信息。
- 有效信号/网络事件经防抖提前读取，并重置兜底，避免同一时刻重复采样。
- VERIFY 每秒验证，到期再读取一次新鲜 RSSI；没有广播时也能在最迟约 7 秒发现弱信号后启动完整 7 秒验证。实际时间还受调度、Binder 延迟影响，不承诺硬实时。
- 息屏、无 Wi-Fi、多 Wi-Fi、配置错误、进程停止时取消；冷却期仅保留到期任务，忽略一般信号事件。
- WebUI 只在用户点击刷新/修改设置时请求快照，没有页面自动轮询。

这保留了弱信号检测能力，强信号且一直亮屏时不会减少正常兜底读数。纯事件模式目前缺乏可靠保障，不能为减少读取而删除兜底。

## 功耗指标与边界

确定性模拟中，假设 Wi-Fi 连续连接一小时、前 15 分钟亮屏、其余 45 分钟息屏且无额外事件：旧固定机制读取 515 次，新机制 129 次，减少约 74.95%。这是业务读取次数模型，**不是实测电量下降或硬件唤醒下降比例**。一直亮屏且强信号的周期读取基本不变；事件频繁时可增加读数，受合并/限频限制；核心模式验证阶段由 7 秒变为 1 秒确认，是为及时取消误判付出的短期成本。

Handler.postDelayed 在没有 WakeLock 时本来就不会自行唤醒深睡 CPU，因此不能把旧任务次数直接称为深睡唤醒次数。新版本减少息屏业务、文件访问、重复能力回调、日志和通知子进程重建；PM/最终复核也有 Binder 成本。诊断提供 RSSI 事件、Binder 读取、周期/事件任务、倒计时、断开、弱 AP 重连、能力事件过滤、流体云及 CPU ticks。USB/ADB 短窗口不能用于计算 mAh 或全天耗电收益。

## 保留的行为和 WebUI

保留 `/data/adb/coloros_wifi_sense/threshold`、阈值范围 -100..-20、原 action.sh 阈值切换、安装音量键选择、核心/流体云模式。弱信号恢复、BSSID 改变会取消验证；开关默认开启，关闭立即请求断开；结果收起并保留 15 秒。流体云启动/退出有 token 隔离和有界清理，核心模式不创建渲染子进程。

`webroot/` 通过 [KernelSU 官方 WebUI bridge](https://kernelsu.org/guide/module-webui.html) 调用固定 `webctl.sh`，提供阈值、模式和手动诊断。无 CDN、外部网络、HTTP daemon；参数双重验证，状态文件权限 0600，不输出 SSID/BSSID。仅核心安装移除了流体云 DEX，面板会提示需重新安装以添加。普通浏览器显示缺少管理器环境；预览截图使用单独测试快照，不在发布包注入 mock。

断开同一个弱 AP 后的自动重连由 Android 控制。模块保留 60 秒冷却并计数弱 AP 重连，避免连续切断；没有永久关闭 Wi-Fi、修改保存网络或宣称已经禁止系统自动重连。是否需要临时抑制重连，必须结合实际系统 API 与设备测试再判断。

## 测试矩阵

| 层级 | 已完成结果 | 能证明的范围 |
|---|---|---|
| 纯 Java 策略 | WifiDisconnectPolicyTest 全部通过 | 阈值、倒计时、恢复、BSSID、冷却、显式提交 |
| 纯 Java 控制器 | 28 / 28 | 用户要求的 12 场景及竞态、配置错误、任务/读取计数 |
| WebUI 自动化 | 8 / 8 | bridge、预设、输入校验、模式、错误展示、无 bridge |
| Android shell 私有目录 | 20 / 20 | 阈值/nonce/模式校验、600 权限、缺 DEX、守护进程未启动 |
| 本机生命周期 | 通过 | 真屏幕事件、Binder/Handler/FileObserver；RSSI 模拟，实际断开被拦截 |
| 本机配置/最终闸门 | 通过 | 非法配置静默、修复恢复、保护后的断开尝试被拦截 |
| 本机广播探针 | 完成，结果为不支持该注册方式 | 不把返回 null 误认成成功 |
| 本机流体云进程 | 2 / 2 | 息屏退出，结果约 15.062 秒后退出，exit 0 |
| 安装修复隔离回归（此前） | 621 / 621；探针 12 / 12 | 固定 9 安装器 × 3 shell × 23 故障场景；非当前 v1.2.0 全包重跑 |

控制器前 12 项逐一覆盖：正常信号、逐渐下降、突然下降、验证中恢复、验证中息屏、息屏事件、弱信号重新亮屏、BSSID 改变、断开重连、原弱 AP 立即重连、无 Wi-Fi、只有移动数据。额外覆盖读取/最终闸门的息屏与事件竞态、阈值变化、非法连接信息、仅核心零 UI、事件合并、迟到确认、冷却唤醒、退出和读取次数对比。

成功的实机息屏窗口：RSSI 读取 8 → 8，周期任务 3 → 3，主动断开 0；唤醒后弱信号开始新倒计时，快照余量 6161 ms。手动断开保护测试记录 attempts=1、accepted=0，测试代理明确阻止真实无线电调用。非法配置窗口读取数保持不变。未通过/人工打断的先前窗口不计为通过。

编译入口是 `build.ps1`（Java 8 字节码、Android SDK 36、D8 min-api 29），主测试入口 `test.ps1`。实机测试说明见 [test/android/README.md](../../test/android/README.md)，安装故障根因/注入边界见 [安装报告](../installation-failure/REPORT.md)。最终编译/包校验日志和精简实机证据随本目录 evidence 保存。

最终编译成功（仅有现有弃用 API 提示）；`python test/package-check.py` 通过 19 个 ZIP 条目的路径/大小限制、UTF-8/LF、源文件一致性、元数据、脚本执行位、生产 DEX 隔离、无测试 payload、旧入口脚本未变等检查。ZIP 69,062 字节，SHA-256 `15f3b5abdefa17b844f948cc9b8b32112710f8117ef2767a65c48dd8f223630e`。这些检查不等于 KernelSU 服务端已通过预检。

## 尚需真实设备验证

1. 实际走出覆盖区域、持续弱 AP 的断开成功率、延迟、移动数据接管和原 AP 自动重连行为。
2. 跨 BSSID 漫游、双 Wi-Fi、VPN、不同 ColorOS/API/KernelSU/Magisk；公开 DisplayManager 后备在其他 ROM 的 AOD/多屏表现。
3. 新版真实 KernelSU/Magisk 安装/升级、KernelSU WebView bridge 与音量键；此前隔离安装回归不等于真实管理器安装。
4. 最新完整守护进程与用户点击原生流体云开关的端到端测试。此次测试保留正式旧模块与阈值，未替换正式安装，也没有真实切断 Wi-Fi。
5. 无 USB/ADB 干扰的长时间 CPU/系统唤醒和电量对照；目前不能量化真实省电百分比。

## 临时文件清理

测试使用的便携 QEMU、Linux 下载/镜像、辅助解压依赖及旧反编译临时缓存已清理。清理记录合计 1,151,296,404 字节，约 1.07 GiB；保留源码、压缩后的诊断证据和小型测试日志。未删除用户 SDK、JDK、JADX，也未安装新的后台虚拟机服务。Android 私有测试夹清理状态单列在交付说明，正式模块与配置保持原状。

最终清理时 ADB 设备列表为空，未能删除手机 `/data/local/tmp/cws-event-test` 和独立探针 DEX；它们是私有测试文件，不是后台服务，测试进程此前已正常退出。重新连接后可按测试说明删除。主机错误版本号的中间 v1.1.1 构建包已删除，避免与早先 Downloads 中的安装修复候选混淆。
