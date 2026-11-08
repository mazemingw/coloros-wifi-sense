# 安装失败隔离复现

本工具启动一次性的 Linux 内存虚拟机，运行锁定版本的上游安装脚本和本模块安装脚本。虚拟机无磁盘、无网络、无宿主目录共享。每个案例再使用独立 chroot；不会操作宿主真实 `/data/adb`。`guest.sh` 检查虚拟机专用启动标记，禁止直接在宿主运行。

需要 Python 3.12+、Git（包含本项目 `v1.1.0` tag）、QEMU system x86_64。下载的上游安装器和 Linux 依赖以 commit / SHA-256 固定，首次运行需要联网，后续使用 `build/installer-lab/cache/` 缓存。所有测试结果位于 `build/installer-lab/`。

Linux 可通过发行版安装 `qemu-system-x86`；Windows 可传入便携 QEMU 的完整路径。本次实际使用 `build/installer-lab/cache/qemu/qemu-system-x86_64.exe`，没有开启或修改 Windows 虚拟化功能。

```sh
git fetch origin tag v1.1.0
python -X utf8 test/installer/run.py --phase before --timeout 1200
python -X utf8 test/installer/analyze.py --phase before --labels before
python -X utf8 test/installer/run.py --phase after --timeout 1200
python -X utf8 test/installer/analyze.py --phase after --labels after
```

`before` 从不可变的 `v1.1.0` tag 读取原始 `customize.sh` 和经过 SHA-256 校验的发布 ZIP。现在的 `after` 默认使用 `module.prop` 所指版本的完整 `build/coloros-wifi-sense-VERSION.zip`，也可通过 `--module-zip PATH` 指定；必须先编译，脚本会检查 ZIP 内安装脚本与工作区一致，并记录候选 ZIP 哈希。这样新增 WebUI 文件及权限设置也会进入测试负载。

默认测试 9 个安装器配置 × 3 种 shell × 23 个案例。负面案例通过表示正确停止、保留旧配置/模块，并不表示安装成功。`analyze.py` 对返回值、阈值、音量键选项、DEX、更新标记、旧模块哨兵、清理及新配置权限进行断言；修复前已知缺陷会单独计为失败，不包装成通过。

报告中已完成的 621 案例属于此前安装修复候选：当时以同一个 v1.1.0 负载替换安装脚本控制变量，并单独验证候选包元数据。它不是 v1.2.0 完整负载重新运行的结果。测试虚拟机及下载缓存已经清理，报告与日志保留；需要再次运行时需自行提供 QEMU。

`--smoke` 只检查第一个安装器的正常安装及权限拒绝；`--profiles fina,magisk` 可选择配置；`--label after-a` 将结果写到独立目录，便于并行批次。完整单次执行在软件模拟 TCG 下较慢。每个案例有 20 秒上限，整个虚拟机超时由 `--timeout` 指定。

本次实际回归分三组运行，分别为：

| 批次 | profiles |
|---|---|
| a | kernelsu,kernelsu-v1.0.5,next |
| b | next-v1.0.9-magic,next-v1.0.9-overlayfs,sukisu |
| c | fina,fina-v1.3.0,magisk |

例如：

```sh
python -X utf8 test/installer/run.py --phase after --label after-a --profiles kernelsu,kernelsu-v1.0.5,next
python -X utf8 test/installer/run.py --phase after --label after-b --profiles next-v1.0.9-magic,next-v1.0.9-overlayfs,sukisu
python -X utf8 test/installer/run.py --phase after --label after-c --profiles fina,fina-v1.3.0,magisk
python -X utf8 test/installer/analyze.py --phase after --labels after-a,after-b,after-c
```

额外探针保留分流日志、合并的真实执行顺序、strace 系统调用记录，以及父 shell 上下文对照：

```sh
python -X utf8 test/installer/run.py --phase before --probe --label before-contracts
python -X utf8 test/installer/run.py --phase after --probe --label after-contracts
python -X utf8 test/installer/analyze_probes.py
python -X utf8 test/installer/summarize.py
```

这里的 `EINVAL` 是 strace 只针对 mkdir 子进程的系统调用故障注入，记录明确标注 `(INJECTED)`；没有伪造 BusyBox 的报错字符串。`before-modeled-finasu-ui.log` 用实际捕获的两路输出，按已审查的 FinaSU 界面规则重排，并补上已知 ksud/管理器错误模板。它不是运行完整 Android 管理器得到的日志。

`mount_partitions`、Android `getprop`、音量键 `getevent`、SELinux `chcon` 是可审查的 mock。Linux 内核真实执行文件权限、目录创建、重定向及清理；权限拒绝案例主动降到 UID 1000 注入故障，不代表对用户 Root 状态的判断。Magisk 的 `ensure_bb` 被替换为空操作，以保留 ash / dash 的对比，否则它会主动重启成 BusyBox ash。KSU shell 使用官方仓库中的 BusyBox v1.36.1.1 和 `ASH_STANDALONE=1`。

未执行完整 ksud Rust 外壳、Android 挂载/SELinux 策略、旧版 KernelSU 的模块镜像事务或管理器 APK。首次安装的 shell-only 探针在持续根目录故障下缺少元数据，当前 FinaSU Rust 后处理会尝试补齐；不能把该探针直接等同于完整管理器的最终结果。

详细结论与边界见 [定位报告](../../docs/installation-failure/REPORT.md)。
