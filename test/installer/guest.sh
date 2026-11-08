#!/bin/sh
# Only PID 1's diskless, networkless lab VM can launch this destructive guest setup.
grep -q 'wifisense-installer-lab=1' /proc/cmdline || exit 77
[ -f /lab/phase ] && [ -f /lab/hooks.sh ] || exit 77
export PATH=/bin:/usr/bin:/sbin:/usr/sbin LC_ALL=C
phase=$(cat /lab/phase)
mkdir -p /results /cases
mkdir -p /template/bin /template/lib /template/ksu/bin /template/usr/lib
cp /bin/busybox /template/bin/busybox
cp /lib/ld-musl-x86_64.so.1 /template/lib/ld-musl-x86_64.so.1
cp /usr/bin/dash /template/bin/dash
cp /lab/ksu/busybox /template/ksu/bin/busybox
cp /usr/bin/strace /template/bin/strace
cp -a /lab/probe-libs/. /template/usr/lib/
/bin/busybox --list | while read -r cmd; do ln -sf busybox "/template/bin/$cmd"; done
printf 'installer\tshell\tcase\trc\tthreshold\tfluid\tstaging\ttmp\told_module\tupdate\tfluid_dex\tdir_mode\tfile_mode\n' > /results/matrix.tsv
id=0
for upstream in /lab/upstream/*.sh; do
  if [ -f /lab/probe ] && [ "${upstream##*/}" != fina.sh ]; then continue; fi
  installer=${upstream##*/}; installer=${installer%.sh}
  for shell in ash ksu-ash dash; do
    echo "Testing $installer / $shell ($phase)" >&2
    scenarios='normal_up normal_down fresh_up upgrade_threshold dir_conflict dir_denied dir_exists_unwritable upgrade_readonly_config threshold_is_dir existing_dir_mkdir_error tmp_missing_initial tmp_denied env_tmp_empty env_tmp_invalid env_api_empty env_api_invalid env_api_old env_ksu_empty abort_mid root_einval env_mod_empty env_mod_relative env_mod_root'
    [ ! -f /lab/smoke ] || scenarios='normal_up dir_denied'
    if [ -f /lab/probe ]; then
      [ "$shell" = ksu-ash ] || continue
      scenarios='root_einval root_einval_merged root_einval_fresh contract_success contract_abort contract_abort_subshell'
    fi
    for scenario in $scenarios; do
      id=$((id + 1)); name=$(printf '%03d-%s-%s-%s' "$id" "$installer" "$shell" "$scenario")
      echo "CASE $name" >&2
      root=/cases/$id; log=/results/$name
      mkdir -p "$root/bin" "$root/lib" "$root/etc" "$root/dev" "$root/proc" "$root/system" "$root/lab" "$root/data/adb/modules_update" "$root/data/adb/modules/coloros_wifi_sense" "$log"
      cp -a /template/. "$root/"
      ln -sf /bin/sh "$root/system/sh"
      mknod "$root/dev/null" c 1 3; chmod 666 "$root/dev/null"
      cp /lab/hooks.sh "$root/lab/hooks.sh"
      cp "/lab/$phase.zip" "$root/module.zip"
      echo old-installed-module > "$root/data/adb/modules/coloros_wifi_sense/sentinel"
      uid=0
      case "$scenario" in
        fresh_up|root_einval_fresh) rm -rf "$root/data/adb/modules/coloros_wifi_sense" ;;
        upgrade_threshold) mkdir -p "$root/data/adb/coloros_wifi_sense"; echo -85 > "$root/data/adb/coloros_wifi_sense/threshold" ;;
        dir_conflict) echo blocker > "$root/data/adb/coloros_wifi_sense" ;;
        dir_denied|tmp_denied) uid=1000 ;;
        dir_exists_unwritable|upgrade_readonly_config)
          uid=1000; mkdir -p "$root/data/adb/coloros_wifi_sense"
          if [ "$scenario" = upgrade_readonly_config ]; then echo -88 > "$root/data/adb/coloros_wifi_sense/threshold"; fi ;;
        threshold_is_dir) mkdir -p "$root/data/adb/coloros_wifi_sense/threshold" ;;
        existing_dir_mkdir_error) mkdir -p "$root/data/adb/coloros_wifi_sense" ;;
      esac
      cp "$upstream" "$root/lab/installer.sh"
      # No lines are prepended to upstream: ash's line numbers remain meaningful.
      cat "$upstream" > "$root/lab/runner"
      cat /lab/hooks.sh >> "$root/lab/runner"
      cat >> "$root/lab/runner" <<'TAIL'
case "$LAB_CASE" in
  contract_success|contract_abort|contract_abort_subshell)
    mkdir -p /dev/tmp /stage
    /bin/busybox unzip -oq /module.zip -d /stage
    MODPATH=/stage; TMPDIR=/dev/tmp; API=36
    cd /dev/tmp
    umask 022
    lab_context() {
      printf 'TMPDIR=%s\nMODPATH=%s\nBOOTMODE=%s\nKSU=%s\nAPI=%s\n' "$TMPDIR" "$MODPATH" "$BOOTMODE" "$KSU" "$API"
      pwd -P
      umask
    }
    lab_context > /lab/context.before
    if [ "$LAB_CASE" = contract_abort_subshell ]; then
      ( abort 'CONTROL: abort in a subshell' )
    else
      . /stage/customize.sh
    fi
    echo 'parent continued' > /lab/continued
    lab_context > /lab/context.after
    if [ "$LAB_CASE" = contract_success ]; then
      cmp /lab/context.before /lab/context.after || exit 9
      echo 'CONTEXT_UNCHANGED'
    fi ;;
  env_mod_empty|env_mod_relative|env_mod_root)
    /bin/busybox unzip -oq /module.zip -d /stage
    case "$LAB_CASE" in
      env_mod_empty) MODPATH= ;;
      env_mod_relative) MODPATH=stage ;;
      env_mod_root) MODPATH=/ ;;
    esac
    API=36
    . /stage/customize.sh ;;
  *) install_module ;;
esac
exit 0
TAIL
      cat > "$root/lab/launch" <<'LAUNCH'
#!/bin/sh
export PATH=/bin:/system/bin LC_ALL=C
export BOOTMODE=true KSU=true OUTFD=1 ZIPFILE=/module.zip
# getprop and ps must be available even while the upstream presets are sourced.
export LAB_API=36
case "$LAB_CASE" in
  env_tmp_empty) TMPDIR= ;;
  env_tmp_invalid) TMPDIR='/missing invalid tmp' ;;
  env_api_empty) LAB_API= ;;
  env_api_invalid) LAB_API=invalid ;;
  env_api_old) LAB_API=28 ;;
  env_ksu_empty) KSU= ;;
esac
export TMPDIR LAB_API
if [ "$LAB_SHELL" = ash ]; then
  exec /bin/busybox sh -c "$(cat /lab/runner)"
elif [ "$LAB_SHELL" = ksu-ash ]; then
  export ASH_STANDALONE=1
  exec /ksu/bin/busybox sh -c "$(cat /lab/runner)"
else
  exec /bin/dash -c "$(cat /lab/runner)"
fi
LAUNCH
      chmod 755 "$root/lab/launch"
      printf 'root:x:0:0:root:/:/bin/sh\nlab:x:1000:1000:lab:/:/bin/sh\n' > "$root/etc/passwd"
      if [ "$uid" = 1000 ]; then
        chown -R 1000:1000 "$root"
        case "$scenario" in
          dir_denied) chown 0:0 "$root/data/adb"; chmod 555 "$root/data/adb" ;;
          tmp_denied) chown 0:0 "$root/dev"; chmod 555 "$root/dev" ;;
          dir_exists_unwritable|upgrade_readonly_config)
            chown -R 0:0 "$root/data/adb/coloros_wifi_sense"; chmod 555 "$root/data/adb/coloros_wifi_sense"
            [ ! -f "$root/data/adb/coloros_wifi_sense/threshold" ] || chmod 444 "$root/data/adb/coloros_wifi_sense/threshold" ;;
        esac
      fi
      mount -t proc proc "$root/proc"
      if [ "$scenario" = root_einval_merged ]; then
        LAB_UID=$uid LAB_SHELL=$shell LAB_CASE=$scenario /bin/busybox timeout -k 2 20 /bin/busybox chroot "$root" /bin/sh /lab/launch > "$log/combined.log" 2>&1
      elif [ "$uid" = 0 ]; then
        LAB_UID=$uid LAB_SHELL=$shell LAB_CASE=$scenario /bin/busybox timeout -k 2 20 /bin/busybox chroot "$root" /bin/sh /lab/launch > "$log/stdout.log" 2> "$log/stderr.log"
      else
        LAB_UID=$uid LAB_SHELL=$shell LAB_CASE=$scenario /bin/busybox timeout -k 2 20 /bin/busybox chroot "$root" /bin/su -p -s /bin/sh -c '/bin/sh /lab/launch' lab > "$log/stdout.log" 2> "$log/stderr.log"
      fi
      rc=$?
      cp "$root/lab/strace-mkdir.log" "$log/strace-mkdir.log" 2>/dev/null
      for artifact in context.before context.after continued; do
        cp "$root/lab/$artifact" "$log/$artifact" 2>/dev/null
      done
      threshold=missing; fluid=missing; staging=no; tmp=no; old=no
      [ ! -f "$root/data/adb/coloros_wifi_sense/threshold" ] || threshold=$(cat "$root/data/adb/coloros_wifi_sense/threshold")
      [ ! -f "$root/data/adb/modules_update/coloros_wifi_sense/fluid_enabled" ] || fluid=$(cat "$root/data/adb/modules_update/coloros_wifi_sense/fluid_enabled")
      [ ! -d "$root/data/adb/modules_update/coloros_wifi_sense" ] || staging=yes
      [ ! -d "$root/dev/tmp" ] || tmp=yes
      [ ! -f "$root/data/adb/modules/coloros_wifi_sense/sentinel" ] || old=yes
      update=no; dex=no
      [ ! -f "$root/data/adb/modules/coloros_wifi_sense/update" ] || update=yes
      [ ! -f "$root/data/adb/modules_update/coloros_wifi_sense/wifi-fluid.dex" ] || dex=yes
      dir_mode=$(stat -c %a "$root/data/adb/coloros_wifi_sense" 2>/dev/null)
      file_mode=$(stat -c %a "$root/data/adb/coloros_wifi_sense/threshold" 2>/dev/null)
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$installer" "$shell" "$scenario" "$rc" "$threshold" "$fluid" "$staging" "$tmp" "$old" "$update" "$dex" "$dir_mode" "$file_mode" >> /results/matrix.tsv
      umount "$root/proc"
      rm -rf "$root"
    done
  done
  [ ! -f /lab/smoke ] || break
done
uname -a > /results/environment.txt
/bin/busybox | head -n 1 >> /results/environment.txt
/usr/bin/dash -c 'echo dash shell executed' >> /results/environment.txt
/lab/ksu/busybox | head -n 1 >> /results/environment.txt
echo "Completed $id isolated cases ($phase)" >&2
