#!/bin/bash
# Uninstall for user 0 (reversible). Usage: ./debloat.sh [list]
LIST=${1:-$(dirname "$0")/bloat.txt}
LOG=$(dirname "$0")/../backup/debloat_log.txt
grep -vE '^\s*(#|$)' "$LIST" | while read -r p; do
  r=$(adb shell -n pm uninstall -k --user 0 "$p" 2>&1 | tr -d '\r')
  echo "$(date +%F_%T) $p $r" | tee -a "$LOG"
done
