#!/system/bin/sh
# On-demand KernelSU WebUI bridge; no HTTP listener or resident helper.
MODDIR=${0%/*}
STATE_DIR=/data/adb/coloros_wifi_sense
case "$1" in
    connectionconfig|connectionstatus|restoreautojoin)
        case "$1:$#" in
            connectionconfig:2)
                case "$2" in ''|*[!a-zA-Z0-9+/=]*) exit 1 ;; esac
                [ "${#2}" -le 12000 ] || exit 1
                cws_action=configure ;;
            connectionstatus:1) cws_action=status ;;
            restoreautojoin:1) cws_action=restore ;;
            *) exit 1 ;;
        esac
        export CLASSPATH="$MODDIR/wifi-sense.dex"
        if [ "$cws_action" = configure ]; then
            exec /system/bin/app_process /system/bin WifiConnectionControl "$STATE_DIR" "$cws_action" "$2"
        fi
        exec /system/bin/app_process /system/bin WifiConnectionControl "$STATE_DIR" "$cws_action" ;;
    band5g|bandstatus)
        case "$1:$#:${2-}" in band5g:2:0|band5g:2:1) cws_action=$2 ;; bandstatus:1:) cws_action=status ;; *) exit 1 ;; esac
        export CLASSPATH="$MODDIR/wifi-sense.dex"
        exec /system/bin/app_process /system/bin WifiBandControl "$MODDIR" "$STATE_DIR" "$cws_action" ;;
    countdown)
        [ "$#" -eq 2 ] || exit 1
        case "$2" in [1-9]|[1-9][0-9]|[12][0-9][0-9]|300) ;; *) echo '倒计时必须是 1 至 300 秒的整数。' >&2; exit 1 ;; esac
        [ -d "$STATE_DIR" ] || exit 1
        umask 077
        cws_tmp=$(mktemp "$STATE_DIR/countdown.XXXXXX") || exit 1
        trap 'rm -f "$cws_tmp"' EXIT
        printf '%s\n' "$2" > "$cws_tmp" && mv -f "$cws_tmp" "$STATE_DIR/countdown" || exit 1
        echo '自动断开倒计时已保存，正在进行的倒计时会重新开始。' ;;
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
    *) echo '用法：webctl.sh status <请求标识> | threshold <-100..-20> | countdown <1..300> | fluid <0|1> | band5g <0|1> | bandstatus' >&2; exit 1 ;;
esac
