#!/bin/bash
# Restore system pkg removed by debloat. Usage: ./restore.sh <pkg>  |  ./restore.sh all
if [ "$1" = all ]; then pkgs=$(grep -vE '^\s*(#|$)' "$(dirname "$0")/bloat.txt"); else pkgs="$@"; fi
for p in $pkgs; do
  adb shell cmd package install-existing "$p" || echo "no system copy: install from backup/apks/$p"
done
