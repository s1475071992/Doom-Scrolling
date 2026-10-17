#!/usr/bin/env bash
# Compare CPU/GPU/NPU over the same interval on real phones.
# Usage: bash tools/adb_heat_benchmark.sh SERIAL 1800 npu
# Keep screen brightness, ambient temperature, battery starting level, camera
# FPS and test gestures constant across modes. This does not prove NPU dispatch.
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo "Usage: $0 ANDROID_SERIAL DURATION_SECONDS LABEL" >&2
  exit 2
fi
SERIAL="$1"
DURATION="$2"
LABEL="$3"
[[ "$DURATION" =~ ^[0-9]+$ ]] || { echo "Duration must be an integer" >&2; exit 2; }
if ((DURATION < 60)); then echo "Run at least 60 seconds" >&2; exit 2; fi
command -v adb >/dev/null || { echo "adb not on PATH" >&2; exit 2; }
adb -s "$SERIAL" get-state >/dev/null
NAME="${LABEL}_$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "benchmark-results/${NAME}"
REPORT="benchmark-results/${NAME}/battery.csv"
echo "epoch_s,level_percent,battery_tenth_c,voltage_mv,charge_state" > "$REPORT"
echo "Running ${LABEL} sample for ${DURATION}s, every 30s. Do NOT mistake battery temp for NPU utilization."
END=$((SECONDS + DURATION))
while ((SECONDS < END)); do
    STAMP="$(date +%s)"
    RAW="$(adb -s "$SERIAL" shell dumpsys battery)"
    LEVEL="$(printf '%s\n' "$RAW" | grep -m1 'level:' | awk '{print $2}' || true)"
    TEMP="$(printf '%s\n' "$RAW" | grep -m1 'temperature:' | awk '{print $2}' || true)"
    VOLT="$(printf '%s\n' "$RAW" | grep -m1 'voltage:' | awk '{print $2}' || true)"
    PLUG="$(printf '%s\n' "$RAW" | grep -m1 'status:' | awk '{print $2}' || true)"
    echo "${STAMP},${LEVEL:-NA},${TEMP:-NA},${VOLT:-NA},${PLUG:-NA}" >> "$REPORT"
    adb -s "$SERIAL" shell dumpsys thermalservice > "benchmark-results/${NAME}/thermal_${STAMP}.txt" 2>&1 || true
    sleep 30
done
adb -s "$SERIAL" shell dumpsys batterystats > "benchmark-results/${NAME}/batterystats.txt" || true
adb -s "$SERIAL" logcat -d -v time -s HandDetection:D NpuSubmodel:D GestureService:D LiteRT:D '*:S' > "benchmark-results/${NAME}/inference-logcat.txt" || true
echo "Data: benchmark-results/${NAME}. For real Qualcomm/MediaTek NPU execution obtain vendor HTP/APU profiler trace."
