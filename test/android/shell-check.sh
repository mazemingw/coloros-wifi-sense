#!/system/bin/sh
set -eu
M=/data/local/tmp/cws-event-test/module
S=/data/local/tmp/cws-event-test/state
[ -f "$M/webctl.sh" ] && [ -d "$S" ] || exit 77
passed=0
for value in -100 -90 -85 -80 -75 -20; do
    sh "$M/webctl.sh" threshold "$value" >/dev/null
    [ "$(cat "$S/threshold")" = "$value" ] || exit 1
    passed=$((passed+1))
done
for value in -101 -19 -80.5 '--help' '-80; id' ''; do
    if sh "$M/webctl.sh" threshold "$value" >/dev/null 2>&1; then exit 1; fi
    passed=$((passed+1))
done
for value in '' 'bad nonce' 'bad/path'; do
    if sh "$M/webctl.sh" status "$value" >/dev/null 2>&1; then exit 1; fi
    passed=$((passed+1))
done
for value in 1 0; do
    sh "$M/webctl.sh" fluid "$value" >/dev/null
    [ "$(cat "$M/fluid_enabled")" = "$value" ] || exit 1
    [ "$(stat -c %a "$M/fluid_enabled")" = 600 ] || exit 1
    passed=$((passed+1))
done
if sh "$M/webctl.sh" fluid '1; id' >/dev/null 2>&1; then exit 1; fi
passed=$((passed+1))
mv "$M/wifi-fluid.dex" "$M/wifi-fluid.held"
trap 'mv "$M/wifi-fluid.held" "$M/wifi-fluid.dex"' EXIT
if sh "$M/webctl.sh" fluid 1 >/dev/null 2>&1; then exit 1; fi
passed=$((passed+1))
mv "$M/wifi-fluid.held" "$M/wifi-fluid.dex"
trap - EXIT
if sh "$M/webctl.sh" status stopped_test >/dev/null 2>&1; then exit 1; fi
passed=$((passed+1))
echo "PASS $passed Android shell/WebUI command checks (private fixture only)"
for value in 1 2 7 30 60 300; do
    sh "$M/webctl.sh" countdown "$value" >/dev/null
    [ "$(cat "$S/countdown")" = "$value" ] || exit 1
    [ "$(stat -c %a "$S/countdown")" = 600 ] || exit 1
    passed=$((passed+1))
done
for value in 0 301 -1 1.5 07 '1; id' ''; do
    if sh "$M/webctl.sh" countdown "$value" >/dev/null 2>&1; then exit 1; fi
    passed=$((passed+1))
done
sh "$M/webctl.sh" countdown 7 >/dev/null
echo "PASS $passed total Android shell checks including countdown bounds and mode 0600"
