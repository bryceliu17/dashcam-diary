#!/usr/bin/env bash
# Read-only Android internal-flash inventory. Requires adb on macOS/Linux.
# Optional: ADB_SERIAL=<serial> bash check_android_flash_health.sh
set -u
PATH="/usr/bin:/bin:$PATH"

ADB_BIN=${ADB_BIN:-adb}
ADB_ARGS=()
if [ -n "${ADB_SERIAL:-}" ]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "ERROR: adb is not installed or not on PATH." >&2
  exit 1
fi
if ! "$ADB_BIN" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1; then
  echo "ERROR: no authorized device (or multiple devices). Check 'adb devices -l'; set ADB_SERIAL if needed." >&2
  exit 1
fi

adb_shell() { "$ADB_BIN" "${ADB_ARGS[@]}" shell "$@"; }

MODEL=unknown
VENDOR=unknown
MANFID=unknown
CAPACITY=unknown
LIFE_A=unavailable
LIFE_B=unavailable
PRE_EOL=unavailable
ROOT=no

strip_cr() { tr -d '\000\r'; }

read_field() {
  local mode=$1 label=$2 path=$3 value status
  if [ "$mode" = root ]; then
    if value=$(adb_shell su -c "cat '$path'" 2>&1); then status=0; else status=$?; fi
  else
    if value=$(adb_shell "cat '$path'" 2>&1); then status=0; else status=$?; fi
  fi
  value=$(printf '%s' "$value" | strip_cr)
  # Some old Android shells return success even when cat prints an error.
  case "$value" in
    *'Permission denied'*|*'No such file or directory'*|*'not found'*) status=1 ;;
  esac
  if [ "$status" -eq 0 ]; then
    printf '  %-19s %-56s %s\n' "$label" "$path" "$value"
    case "$label" in
      model) MODEL=$value ;;
      manufacturer_id) MANFID=$value ;;
      manufacturer_name) VENDOR=$value ;;
      life_time_a) LIFE_A=$value ;;
      life_time_b) LIFE_B=$value ;;
      pre_eol) PRE_EOL=$value ;;
      life_time_pair)
        # Some kernels expose both EXT_CSD bytes in one sysfs attribute.
        if [[ $value =~ ^(0[xX][0-9A-Fa-f]+)[[:space:]]+(0[xX][0-9A-Fa-f]+)$ ]]; then
          LIFE_A=${BASH_REMATCH[1]}
          LIFE_B=${BASH_REMATCH[2]}
        fi
        ;;
    esac
  else
    printf '  %-19s %-56s ERROR: %s\n' "$label" "$path" "$value"
  fi
}

life_meaning() {
  local raw=$1 n
  if [[ $raw =~ ^(0[xX])?([0-9A-Fa-f]{1,2})$ ]]; then
    n=$((16#${BASH_REMATCH[2]}))
    case $n in
      0) echo 'undefined / unsupported' ;;
      1|2|3|4|5|6|7|8|9|10) echo "$(((n-1)*10))-$((n*10))% of rated life used (range, not an exact percentage)" ;;
      11) echo 'beyond rated lifetime' ;;
      *) echo 'reserved / vendor-specific / unknown' ;;
    esac
  else
    echo 'not readable or not a recognized raw byte'
  fi
}

eol_meaning() {
  local raw=$1 n
  if [[ $raw =~ ^(0[xX])?([0-9A-Fa-f]{1,2})$ ]]; then
    n=$((16#${BASH_REMATCH[2]}))
    case $n in
      0) echo 'undefined / unsupported' ;;
      1) echo 'Normal' ;;
      2) echo 'Warning' ;;
      3) echo 'Urgent' ;;
      *) echo 'reserved / vendor-specific / unknown' ;;
    esac
  else
    echo 'not readable or not a recognized raw byte'
  fi
}

echo '=== Android flash health (read-only) ==='
printf 'Device: %s\n' "$(adb_shell 'getprop ro.product.model' | strip_cr)"
printf 'Shell identity: %s\n' "$(adb_shell id | strip_cr)"

parts=$(adb_shell 'cat /proc/partitions' 2>&1 | strip_cr)
mount_data=$(adb_shell 'mount' 2>&1 | strip_cr | grep ' on /data ' || true)
if [ -z "$mount_data" ]; then
  mount_data=$(adb_shell 'mount' 2>&1 | strip_cr | grep ' /data ' || true)
fi
data_source=$(printf '%s\n' "$mount_data" | awk '{print $1; exit}')
data_link=
if [[ $data_source == */by-name/* ]]; then
  data_link=$(adb_shell "ls -l '$data_source'" 2>&1 | strip_cr)
fi
echo
echo '--- /proc/partitions ---'
printf '%s\n' "$parts"
echo "--- /data mount: ${mount_data:-unavailable}"
echo "--- /data by-name link: ${data_link:-unavailable}"

TYPE=Unknown
BLOCK=unknown
if printf '%s\n' "$mount_data" | grep -q 'mmcblk[0-9]'; then
  BLOCK=$(printf '%s\n' "$mount_data" | grep -o 'mmcblk[0-9]*' | head -1)
  TYPE=eMMC
elif printf '%s\n' "$data_link" | grep -q 'mmcblk[0-9]*p'; then
  BLOCK=$(printf '%s\n' "$data_link" | grep -o 'mmcblk[0-9]*' | head -1)
  TYPE=eMMC
elif printf '%s\n' "$mount_data" | grep -qE '/dev/block/sd[a-z][0-9]*'; then
  if printf '%s\n' "$parts" | grep -qE '[[:space:]]sda$' &&
     adb_shell 'ls -d /sys/bus/platform/drivers/ufshcd /sys/class/ufs 2>/dev/null' 2>/dev/null | grep -qi ufs; then
    TYPE=UFS
    BLOCK=sda
  fi
fi
if [ "$TYPE" = Unknown ] && printf '%s\n' "$parts" | grep -qE '[[:space:]]mmcblk0$'; then
  TYPE='eMMC (probable; verify /data backing device)'
  BLOCK=mmcblk0
fi
if [ "$TYPE" = Unknown ] && printf '%s\n' "$parts" | grep -qE '[[:space:]]sda$' &&
   adb_shell 'ls -d /sys/bus/platform/drivers/ufshcd /sys/class/ufs 2>/dev/null' 2>/dev/null | grep -qi ufs; then
  TYPE=UFS
  BLOCK=sda
fi

if [ "$BLOCK" != unknown ]; then
  kib=$(printf '%s\n' "$parts" | awk -v b="$BLOCK" '$4 == b {print $3; exit}')
  if [ -n "$kib" ]; then
    CAPACITY=$(awk -v k="$kib" 'BEGIN {printf "%.0f bytes (%.2f GiB)", k*1024, k/1048576}')
  fi
fi

echo
echo '--- Root and read-only tool availability ---'
su_result=$(adb_shell su -c id 2>&1 | strip_cr)
if printf '%s' "$su_result" | grep -q 'uid=0(root)'; then ROOT=yes; fi
printf 'su -c id: %s\n' "$su_result"
for tool in mmc mmc-utils smartctl; do
  result=$(adb_shell "command -v $tool" 2>&1 | strip_cr)
  printf '%s: %s\n' "$tool" "${result:-not installed / not on device PATH}"
done

echo
echo '--- /proc/bootdevice (vendor-specific, read-only when available) ---'
if adb_shell 'ls -ld /proc/bootdevice 2>&1' | grep -q '^d'; then
  read_field shell bootdevice_type /proc/bootdevice/type
  read_field shell bootdevice_host /proc/bootdevice/name
  read_field shell model /proc/bootdevice/product_name
  read_field shell manufacturer_id /proc/bootdevice/manfid
  read_field shell bootdevice_size /proc/bootdevice/size
  read_field shell life_time_a /proc/bootdevice/life_time_est_typ_a
  read_field shell life_time_b /proc/bootdevice/life_time_est_typ_b
  read_field shell pre_eol /proc/bootdevice/pre_eol_info
fi
echo
echo '--- /sys name searches (empty may mean traversal is restricted) ---'
for pattern in '*life*' '*eol*' '*health*' '*ufs*' '*mmc*' '*ext_csd*'; do
  echo "Pattern: $pattern"
  adb_shell "find /sys -iname '$pattern' 2>/dev/null" 2>&1 | strip_cr | head -30
done

echo
echo '--- Kernel log: dmesg storage lines ---'
dmesg_output=$(adb_shell dmesg 2>&1 | strip_cr)
if printf '%s' "$dmesg_output" | grep -qi 'permission denied'; then
  printf '%s\n' "$dmesg_output" | head -3
else
  printf '%s\n' "$dmesg_output" | grep -iE 'mmc|ufs|storage|scsi' | head -60 || true
fi

if [[ $TYPE == eMMC* ]]; then
  base="/sys/block/$BLOCK/device"
  for mode in shell root; do
    [ "$mode" = shell ] || [ "$ROOT" = yes ] || continue
    echo
    echo "--- eMMC sysfs as $mode ---"
    read_field "$mode" type "$base/type"
    read_field "$mode" model "$base/name"
    read_field "$mode" manufacturer_id "$base/manfid"
    read_field "$mode" oem_id "$base/oemid"
    read_field "$mode" revision "$base/rev"
    read_field "$mode" life_time_pair "$base/life_time"
    read_field "$mode" life_time_a "$base/life_time_est_typ_a"
    read_field "$mode" life_time_b "$base/life_time_est_typ_b"
    read_field "$mode" pre_eol "$base/pre_eol_info"
    read_field "$mode" block_sectors "/sys/block/$BLOCK/size"
    # These sysfs attributes are the kernel's parsed EXT_CSD bytes 268, 269, 267.
  done
  echo 'Raw EXT_CSD candidate paths (if this kernel exposes debugfs):'
  adb_shell 'find /sys/kernel/debug -iname "*ext_csd*" -o -iname "*extcsd*" 2>/dev/null' 2>&1 | strip_cr | head -20
  echo 'Common debugfs EXT_CSD path (metadata only, no raw dump):'
  adb_shell 'ls -l /sys/kernel/debug/mmc0/mmc0:0001/ext_csd' 2>&1 | strip_cr
  mmc_path=$(adb_shell 'command -v mmc' 2>/dev/null | strip_cr)
  if [ -n "$mmc_path" ]; then
    echo
    echo '--- mmc extcsd read (read-only; selected health lines) ---'
    adb_shell "mmc extcsd read /dev/block/$BLOCK" 2>&1 | strip_cr | grep -iE 'LIFE_TIME|PRE_EOL|error|denied' || true
    if [ "$ROOT" = yes ]; then
      adb_shell su -c "mmc extcsd read /dev/block/$BLOCK" 2>&1 | strip_cr | grep -iE 'LIFE_TIME|PRE_EOL|error|denied' || true
    fi
  fi
fi

if [ "$TYPE" = UFS ]; then
  echo
  echo '--- UFS health descriptor candidates ---'
  dirs=$(adb_shell 'find /sys -type d -name health_descriptor 2>/dev/null' 2>/dev/null | strip_cr | head -30)
  if [ "$ROOT" = yes ]; then
    root_dirs=$(adb_shell su -c 'find /sys -type d -name health_descriptor 2>/dev/null' 2>/dev/null | strip_cr | head -30)
    dirs=$(printf '%s\n%s\n' "$dirs" "$root_dirs" | sort -u)
  fi
  if [ -z "$dirs" ]; then echo 'No readable descriptor directory found.'; fi
  while IFS= read -r dir; do
    [ -n "$dir" ] || continue
    for mode in shell root; do
      [ "$mode" = shell ] || [ "$ROOT" = yes ] || continue
      read_field "$mode" life_time_a "$dir/life_time_estimation_a"
      read_field "$mode" life_time_b "$dir/life_time_estimation_b"
      read_field "$mode" pre_eol "$dir/eol_info"
    done
  done <<< "$dirs"
  echo 'UFS manufacturer/product descriptor candidates:'
  desc=$(adb_shell 'find /sys -type f \( -name manufacturer_name -o -name product_name -o -name wmanufacturerid \) 2>/dev/null' 2>/dev/null | strip_cr | head -30)
  if [ "$ROOT" = yes ]; then
    root_desc=$(adb_shell su -c 'find /sys -type f \( -name manufacturer_name -o -name product_name -o -name wmanufacturerid \) 2>/dev/null' 2>/dev/null | strip_cr | head -30)
    desc=$(printf '%s\n%s\n' "$desc" "$root_desc" | sort -u)
  fi
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    case "$path" in
      */manufacturer_name) label=manufacturer_name ;;
      */product_name) label=model ;;
      *) label=manufacturer_id ;;
    esac
    read_field shell "$label" "$path"
    if [ "$ROOT" = yes ]; then read_field root "$label" "$path"; fi
  done <<< "$desc"
fi

if [ "$ROOT" = yes ]; then
  smart_path=$(adb_shell 'command -v smartctl' 2>/dev/null | strip_cr)
  if [ -n "$smart_path" ] && [ "$BLOCK" != unknown ]; then
    echo
    echo '--- smartctl -a (read-only; may not support phone flash) ---'
    adb_shell su -c "smartctl -a /dev/block/$BLOCK" 2>&1 | strip_cr | head -80
  fi
fi

if [ "$VENDOR" = unknown ]; then
  case "$MANFID" in
    0x000045|0x45) VENDOR='SanDisk (inferred from eMMC manufacturer ID 0x45)' ;;
    0x000015|0x15) VENDOR='Samsung (inferred from eMMC manufacturer ID 0x15)' ;;
  esac
fi

echo
echo '=== Summary: observed values, not age-based estimates ==='
printf 'Storage type: %s\n' "$TYPE"
printf 'Block device: %s\n' "$BLOCK"
printf 'Model: %s\n' "$MODEL"
printf 'Manufacturer: %s\n' "$VENDOR"
printf 'Manufacturer ID: %s\n' "$MANFID"
printf 'Capacity: %s\n' "$CAPACITY"
printf 'Life Time A: %s — %s\n' "$LIFE_A" "$(life_meaning "$LIFE_A")"
printf 'Life Time B: %s — %s\n' "$LIFE_B" "$(life_meaning "$LIFE_B")"
printf 'Pre-EOL: %s — %s\n' "$PRE_EOL" "$(eol_meaning "$PRE_EOL")"
printf 'Root available: %s\n' "$ROOT"
if [ "$ROOT" = no ]; then
  echo 'Unreadable /sys nodes may require root; an absent node is different from permission denied.'
fi
echo 'No system files were changed. No writes, formatting, erase or stress tests were performed.'
