#!/system/bin/sh
# macchanger - persistent WiFi MAC changer for MediaTek (MTK) Android
# Device: Infinix SMART 5 (MT6761) / Android 11 / Magisk
#
# Why not plain "ip link set"?  On MTK the WiFi driver reloads the factory
# MAC from NVRAM on every WiFi re-init, so ip-link spoofs vanish on
# reconnect/reboot.  The factory MAC lives in:
#     /mnt/vendor/nvdata/APCFG/APRDEB/WIFI   (6 bytes at offset 4)
# Editing that file persists across reboots and reconnects.

PATH=/system/bin:/system/xbin:/data/adb/magisk:$PATH
BB=/data/adb/magisk/busybox
DIR=/data/adb/macchanger
BAK=$DIR/WIFI.factory
NV=/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
IF=wlan0

[ -x "$BB" ] || BB=busybox

say()  { echo "$*"; }
die()  { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "must run as root (try: su -c '$0 $*')"
[ -f "$NV" ] || die "NVRAM file not found: $NV"

init_backup() {
    [ -f "$BAK" ] && return 0
    mkdir -p "$DIR"
    cp -a "$NV" "$BAK" || die "cannot save factory NVRAM backup"
    say "[*] saved factory NVRAM backup -> $BAK"
}

mac_from() {
    $BB dd if="$1" bs=1 skip=4 count=6 2>/dev/null \
      | $BB hexdump -v -e '6/1 "%02x"' \
      | sed 's/\(..\)/\1:/g; s/:$//'
}

get_runtime() { cat /sys/class/net/$IF/address 2>/dev/null; }
get_nvram()   { mac_from "$NV"; }
get_factory() { [ -f "$BAK" ] && mac_from "$BAK"; }

valid_mac() {
    echo "$1" | grep -Eq '^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$'
}
norm_mac() { echo "$1" | tr 'A-F' 'a-f'; }

set_nvram() {
    esc=$(echo "$1" | tr -d ':' | sed 's/../\\x&/g')
    $BB printf "%b" "$esc" | $BB dd of="$NV" bs=1 seek=4 conv=notrunc 2>/dev/null
}

reinit_wifi() {
    say "[*] reinitialising WiFi (this briefly disconnects)..."
    svc wifi disable; sleep 2
    svc wifi enable;  sleep 6
}

cmd_set() {
    [ -n "$1" ] || die "usage: $0 set AA:BB:CC:DD:EE:FF"
    mac=$(norm_mac "$1")
    valid_mac "$mac" || die "invalid MAC address: $1"

    first=$(echo "$mac" | cut -d: -f1)
    if [ $((0x$first & 1)) -ne 0 ]; then
        say "[!] warning: $mac is a MULTICAST address; most drivers reject it."
    fi

    init_backup
    say "[*] writing NVRAM ($NV) ..."
    set_nvram "$mac"
    [ "$(get_nvram)" = "$mac" ] || die "NVRAM write verification failed"

    # Reliable apply: full WiFi restart so the driver re-reads NVRAM.
    # Do NOT touch ip link here - it crashes the WiFi service on MTK.
    reinit_wifi
    sleep 1

    say "[+] runtime MAC : $(get_runtime)"
    say "[+] nvram   MAC : $(get_nvram)"
    say "[!] your whitelisted WiFi will drop unless this MAC is whitelisted."
    say "    run '$0 restore' to go back to the factory MAC."
}

cmd_random() {
    r=$($BB od -An -N5 -tx1 /dev/urandom | $BB awk '{printf "02:%s:%s:%s:%s:%s",$1,$2,$3,$4,$5}')
    say "[*] random MAC: $r"
    cmd_set "$r"
}

cmd_wifi() {
    reinit_wifi
    say "[+] runtime MAC : $(get_runtime)"
}

cmd_restore() {
    init_backup
    cp -a "$BAK" "$NV" || die "restore failed"
    say "[*] restored factory NVRAM MAC: $(get_factory)"
    reinit_wifi
    say "[+] runtime MAC : $(get_runtime)"
}

cmd_show() {
    init_backup
    say "interface      : $IF"
    say "factory (backup): $(get_factory)"
    say "nvram           : $(get_nvram)"
    say "runtime         : $(get_runtime)"
}

usage() {
    cat <<EOF
macchanger - persistent WiFi MAC changer (MTK NVRAM method)

usage:
  $0 show                 show factory / nvram / live MAC
  $0 set AA:BB:CC:DD:EE:FF   set a specific MAC (writes NVRAM + restarts
                          WiFi so the driver picks it up)
  $0 random               set a random locally-administered MAC
  $0 wifi                 just restart WiFi (applies current NVRAM MAC)
  $0 restore              restore the factory MAC from backup
  $0 help                 this text

notes:
  * persists across reboots (writes MTK NVRAM).
  * changing the MAC drops your whitelisted WiFi connection.
EOF
}

case "$1" in
    show|status)   cmd_show ;;
    set)           cmd_set "$2" ;;
    random|rand)   cmd_random ;;
    wifi)          cmd_wifi ;;
    restore|reset) cmd_restore ;;
    ""|-h|--help|help) usage ;;
    *) usage; exit 1 ;;
esac
