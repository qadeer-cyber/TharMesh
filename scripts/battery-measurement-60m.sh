#!/usr/bin/env bash
# Owner / lab procedure — 60-minute batterystats capture (NOT EXECUTED in CI).
# Usage: connect a physical device via adb, start mesh field test, then:
#   THERMESH_BATTERY_LABEL=mesh_idle ./scripts/battery-measurement-60m.sh
set -euo pipefail
LABEL="${THERMESH_BATTERY_LABEL:-tharmesh_mesh}"
ADB="${ADB:-adb}"
DURATION_SEC="${THERMESH_BATTERY_DURATION_SEC:-3600}"
EVIDENCE="${THERMESH_BATTERY_EVIDENCE:-/tmp/tharmesh-battery-${LABEL}}"
mkdir -p "$EVIDENCE"
"$ADB" shell dumpsys batterystats --reset
echo "Reset batterystats at $(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee "$EVIDENCE/reset.txt"
echo "Run your mesh scenario for ${DURATION_SEC}s, then this script continues..."
sleep "$DURATION_SEC"
"$ADB" shell dumpsys batterystats | tee "$EVIDENCE/batterystats-after.txt"
"$ADB" shell dumpsys power | tee "$EVIDENCE/power-after.txt"
echo "RESULT=MEASURED label=${LABEL} duration_sec=${DURATION_SEC}" | tee "$EVIDENCE/verdict.txt"