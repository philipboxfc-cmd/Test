#!/bin/sh
#
# KindleNotes launcher. Started by upstart (/etc/upstart/kindlenotes.conf).
#
# It lives on the root file system on purpose: the daemon is copied to a RAM
# directory and started with cwd=/, so nothing keeps a file open on /mnt/us
# and the Kindle can still switch to USB drive mode while notes are running.
#
NOTES=/mnt/us/notes

RUN=""
for d in /var/tmp /tmp /dev/shm; do
    if [ -d "$d" ] && touch "$d/.kindlenotes-probe" 2>/dev/null; then
        rm -f "$d/.kindlenotes-probe"
        RUN="$d/kindlenotes"
        break
    fi
done
[ -n "$RUN" ] || exit 1
mkdir -p "$RUN"
LOG="$RUN/notesd.log"

# Wait for the user storage. Never give up: exiting here would make upstart
# respawn us in a loop while the Kindle is connected over USB.
while [ ! -f "$NOTES/bin/notesd" ]; do
    sleep 3
done

PORT=8080
LAN=0
UI_LANG=ru
[ -f "$NOTES/config" ] && . "$NOTES/config"
case "$PORT" in ''|*[!0-9]*) PORT=8080 ;; esac
BIND=127.0.0.1
[ "$LAN" = "1" ] && BIND=0.0.0.0
[ "$UI_LANG" = "en" ] || UI_LANG=ru

mkdir -p "$NOTES/data" 2>/dev/null
cp -f "$NOTES/bin/notesd" "$RUN/notesd" || { sleep 10; exit 1; }
chmod 755 "$RUN/notesd"

cd /
"$RUN/notesd" -b "$BIND" -p "$PORT" -d "$NOTES/data" -x /mnt/us/documents -l "$LOG" -L "$UI_LANG" &
CHILD=$!
trap 'kill $CHILD 2>/dev/null; exit 0' TERM INT
wait $CHILD
# throttle respawns if the daemon died (for example: port already in use)
sleep 10
exit 1
