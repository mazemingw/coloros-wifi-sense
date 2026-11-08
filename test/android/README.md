# Android 集成检查

这些测试使用 ADB 和 root，但只写入 `/data/local/tmp/cws-event-test`，不安装模块、不修改 `/data/adb`。测试 DEX 与发布 DEX 分开编译，发布包不包含测试入口。需要 Python 3、JDK 17、Android SDK 36、已授权的 root ADB；脚本顶部的 ADB 路径可按环境调整。

```powershell
.\build.ps1 -AndroidSdk D:\Android\SDK -Jdk 'C:\Program Files\Java\jdk-17'
.\test\android\build-harness.ps1
python -X utf8 test/android/run-device.py lifecycle
python -X utf8 test/android/run-device.py config
```

`lifecycle` 会真实熄屏/唤醒。请保持手机可唤醒，避免人工操作干扰采样窗口。它通过测试代理修改返回的 RSSI；所有主动断开调用被拦截并返回 false，因此不会实际断开无线电。断言覆盖真实 Binder 读取、FileObserver、Handler、屏幕回调、配置更新、息屏零业务采样和重新亮屏开始新倒计时。`config` 验证非法配置进入静默状态、修复后恢复及最终断开前检查。

运行器拒绝覆盖已运行的同名测试进程，等待新进程 PID 对应的状态文件，退出时只停止本次测试进程。结果保存在 `build/power-audit/`，支持检查失败日志；一次失败或被人工唤醒打断的窗口不算通过。

附加工具：

- `WifiPlatformProbe.java` / `broadcast-check.py`：针对独立 root 的动态广播注册探针；必须先独立编译、启动该探针，再运行客户端，不能把返回 null 当作注册成功。
- `shell-check.sh`：将模块脚本中的固定状态路径改为测试目录后，在 Android shell 运行阈值、模式及参数校验；不可直接指向正式状态目录。
- `fluid-check.py`：要求测试目录已有发布版 `wifi-fluid.dex`，测试实际流体云渲染进程的息屏清理和 15 秒结果寿命。此测试会显示原生通知，但不修改 Wi-Fi。

手动清理前确认没有 `cws-device-harness` 或平台探针进程；只删除上述私有测试目录及 `/data/local/tmp/wifi-platform-probe.dex`。不要删除正式模块或已有阈值。

这些工具不能代替真实弱 AP、跨 BSSID 漫游、无线电实际断开/重连、KernelSU WebView、长期电量测试。详细结果见 [审查与验证报告](../../docs/low-power/REPORT.md)。
