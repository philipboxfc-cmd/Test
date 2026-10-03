#!/bin/bash
# End-to-end tests for notesd. Works against the native build or against the
# ARM build run through qemu-arm-static:
#   NOTESD="build/notesd-native" tests/run.sh
#   NOTESD="qemu-arm-static payload/notes/bin/notesd" tests/run.sh
set -u

NOTESD=${NOTESD:-build/notesd-native}
PORT=${PORT:-18080}
BASE="http://127.0.0.1:$PORT"
TMP=$(mktemp -d)
DATA="$TMP/data"
EXPORT="$TMP/documents"
mkdir -p "$DATA" "$EXPORT"
LOG="$TMP/notesd.log"

fail=0
pass=0
check() { # check <description> <condition...>
    local desc=$1; shift
    if "$@"; then pass=$((pass + 1)); echo "ok   - $desc"; else fail=$((fail + 1)); echo "FAIL - $desc"; fi
}
contains() { grep -q -- "$2" <<<"$1"; }
not_contains() { ! grep -q -- "$2" <<<"$1"; }

$NOTESD -b 127.0.0.1 -p "$PORT" -d "$DATA" -x "$EXPORT" -l "$LOG" &
SRV=$!
trap 'kill $SRV 2>/dev/null; rm -rf "$TMP"' EXIT

for _ in $(seq 1 50); do
    curl -s -o /dev/null "$BASE/" && break
    sleep 0.1
done

# 1. empty list
out=$(curl -s "$BASE/")
check "list renders (ru)" contains "$out" "<h1>Заметки</h1>"
check "empty hint shown" contains "$out" "Заметок пока нет"

# 2. create a note with CRLF line endings
hdr=$(curl -s -D - -o /dev/null -X POST --data-urlencode $'body=Список покупок\r\nмолоко\r\nхлеб' --data-urlencode "id=" "$BASE/save")
check "save redirects" contains "$hdr" "HTTP/1.0 302"
id=$(sed -n 's/^Location: \/n\/\([A-Za-z0-9_-]*\).*/\1/p' <<<"$hdr" | tr -d '\r')
check "got an id" test -n "$id"
check "file created" test -f "$DATA/$id.txt"
check "line endings normalized to LF" test "$(od -c "$DATA/$id.txt" | grep -c '\\r')" = "0"
check "content saved" grep -q "хлеб" "$DATA/$id.txt"

# 3. list shows title and preview
out=$(curl -s "$BASE/")
check "list shows title" contains "$out" "<b>Список покупок</b>"
check "list shows preview" contains "$out" "молоко хлеб"
check "list links to note" contains "$out" "href=\"/n/$id\""

# 4. view
out=$(curl -s "$BASE/n/$id")
check "view shows body with breaks" contains "$out" "молоко<br>"
check "view has edit link" contains "$out" "href=\"/e/$id\""

# 5. edit existing
hdr=$(curl -s -D - -o /dev/null -X POST --data-urlencode "id=$id" --data-urlencode $'body=Список покупок\nмолоко\nхлеб\nсыр <b>&' "$BASE/save")
check "edit redirects to the note" contains "$hdr" "Location: /n/$id"
out=$(curl -s "$BASE/n/$id")
check "edited body present and escaped" contains "$out" "сыр &lt;b&gt;&amp;"
check "edit form prefilled" contains "$(curl -s "$BASE/e/$id")" "<textarea name=\"body\""

# 6. search: upper-case Cyrillic must match lower-case text
out=$(curl -s -G --data-urlencode "q=МОЛОКО" "$BASE/")
check "search is case-insensitive for Cyrillic" contains "$out" "href=\"/n/$id\""
out=$(curl -s -G --data-urlencode "q=zzz-not-there" "$BASE/")
check "search miss" contains "$out" "Ничего не найдено"
check "search miss hides notes" not_contains "$out" "href=\"/n/$id\""

# 7. quick note
hdr=$(curl -s -D - -o /dev/null -X POST --data-urlencode "text=Позвонить маме" "$BASE/quick")
check "quick note redirects" contains "$hdr" "HTTP/1.0 302"
check "quick note stored" grep -lq "Позвонить маме" "$DATA"/*.txt
check "two notes now" test "$(ls "$DATA"/*.txt | wc -l)" = "2"
hdr=$(curl -s -D - -o /dev/null -X POST --data-urlencode "text=   " "$BASE/quick")
check "blank quick note ignored" test "$(ls "$DATA"/*.txt | wc -l)" = "2"

# 8. export to documents
out=$(curl -s "$BASE/x/$id")
check "export page" contains "$out" "скопирована"
check "export file name is transliterated" test -f "$EXPORT/Note Spisok pokupok ($id).txt"
check "export content" grep -q "хлеб" "$EXPORT/Note Spisok pokupok ($id).txt"

# 9. delete with confirmation
out=$(curl -s "$BASE/d/$id")
check "delete confirm page" contains "$out" "Удалить эту заметку"
hdr=$(curl -s -D - -o /dev/null -X POST --data-urlencode "id=$id" "$BASE/delete")
check "delete redirects" contains "$hdr" "Location: /"
check "file removed" test ! -f "$DATA/$id.txt"

# 10. hostile input
code=$(curl -s -o /dev/null -w '%{http_code}' --path-as-is "$BASE/n/../../etc/passwd")
check "path traversal in view rejected" test "$code" = "400"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST --data-urlencode "id=../evil" --data-urlencode "body=x" "$BASE/save")
check "path traversal in save rejected" test "$code" = "400"
check "no stray files" test ! -e "$TMP/evil.txt"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/nope")
check "unknown page is 404" test "$code" = "404"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Content-Length: 5000000" --data "x" "$BASE/save")
check "oversized body rejected" test "$code" = "400"
printf 'GARBAGE\r\n\r\n' | nc -q1 127.0.0.1 "$PORT" >/dev/null 2>&1
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
check "server survives garbage request" test "$code" = "200"

# 11. storage disappears (USB mode) and comes back
mv "$DATA" "$DATA.off"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
check "503 while storage is unmounted" test "$code" = "503"
mv "$DATA.off" "$DATA"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
check "recovers when storage is back" test "$code" = "200"

# 12. about page
check "about page" contains "$(curl -s "$BASE/about")" "KindleNotes"

echo
echo "passed: $pass, failed: $fail"
[ "$fail" = "0" ]
