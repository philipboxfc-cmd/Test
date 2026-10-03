#!/bin/sh
#
# KindleNotes helper: notes.sh start|stop|restart|status|open|log
# For use over SSH (USBNetwork) or from a launcher such as launchpad.
#
NOTES=/mnt/us/notes
PORT=8080
[ -f "$NOTES/config" ] && . "$NOTES/config"
URL="http://127.0.0.1:${PORT}/"

running() { pidof notesd >/dev/null 2>&1; }

case "$1" in
start)
    if running; then
        echo "notesd already running"
    elif ! initctl start kindlenotes >/dev/null 2>&1 && ! start kindlenotes >/dev/null 2>&1; then
        nohup /bin/sh /etc/kindlenotes/launch.sh >/dev/null 2>&1 &
        echo "notesd started (manual)"
    else
        echo "notesd started"
    fi
    ;;
stop)
    initctl stop kindlenotes >/dev/null 2>&1 || stop kindlenotes >/dev/null 2>&1
    for pid in $(pidof notesd 2>/dev/null); do kill "$pid" 2>/dev/null; done
    echo "notesd stopped"
    ;;
restart)
    "$0" stop
    sleep 1
    "$0" start
    ;;
status)
    if running; then echo "notesd running, $URL"; else echo "notesd not running"; fi
    ;;
open)
    # Experimental: ask the framework to open the browser at the notes page.
    # If this does nothing on your firmware, open the browser by hand and
    # type the address: 127.0.0.1:8080
    lipc-set-prop com.lab126.appmgrd start "app://com.lab126.browser?action=goto&url=$URL" 2>/dev/null ||
        lipc-set-prop com.lab126.browser goto "$URL" 2>/dev/null ||
        echo "could not open the browser automatically; type $URL in the Kindle browser"
    ;;
log)
    for d in /var/tmp /tmp /dev/shm; do
        [ -f "$d/kindlenotes/notesd.log" ] && tail -n 50 "$d/kindlenotes/notesd.log"
    done
    ;;
*)
    echo "usage: $0 start|stop|restart|status|open|log"
    exit 2
    ;;
esac
