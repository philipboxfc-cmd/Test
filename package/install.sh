#!/bin/sh
#
# KindleNotes - installer for the Kindle Keyboard (Kindle 3, Wi-Fi).
#
# Runs as root from "Settings -> Menu -> Update Your Kindle" after being
# packaged with KindleTool (see build.sh). Must stay busybox-ash compatible.
# It can also be run by hand over SSH from the directory that holds
# kindlenotes-payload.tgz.
#
NOTES=/mnt/us/notes
PAYLOAD=kindlenotes-payload.tgz
LOG=/mnt/us/notes/install.log

mkdir -p "$NOTES/data" "$NOTES/bin" 2>/dev/null

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
    echo "$*"
}

root_rw() {
    if [ -x /usr/sbin/mntroot ] || command -v mntroot >/dev/null 2>&1; then
        mntroot rw >/dev/null 2>&1
    else
        mount -o remount,rw / >/dev/null 2>&1
    fi
}

root_ro() {
    if [ -x /usr/sbin/mntroot ] || command -v mntroot >/dev/null 2>&1; then
        mntroot ro >/dev/null 2>&1
    else
        mount -o remount,ro / >/dev/null 2>&1
    fi
}

log "KindleNotes: install started"

# ---- 1. find the payload -------------------------------------------------
HERE=$(dirname "$0")
P=""
for cand in "$HERE/$PAYLOAD" "./$PAYLOAD" /var/tmp/*/"$PAYLOAD" /tmp/*/"$PAYLOAD" \
            /var/tmp/"$PAYLOAD" /tmp/"$PAYLOAD" /mnt/us/"$PAYLOAD"; do
    if [ -f "$cand" ]; then
        P="$cand"
        break
    fi
done
if [ -z "$P" ]; then
    log "ERROR: $PAYLOAD not found next to the install script"
    exit 1
fi
log "payload: $P"

# ---- 2. stop a running copy ----------------------------------------------
initctl stop kindlenotes >/dev/null 2>&1
stop kindlenotes >/dev/null 2>&1
for pid in $(pidof notesd 2>/dev/null); do kill "$pid" 2>/dev/null; done
killall notesd >/dev/null 2>&1

# ---- 3. unpack ------------------------------------------------------------
T=/var/tmp/kindlenotes-install
[ -w /var/tmp ] || T=/tmp/kindlenotes-install
rm -rf "$T"
mkdir -p "$T"
if ! tar xzf "$P" -C "$T"; then
    log "ERROR: cannot unpack $P"
    exit 1
fi

# user-space part: /mnt/us/notes (keeps the user's config and notes)
cp -f "$T/notes/bin/notesd" "$NOTES/bin/notesd" || { log "ERROR: cannot copy notesd"; exit 1; }
cp -f "$T/notes/bin/notes.sh" "$NOTES/bin/notes.sh"
cp -f "$T/notes/README.txt" "$NOTES/README.txt"
cp -f "$T/notes/config" "$NOTES/config.default"
[ -f "$NOTES/config" ] || cp -f "$T/notes/config" "$NOTES/config"
chmod 755 "$NOTES/bin/notesd" "$NOTES/bin/notes.sh" 2>/dev/null
log "copied files to $NOTES"

# ---- 4. root fs part: launcher + upstart job -----------------------------
root_rw
mkdir -p /etc/kindlenotes
if ! cp -f "$T/rootfs/etc/kindlenotes/launch.sh" /etc/kindlenotes/launch.sh; then
    root_ro
    log "ERROR: cannot write /etc/kindlenotes/launch.sh (root fs not writable?)"
    exit 1
fi
chmod 755 /etc/kindlenotes/launch.sh
log "installed /etc/kindlenotes/launch.sh"

if [ -d /etc/upstart ]; then
    if [ -f /etc/upstart/framework.conf ]; then
        START="start on started framework"
    else
        START="start on startup"
    fi
    if sed "s/^start on .*/$START/" "$T/rootfs/etc/upstart/kindlenotes.conf" >/etc/upstart/kindlenotes.conf.new &&
       grep -q "^exec " /etc/upstart/kindlenotes.conf.new; then
        mv -f /etc/upstart/kindlenotes.conf.new /etc/upstart/kindlenotes.conf
        chmod 644 /etc/upstart/kindlenotes.conf
        log "installed /etc/upstart/kindlenotes.conf ($START)"
        AUTOSTART=1
    else
        rm -f /etc/upstart/kindlenotes.conf.new
        log "WARNING: could not write the upstart job; use notes.sh start"
        AUTOSTART=0
    fi
else
    log "WARNING: /etc/upstart not found, autostart not installed; use notes.sh start"
    AUTOSTART=0
fi
sync
root_ro

rm -rf "$T"

# ---- 5. start now (the Kindle also restarts after an update) -------------
if [ "$AUTOSTART" = "1" ]; then
    initctl start kindlenotes >/dev/null 2>&1 || start kindlenotes >/dev/null 2>&1 || \
        (nohup /bin/sh /etc/kindlenotes/launch.sh >/dev/null 2>&1 &)
else
    (nohup /bin/sh /etc/kindlenotes/launch.sh >/dev/null 2>&1 &)
fi

log "KindleNotes: install finished. Open http://127.0.0.1:8080/ in the Kindle browser."
exit 0
