# Android-only services are mocks. File operations use real guest Linux syscalls.
mount_partitions() { :; }
recovery_actions() { :; }
recovery_cleanup() { :; }
boot_actions() { :; }
# Magisk otherwise re-execs dash into ash. Keep the explicitly requested
# comparison shell; this hook does not change module or filesystem operations.
ensure_bb() { :; }
getprop() {
  case "$1" in
    ro.build.version.sdk) printf '%s\n' "${LAB_API-36}" ;;
    ro.product.cpu.abi) echo arm64-v8a ;;
    sys.boot_completed) echo 1 ;;
  esac
}
getevent() {
  case "$LAB_CASE" in abort_mid|contract_abort) return 1 ;; esac
  case "$LAB_CASE" in
    normal_down) echo 'EV_KEY KEY_VOLUMEDOWN DOWN' ;;
    *) echo 'EV_KEY KEY_VOLUMEUP DOWN' ;;
  esac
}
chown() {
  # Permission cases run as uid 1000: Android ownership assignment is not under test.
  [ "$LAB_UID" = 1000 ] || /bin/busybox chown "$@"
}
chcon() {
  # Linux VM has no Android SELinux policy. Only diagnose missing objects.
  for lab_last do :; done
  if [ ! -e "$lab_last" ]; then
    echo "chcon: $lab_last: No such file or directory" >&2
    return 1
  fi
}
mkdir() {
  # Explicit fault injection, not evidence of the device's real errno cause.
  for lab_arg do
    case "$LAB_CASE:$lab_arg" in
      root_einval*:/*)
        # Real BusyBox message, generated from a ptrace-injected syscall errno.
        /bin/strace -qq -A -o /lab/strace-mkdir.log -e trace=execve,mkdir,mkdirat -e inject=mkdir,mkdirat:error=EINVAL:when=1 /ksu/bin/busybox mkdir "$@"
        return $? ;;
      root_einval*:coloros_wifi_sense)
        /bin/strace -qq -A -o /lab/strace-mkdir.log -e trace=execve,mkdir,mkdirat /ksu/bin/busybox mkdir "$@"
        return $? ;;
      existing_dir_mkdir_error:/data/adb/coloros_wifi_sense)
        echo "mkdir: injected failure for existing configuration directory" >&2
        return 1 ;;
    esac
  done
  if [ "$LAB_SHELL" = ksu-ash ]; then
    /ksu/bin/busybox mkdir "$@"
  else
    /bin/busybox mkdir "$@"
  fi
}
