# 双 WLAN 连接识别修复（v1.6.2）

## 原因

2026-11-20 在已连接的 OnePlus PLK110 / ColorOS 17 真机复现：系统同时提供两个 Wi-Fi Network，原模块所有采样、倒计时和通网检测入口要求连接数恰好为 1，因而返回 `NO_WIFI`，RSSI 为 null。WebUI 也使用相同的数量判断。

不能直接移除数量限制后任取 Network。`getConnectionInfo("root")` 读取主连接，HTTP 探测却绑定 Network；两者配错会检测副路、断开主路。参考 [AOSP WifiServiceImpl](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android16-release/service/java/com/android/server/wifi/WifiServiceImpl.java)：`getConnectionInfo` 和 `disconnect` 的此类调用使用主 ClientModeManager。

## 修改

- 从 NetworkCapabilities 的 WifiInfo 缓存连接身份与 `isPrimary()` 角色，优先选择唯一主连接。旧系统没有该标志时使用明确的 BSSID 匹配；单连接保留原有兼容路径。信息冲突或无法区分时跳过断开。
- 真机网络回调的两个 BSSID 均被隐藏为 `02:00:00:00:00:00`，但主副角色正确，因此支持按角色匹配。
- 定时采样复用缓存，不增加每轮 NetworkCapabilities 查询。实际断开、有限次数的 HTTP 探测前后重新读取连接与角色；两次根连接读取间身份变化则丢弃操作。
- 副连接出现或丢失不重置主连接倒计时，也不重启主连接已完成的通网检测；主网络切换时取消在途探测，使用新一轮判断。
- WebUI 双 WLAN 下显示主连接 RSSI 和「正在检测主 Wi-Fi」。阈值、暂停、息屏选项、倒计时、流体云和暂避配置均沿用。

## 验证

执行 `./test.ps1`：132 项检查通过（54 个监控场景、15 个连接选择场景、14 个频段事务、21 个连接策略、27 个 WebUI 场景和原断开策略断言组）。执行 `./build.ps1` 编译 Java/DEX 和四架构原生组件，`python -X utf8 test/package-check.py` 检查 ZIP、源码一致性与测试代码隔离，均通过。

真机测试：

1. 修复前：两个 WLAN，亮屏时 `NO_WIFI` / RSSI null。
2. 修复后：两个 WLAN，`IDLE`，主连接 RSSI 约 −34 至 −38 dBm；所选 Network 与系统主角色一致。通网请求得到 HTTP 204，并在成功后停止。
3. 暂时将阈值设为 −20 dBm、倒计时设为 3 秒，以现有强信号触发真实断开路径；暂避设为 12 秒。系统接受倒计时断开，原主 Network 消失，第一次断开后快照中副 Network 仍存在；暂避到期后还原。
4. 原阈值、倒计时与连接策略配置已恢复，主 Wi-Fi 自动重新连接，RSSI 与监控恢复。此后系统也曾撤销副连接，不能据此宣称副连接会一直保持。

这是修改阈值后执行的真实断开测试，不是实际走入弱覆盖区域的无线环境测试。尚需验证其他 ROM、漫游时的主副角色切换，以及副路独立控制。当前修复只管理主 Wi-Fi；不声称可以独立断开副 Wi-Fi，也未增加副路状态显示或读取 SSID 的常驻 shell 轮询。
