#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/coloros_wifi_sense
# Restore only this module's recorded runtime restriction; preserve thresholds.
if [ -d "$STATE_DIR" ]; then
    export CLASSPATH="$MODDIR/wifi-sense.dex"
    /system/bin/app_process /system/bin WifiBandControl "$MODDIR" "$STATE_DIR" 0
fi
