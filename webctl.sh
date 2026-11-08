#!/system/bin/sh
# On-demand KernelSU WebUI bridge; no HTTP listener or resident helper.
MODDIR=${0%/*}
STATE_DIR=/data/adb/coloros_wifi_sense
case "$1" in
    threshold)
        [ "$#" -eq 2 ] || exit 1
        exec sh "$MODDIR/settings.sh" "$2" ;;
    fluid)
        [ "$#" -eq 2 ] || exit 1
        case "$2" in 0|1) ;; *) exit 1 ;; esac
        if [ "$2" = 1 ] && [ ! -f "$MODDIR/wifi-fluid.dex" ]; then
            echo '当前安装未包含流体云组件，请重新安装并选择流体云通知。' >&2
            exit 1
        fi
        umask 077
        cws_tmp=$(mktemp "$MODDIR/fluid_enabled.XXXXXX") || exit 1
        trap 'rm -f "$cws_tmp"' EXIT
        printf '%s\n' "$2" > "$cws_tmp" && mv -f "$cws_tmp" "$MODDIR/fluid_enabled" || exit 1
        echo '通知设置已保存，开启后从下一次提醒生效。' ;;
    status)
        [ "$#" -eq 2 ] || exit 1
        case "$2" in ''|*[!a-zA-Z0-9_-]*) exit 1 ;; esac
        [ "${#2}" -le 80 ] || exit 1
        [ -d "$STATE_DIR" ] || { echo '配置目录不存在，请确认模块已安装。' >&2; exit 1; }
        umask 077
        printf '%s\n' "$2" > "$STATE_DIR/ui_request" || exit 1
        cws_wait=0
        while [ "$cws_wait" -lt 20 ]; do
            if [ -f "$STATE_DIR/status.json" ] && grep -q "\"request\":\"$2\"" "$STATE_DIR/status.json"; then
                cat "$STATE_DIR/status.json"
                exit 0
            fi
            sleep 0.1
            cws_wait=$((cws_wait + 1))
        done
        echo '监控进程未响应，请确认模块已启用并重启手机。' >&2
        exit 1 ;;
    *) echo '用法：webctl.sh status <请求标识> | threshold <-100..-20> | fluid <0|1>' >&2; exit 1 ;;
esac
