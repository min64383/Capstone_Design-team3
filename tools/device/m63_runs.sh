#!/usr/bin/env bash
# M63(IMPROVE_SPEC §13 M63, milepost M63 설계 표 8) 기기 측정. 저장소 루트에서 Git Bash로 실행한다. bench 빌드(./gradlew :app:installBench)가
# 깔려 있고, 폰은 USB로 연결해 잠금을 풀어 둔다. 기록은 $OUT/runs.log.
#   STEPS=prep CFG=A|T  실시간 회차 전: 덮어쓰기(A = 없음, T = ToF 끔 depth.sensorUsage DO_NOT_USE)와 온도 확인.
#                       FIRST=1이면 배터리가 MAX_TEMP 넘을 때 식을 때까지 기다린다(1회차). 그 뒤 케이블을 빼고 사용자 모드로 측정
#   STEPS=pull NAME=.. [N=3]  다시 연결한 뒤 가장 최근 실시간 실행(runs/) N개를 오래된 것부터 $OUT/L/<NAME>_1..(N=1이면 <NAME>)로
#                       가져오고 줄 수·온도를 남긴다. 앱은 실행을 20개까지 남기므로 회차마다 가져오지 않아도 된다
#   STEPS=P             기기 재생 104428을 A·C로 돌리며 각 30 s simpleperf 기록(bench에만 profileable) → $OUT/P/
set -u
export MSYS_NO_PATHCONV=1
APP=hearspace.app
ROOT=/sdcard/Android/data/$APP/files
OUT=${OUT:-data/device_runs/m63}
STEPS=${STEPS:-prep}
CFG=${CFG:-A}
NAME=${NAME:-run}
N=${N:-1}
FIRST=${FIRST:-0}
MAX_TEMP=${MAX_TEMP:-310} # dumpsys battery 단위 0.1 °C
P_SESSION=${P_SESSION:-20261030_104428_S01}
P_CFGS=${P_CFGS:-"A C"}
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/runs.log"; }
temp() { adb shell dumpsys battery | grep -m1 temperature | tr -dc 0-9; }
wait_cool() { while [ "$(temp)" -gt "$MAX_TEMP" ]; do log "temp $(temp) > $MAX_TEMP, waiting"; sleep 30; done; }
stop() { adb shell am force-stop $APP; }
set_cfg() {
  case "$1" in
    A) adb shell rm -f $ROOT/config.override.json ;;
    T) adb shell "echo '{\"depth\": {\"sensorUsage\": \"DO_NOT_USE\"}}' > $ROOT/config.override.json" ;;
    C) adb push tools/device/m61_semantic_combo.json $ROOT/config.override.json > /dev/null ;;
    *) log "unknown config $1"; exit 1 ;;
  esac
}

if adb shell dumpsys package $APP | grep -m1 "pkgFlags=" | grep -q DEBUGGABLE; then log "debug build installed: install bench first (./gradlew :app:installBench)"; exit 1; fi
if [ "$(adb shell dumpsys window | grep -c 'isKeyguardShowing=true')" -gt 0 ]; then log "phone is locked: unlock it first"; exit 1; fi

if [[ " $STEPS " == *" prep "* ]]; then
  stop
  [ "$FIRST" = 1 ] && wait_cool
  set_cfg "$CFG"
  adb logcat -c
  log "prep $CFG: temp $(temp). Unplug, open 사용자 모드 and run; plug in after, then STEPS=pull NAME=<name>"
  exit 0
fi

if [[ " $STEPS " == *" pull "* ]]; then
  mkdir -p "$OUT/L"
  i=0
  for r in $(adb shell ls -t $ROOT/runs | head -"$N" | tr -d '\r' | tac); do
    i=$((i + 1))
    dst="$OUT/L/$NAME"; [ "$N" -gt 1 ] && dst="${dst}_$i"
    if [ -e "$dst" ]; then log "$dst exists: choose another NAME"; exit 1; fi
    if adb pull $ROOT/runs/$r "$dst" > /dev/null; then
      log "pull $(basename "$dst") <- runs/$r, lines device $(wc -l < "$dst/device.csv") slow_path $(wc -l < "$dst/slow_path.csv")"
    else
      log "pull FAILED $(basename "$dst") <- runs/$r"
    fi
  done
  adb logcat -d -s HEARSPACE > "$OUT/L/${NAME}_logcat.txt"
  log "pull done, temp $(temp)"
fi

if [[ " $STEPS " == *" P "* ]]; then
  mkdir -p "$OUT/P"
  adb shell svc power stayon usb
  for c in $P_CFGS; do
    wait_cool
    stop
    set_cfg "$c"
    adb logcat -c
    t0=$(temp)
    adb shell am start -n $APP/.ui.dev.DevHomeActivity --es autoStart "$P_SESSION" > /dev/null
    sleep 5
    adb shell simpleperf record --app $APP -g --duration 30 -o /data/local/tmp/m63_$c.data > "$OUT/P/record_$c.txt" 2>&1
    for _ in $(seq 120); do adb logcat -d -s HEARSPACE | grep -q "run log closed" && break; sleep 1; done
    stop
    latest=$(adb shell ls -t $ROOT/sessions/$P_SESSION/run_log | head -1 | tr -d '\r')
    adb pull $ROOT/sessions/$P_SESSION/run_log/$latest "$OUT/P/$c" > /dev/null
    adb logcat -d -s HEARSPACE > "$OUT/P/$c/logcat.txt"
    adb pull /data/local/tmp/m63_$c.data "$OUT/P/" > /dev/null
    # 스레드별, 느린 경로 스레드의 함수별(자기 시간·자식 포함) 보고
    adb shell simpleperf report -i /data/local/tmp/m63_$c.data --sort comm -n > "$OUT/P/report_${c}_threads.txt" 2>&1
    adb shell simpleperf report -i /data/local/tmp/m63_$c.data --comms SlowPath --sort symbol -n --percent-limit 0.3 > "$OUT/P/report_${c}_self.txt" 2>&1
    adb shell simpleperf report -i /data/local/tmp/m63_$c.data --comms SlowPath --children --sort symbol -n --percent-limit 1 > "$OUT/P/report_${c}_children.txt" 2>&1
    log "P $c temp $t0 -> $(temp), run_log $latest"
  done
  adb shell rm -f $ROOT/config.override.json
  adb shell svc power stayon false
  log "P done (device override removed)"
fi
