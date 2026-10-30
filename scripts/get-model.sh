#!/usr/bin/env bash
# Скачивает русскую офлайн-модель Vosk в assets Android-проекта.
set -euo pipefail
DEST=android/app/src/main/assets/model-ru
NAME=vosk-model-small-ru-0.22
if [ ! -f "$DEST/uuid" ]; then
  mkdir -p .cache
  [ -f ".cache/$NAME.zip" ] || curl -fL -o ".cache/$NAME.zip" "https://alphacephei.com/vosk/models/$NAME.zip"
  rm -rf "$DEST" ".cache/$NAME"
  unzip -q ".cache/$NAME.zip" -d .cache
  mkdir -p "$(dirname "$DEST")"
  mv ".cache/$NAME" "$DEST"
  echo "$NAME" > "$DEST/uuid"
fi
echo "Vosk model ready in $DEST"
