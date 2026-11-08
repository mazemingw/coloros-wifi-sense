# v1.1.0 安装失败复现与定位

日期：2026-11-08。审计对象：`mazemingw/coloros-wifi-sense`，基线 commit `ae23d4f9893dd470f91f8de6725476b8c241847b` / tag `v1.1.0`。修复候选为本地 `v1.1.1`，未发布新 GitHub Release。

后续交付说明：下文记录安装器修复当时的实验，修复现已并入 v1.2.0；新的运行期代码和 WebUI 验证另见 [低功耗报告](../low-power/REPORT.md)。621 案例没有以 v1.2.0 全包重新执行，不应将本文的“运行期未修改”理解为后续版本没有变化。

## 结论与证据等级

**已通过系统调用故障注入，复现截图中的关键报错文字和顺序；尚未确认真实设备返回 EINVAL 的底层原因。** 没有使用用户设备、ADB、临时 Root、SELinux 状态或用户操作作为前提。

1. BusyBox 的递归目录创建会逐级处理绝对路径，包括 `/`。使用 KernelSU 仓库自带的 BusyBox，向 mkdir 子进程第一次 `mkdirat` 注入 `EINVAL`，实际得到 `mkdir: can't create directory '/': Invalid argument`，而不是人为打印这个错误字符串。原始调用目标分别是安装器的 `/dev/tmp`、模块暂存目录，以及模块的阈值目录；**日志里的 `/` 不证明这些变量被改成了根目录。** [BusyBox 实现参考](https://github.com/mirror/busybox/blob/1_36_stable/libbb/make_directory.c)
2. 安装器最初的暂存目录创建、标记和 `cd` 没有检查失败便继续。`/dev/tmp` 未创建后，先出现 `chcon`、`cd` 错误；之后 `unzip -d` 自身能够创建目的目录，所以仍能解压、读取音量键，再在模块的绝对路径 `mkdir -p` 处失败。这条链在 Linux 虚拟机中真实执行，见[时间顺序日志](evidence/before-chronological.log)与[系统调用记录](evidence/before-injected-strace-mkdir.log)。[安装器源码](https://github.com/tiann/KernelSU/blob/730f644615969cfd4a84b45d7f62a667b7b95a50/userspace/ksud/src/installer.sh#L357)
3. FinaSU 的模块安装界面默认不实时显示 stderr，失败后将 stderr 汇总追加到 `Error code` 后。截图中的 `Archive` 因而可以是早先的解压输出，**不能据此认定 abort 后重试安装**。同一次 shell 运行只有三次预期的 unzip 调用：模块信息、自定义脚本、完整内容；未观察到 abort 后继续执行安装。[界面规则](https://github.com/LyraVoid/FinaSU/blob/fbf8e003d902c43213d435632a22e4fb1a219aeb/manager/app/src/main/java/me/weishu/kernelsu/ui/screen/flash/FlashUtils.kt#L254)
4. 截图缓存路径中的 `me.yuki.fina` 与 FinaSU 的默认包名一致，因而额外审计了该分支；这只能确定一个有依据的候选，不能确定截图所用版本或是否为自定义构建。[包名定义](https://github.com/LyraVoid/FinaSU/blob/fbf8e003d902c43213d435632a22e4fb1a219aeb/manager/app/build.gradle.kts#L28)

| 实验类型 | 观察 | 能否确认用户设备根因 |
|---|---|---|
| 正常 Linux 文件系统，无注入 | 三种 shell 均可正常安装 | 否，仅证明正常路径可运行 |
| 原生权限拒绝、文件占位、阈值目标为目录 | 分别出现 Permission denied、File exists、重定向失败；只能复现部分报错 | 否；普通权限拒绝没有自然产生截图的 `/` + EINVAL |
| strace 注入 mkdirat 的 EINVAL | 实际 BusyBox、实际上游脚本产生截图关键错误，包括 `sh: cd: line 360` | 条件性故障链复现，不是底层原因确认 |
| 按 FinaSU 规则组合两路日志 | 形成“选择成功→目录失败→Error code→mkdir/chcon/cd→解压日志”的显示顺序 | UI 行为模型；未运行 Android 管理器 APK |

见[界面日志模型](evidence/before-modeled-finasu-ui.log)。其中 BusyBox、unzip、cd 与模块文字来自真实捕获；外层 ksud 错误和 `Error code` 模板由重放程序补充。测试 ZIP 路径统一为 `/module.zip`，与用户缓存文件名不同，因此不称为“用户完整日志的逐字精确复现”。

## 源码、变量与 abort 审计

`customize.sh` 由安装器 source 到同一 shell 中，不能像独立程序一样假定变量、工作目录互相隔离。已审查的原始模块脚本没有写入 `TMPDIR`、`MODPATH`、`BOOTMODE` 或 `KSU`，也没有父 shell `cd`、`trap`、`eval`。`STATE_DIR` 固定为 `/data/adb/coloros_wifi_sense`，未发现与本次上游脚本同名的安装器状态变量。

| 变量 | 上游来源与传递 | 本次判断 |
|---|---|---|
| TMPDIR | KernelSU 系列与 Magisk 的 presets 将它设为 `/dev/tmp`，覆盖同名继承环境 | 继承空值或无效路径本身不会保留到模块；目录创建失败仍会留下有效字符串、无效实际目录 |
| MODPATH | 读取 module.prop 的 ID 后，依据 BOOTMODE 选择 modules_update 或 modules，再拼接模块 ID | 正常开机安装为 `/data/adb/modules_update/coloros_wifi_sense`；不是从截图目录错误反推为空 |
| BOOTMODE | 非空继承值保留；为空时以 zygote 进程检测初始化为 true/false | 影响输出方式、安装目录与 cleanup；模块不覆写它。虚拟机正常用例明确设置 true |
| KSU | ksud 为安装子进程传入 `KSU=true`，并开启 `ASH_STANDALONE=1` | 模块不依赖它决定是否初始化阈值；空 KSU 的正常路径也有覆盖 |
| API | 安装器读取系统属性；Linux 中由 getprop mock 提供 | 原脚本对空值/非数字报 shell 数字比较错误；修复后明确诊断 |

[KernelSU Rust 调用层](https://github.com/tiann/KernelSU/blob/730f644615969cfd4a84b45d7f62a667b7b95a50/userspace/ksud/src/module.rs#L62)、[Magisk 工具脚本](https://github.com/topjohnwu/Magisk/blob/33bfff7ce750c27f157ddb4580e36bf842d6d95b/scripts/util_functions.sh)。

已审计实现的 `abort` 打印消息、按需要执行恢复环境 cleanup、删除 MODPATH 和 TMPDIR，随后退出当前 shell。生产脚本的 `|| abort` 位于执行目录创建的子 shell **外面**，因此会终止安装器；没有看到改写上述变量或自动重新调用安装的逻辑。

额外控制实验验证：

- source 成功前后，TMPDIR、MODPATH、BOOTMODE、KSU、API、物理工作目录和 umask 完全一致，见[上下文对照](evidence/after-caller-context.txt)。
- 中途 abort 后未执行后续标记，暂存目录已清理。
- 人为把 abort 放在子 shell 中，只退出该子 shell，父 shell 继续，但共享的暂存目录仍被删除。这个控制实验说明“进程环境隔离”和“文件删除影响”有区别；生产脚本没有这种放置方式。

异常 MODPATH/TMPDIR 的检查直接退出，不调用会使用不可信清理路径的上游 abort。这只针对非法环境；正常失败仍走标准 cleanup。

## 上游版本固定与差异

完整 commit、文件路径和 SHA-256 在 [upstreams.json](../../test/installer/upstreams.json)，运行时从各项目官方仓库下载、校验；不把第三方完整源码或二进制提交到模块仓库。

| 测试配置 | 固定版本/commit 前缀 | 观察范围 |
|---|---|---|
| kernelsu | main `730f64461596` | 当前脚本与 Rust 调用/收尾 |
| kernelsu-v1.0.5 | v1.0.5 `61c0f7f849aa` | 历史脚本；模块镜像事务仅审计，未模拟挂载 |
| next | dev `eed26da24665` | 当前 Next 脚本与 Rust 调用 |
| next-v1.0.9-magic | v1.0.9 `2241696498ce` | 历史 Magic Mount 安装脚本 |
| next-v1.0.9-overlayfs | 同上 | 历史 OverlayFS 安装脚本 |
| sukisu | main `c49129a4866c` | 当前 Ultra 脚本与 Rust 调用 |
| fina | master `fbf8e003d902` | 截图包名对应候选；含界面输出规则 |
| fina-v1.3.0 | v1.3.0 `9a57ccb05003` | 发行 tag 的安装脚本 |
| magisk | master `33bfff7ce750` | util_functions 安装分支 |

当前 KernelSU、SukiSU 与 FinaSU 安装脚本内容相同；仍分别记录配置，不能把这部分结果看作九套独立实现。历史脚本的代码行号、挂载路径和外围事务不同；共同的暂存目录与 abort 模式有复现覆盖。测试没有声称覆盖全部历史版本。

## ZIP 与打包审计

- module.prop 位于 ZIP 根目录，ID 为 `coloros_wifi_sense`；UTF-8、无 BOM，打包统一 LF。没有 install.sh 触发旧式安装分支，也没有额外父目录或自带修改上游的安装器。
- `build.ps1` 直接打包两份 DEX、shell、源文件、README 和许可证；没有把宿主 TMPDIR 或 MODPATH 写入 ZIP。目录项为相对路径，无 `..` 或反斜杠。
- v1.1.0 发布包 SHA-256：`245f924bf0476430c242a9725a01e7ada81fb0c1812e32e2000cdc65197c0b54`。复现使用该负载与 tag 中的原始安装脚本。
- 本次 Java 核心、流体云实现、service/action/settings/demo 均未修改；修复包另用 v1.1.1 / versionCode 3，避免替换已发布的不可变 v1.1.0。

## 最小修复

原脚本即使目录存在也执行一次绝对路径 `mkdir -p`。在已复现的 BusyBox 父目录错误条件下，这个不必要的 `/` 访问仍会令升级安装失败。

修复仅在目录不存在时，在子 shell 内进入现有父目录，使用相对路径创建叶目录：

```sh
if [ ! -d "$STATE_DIR" ]; then
    cws_error=$( (umask 077; cd /data/adb && mkdir -p coloros_wifi_sense) 2>&1) ||
        cws_install_failure "无法创建阈值配置目录" "$?" "$cws_error"
fi
```

同时保留默认阈值仅在文件不存在时写入的行为；把目录/文件写入错误与退出状态捕获到主日志，显示配置路径、调用身份、工作目录及安装器变量。没有更改 SELinux、系统目录权限、配置位置、原有阈值、音量键逻辑或操作按钮。新目录仍为 0700，新默认文件仍为 0600；已有只读阈值不被覆盖。

针对非法环境的额外修复：先拒绝空、相对、根路径或无模块信息的 MODPATH，以及无效 TMPDIR；API 先检查数字格式。原脚本在直接 source 到异常 MODPATH 的控制实验中会向 `/fluid_enabled` 等错误位置写入；这是独立复现的健壮性缺陷，**不是对截图变量状态的推断**。

修复代码见 [customize.sh](../../customize.sh)。

## 测试矩阵与结果

完整结果由 `test/installer/analyze.py` 生成，见 [修复前矩阵](matrix-before.tsv)、[修复后矩阵](matrix-after.tsv) 和 [结果汇总](SUMMARY.md)。修复前功能断言 486/621（78.26%），修复后 621/621（100%）；另有前后合计 12/12 个系统调用与 shell 契约探针。全部属于隔离模拟与故障注入结果，不是 621 台实机验证。

断言不只判断退出码，还检查保留的阈值、功能选择、流体云 DEX 的保留/移除、暂存目录、更新标记、旧模块哨兵及新建配置权限。负面案例“通过”表示正确拒绝并诊断，不表示成功安装。修复前的已知问题按失败保留，不将“复现成功”改计为产品通过。

环境：QEMU 11.1.0 / TCG，无磁盘、无 NIC、无宿主目录共享；Alpine Linux 6.18.60，384 MiB 内存；每案例另建 chroot。三种 shell：Alpine BusyBox 1.37.0 ash、KernelSU 自带 BusyBox 1.36.1.1 ash（standalone）、dash 0.5.13.1。

权限失败用 UID 1000 与只读目录主动注入；目录冲突与重定向错误由真实 Linux 文件系统产生。Android 挂载、属性、音量键与 chcon 使用 mock；Magisk ensure_bb 在 shell 比较中不重启进程。全部注入和替换均在 [hooks.sh](../../test/installer/hooks.sh) 可见。

## 尚需真实 Android 验证

1. **为什么真实设备的根路径目录操作返回 EINVAL。** 当前只证明此条件能解释日志；正常 Linux 与普通权限拒绝没有自然重现它。没有证据将原因归给临时 Root、SELinux、特定内核功能或用户操作。
2. 用户实际管理器、ksud、BusyBox 构建及是否启用自定义 metamodule 安装器。当前源码只是候选；ksud 可以用 metainstall.sh 替换默认安装流程，不能仅凭一张图排除。[调用选择逻辑](https://github.com/tiann/KernelSU/blob/730f644615969cfd4a84b45d7f62a667b7b95a50/userspace/ksud/src/metamodule.rs)
3. 完整 ksud 事务、SELinux 标签、重启加载，以及真实音量键和流体云显示。此次安装器修复没有改变运行期代码，但这些没有被 Linux mock 测试代替。
4. 持续 EINVAL 下，安装器自身最初的 mkdir/chcon/cd 仍报错。新安装的 shell-only 探针返回 0 时，更新元数据尚未建立，见 [stderr](evidence/after-fresh-shell-only.stderr.log)；**这不是已确认的管理器最终失败**：当前 FinaSU Rust 会在脚本返回后再次创建模块目录、复制 module.prop 并建立 update 文件，而历史 KernelSU 还有模块镜像事务。测试未执行这些外围代码，因此不能声称端到端完全修好，也不能把 shell 阶段缺文件直接判作真实安装失败。[FinaSU 收尾](https://github.com/LyraVoid/FinaSU/blob/fbf8e003d902c43213d435632a22e4fb1a219aeb/userspace/ksud/src/module.rs#L851)

实机若仍复现，应记录原始两路日志及管理器/ksud/BusyBox 版本，对实际 mkdir/mkdirat 的参数、errno 和命名空间做追踪；同时读取相关路径类型/标签、可写性和可用空间。只有观察到真实系统调用结果，才能继续确定底层原因。本次没有为了收集这些信息而修改任何手机或宿主系统目录。

## 复现入口

[脚本与运行说明](../../test/installer/README.md)。`before` 固定原始 tag，`after` 使用工作区修复；上游与环境以 commit / 校验和固定。完整原始日志、VM 命令和运行来源信息存放于本地 `build/installer-lab/`；本文附带的日志为选取的最小证据。
