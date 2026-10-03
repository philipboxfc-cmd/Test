#!/bin/sh
#
# KindleNotes - uninstaller for the Kindle Keyboard (Kindle 3).
# Removes the service and program files. Your notes in /mnt/us/notes/data
# and your config file are kept.
#
NOTES=/mnt/us/notes
LOG=/mnt/us/notes/install.log

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
    echo "$*"
}

root_rw() {
    if command -v mntroot >/dev/null 2>&1; then mntroot rw >/dev/null 2>&1; else mount -o remount,rw / >/dev/null 2>&1; fi
}
root_ro() {
    if command -v mntroot >/dev/null 2>&1; then mntroot ro >/dev/null 2>&1; else mount -o remount,ro / >/dev/null 2>&1; fi
}

log "KindleNotes: uninstall started"

initctl stop kindlenotes >/dev/null 2>&1
stop kindlenotes >/dev/null 2>&1
for pid in $(pidof notesd 2>/dev/null); do kill "$pid" 2>/dev/null; done
killall notesd >/dev/null 2>&1

root_rw
rm -f /etc/upstart/kindlenotes.conf
rm -rf /etc/kindlenotes
sync
root_ro

rm -rf /var/tmp/kindlenotes /tmp/kindlenotes /dev/shm/kindlenotes
rm -rf "$NOTES/bin" "$NOTES/README.txt" "$NOTES/config.default"

log "KindleNotes: uninstall finished (notes kept in $NOTES/data)"
exit 0
