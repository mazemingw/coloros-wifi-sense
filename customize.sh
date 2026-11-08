#!/system/bin/sh

# This file is sourced into the installer's shell. Never change its TMPDIR,
# MODPATH, BOOTMODE, umask or working directory in the parent shell.
STATE_DIR=/data/adb/coloros_wifi_sense

cws_install_failure() {
    ui_print "! $1 (exit=$2)"
    ui_print "! 配置路径：$STATE_DIR/threshold"
    ui_print "! MODPATH=${MODPATH-<unset>} TMPDIR=${TMPDIR-<unset>}"
    ui_print "! API=${API-<unset>} BOOTMODE=${BOOTMODE-<unset>} KSU=${KSU-<unset>}"
    ui_print "! 身份：$(id 2>&1)"
    ui_print "! 工作目录：$(pwd 2>&1)"
    [ -z "$3" ] || ui_print "! 系统错误：$3"
    ui_print "! 路径状态：$(ls -ld /data/adb "$STATE_DIR" "$STATE_DIR/threshold" 2>&1)"
    abort "安装已停止；请保存完整日志，已有阈值配置不会被删除。"
}

# abort() recursively removes MODPATH and TMPDIR. With malformed cleanup paths,
# exit directly rather than letting the installer delete an unrelated directory.
case "${MODPATH-}" in
    /*) [ "$MODPATH" != / ] && [ -d "$MODPATH" ] && [ -f "$MODPATH/module.prop" ] || {
        ui_print "! 安装器 MODPATH 无效：${MODPATH-<unset>}；未写入任何配置。"
        exit 1
    } ;;
    *) ui_print "! 安装器 MODPATH 必须是有效绝对路径：${MODPATH-<unset>}"; exit 1 ;;
esac
case "${TMPDIR-}" in
    /*) [ "$TMPDIR" != / ] && [ -d "$TMPDIR" ] || {
        ui_print "! 安装器 TMPDIR 无效：${TMPDIR-<unset>}；未写入任何配置。"
        exit 1
    } ;;
    *) ui_print "! 安装器 TMPDIR 必须是有效绝对路径：${TMPDIR-<unset>}"; exit 1 ;;
esac
case "${API-}" in
    ''|*[!0-9]*) cws_install_failure "安装器未提供有效的 Android API" 1 "API=${API-<unset>}" ;;
esac
[ "$API" -ge 29 ] || abort "colorOSWIFI感知需要 Android 10 或更新版本"

module_version=$(sed -n 's/^version=//p' "$MODPATH/module.prop")
ui_print "欢迎使用ColorOS WIFI感知模块！"
ui_print "====="
ui_print "本模块的功能是处理弱信号不会自动断开WIFI的问题"
ui_print "WIFI未连接时不会检测，降低耗电"
ui_print "作者：酷安@NVMEX夜莺"
ui_print "版本：$module_version"
ui_print "====="
ui_print "模块功能选择：按音量上下键选择"
ui_print "音量+：开启核心及流体云通知（推荐）"
ui_print "音量-：仅开启核心"

command -v getevent >/dev/null 2>&1 || abort "无法读取音量按键，请在开机后的 Magisk/KernelSU 管理器中安装"
while :; do
    key_event=$(getevent -qlc 1 2>/dev/null) || abort "读取音量按键失败"
    case "$key_event" in
        *KEY_VOLUMEUP*DOWN*|*KEY_VOLUMEUP*00000001*|*0001*0073*00000001*)
            printf '1\n' > "$MODPATH/fluid_enabled" || abort "无法保存功能选择"
            ui_print "已选择：核心及流体云通知"
            break ;;
        *KEY_VOLUMEDOWN*DOWN*|*KEY_VOLUMEDOWN*00000001*|*0001*0072*00000001*)
            printf '0\n' > "$MODPATH/fluid_enabled" || abort "无法保存功能选择"
            rm -f "$MODPATH/wifi-fluid.dex"
            ui_print "已选择：仅开启核心"
            break ;;
    esac
done

if [ ! -d "$STATE_DIR" ]; then
    # BusyBox mkdir -p /absolute/path visits '/' even if the parent exists.
    # Only this leaf is missing. Keep cd and umask inside a subshell and avoid
    # traversing '/' through recursive mkdir; do not chmod any system directory.
    cws_error=$( (umask 077; cd /data/adb && mkdir -p coloros_wifi_sense) 2>&1) ||
        cws_install_failure "无法创建阈值配置目录" "$?" "$cws_error"
fi
if [ ! -f "$STATE_DIR/threshold" ]; then
    cws_error=$( (umask 077; printf '%s\n' -80 > "$STATE_DIR/threshold") 2>&1) ||
        cws_install_failure "无法保存默认阈值" "$?" "$cws_error"
fi
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/settings.sh" 0 0 0755
set_perm "$MODPATH/demo.sh" 0 0 0755
set_perm "$MODPATH/webctl.sh" 0 0 0755
set_perm "$MODPATH/fluid_enabled" 0 0 0600
ui_print "安装完成，重启后开始监听 Wi-Fi。"
