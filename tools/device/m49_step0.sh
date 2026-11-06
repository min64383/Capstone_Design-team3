#!/usr/bin/env bash
# M49 0단계(IMPROVE_SPEC §13 M49, 설계 표 5) 기기 자동화. 저장소 루트에서 Git Bash로 실행한다. 폰은 USB로 연결하고 잠금을 풀어 둔다
# (`svc power stayon usb`로 연결 중에는 화면이 꺼지지 않게 한다). 디버그 빌드의 DevHomeActivity `autoStart` 값으로 화면을 연다.
#   0b: T 세션을 재생하며 의미 영상을 세션 폴더 semantic/에 저장하고 PC data/sessions/<세션>/semantic/으로 가져온다.
#   0c: 실내 실시간 안내를 의미 모드 끔 5분 → 31 °C 이하로 식힘 → 켬 5분 돌리고 실행 로그를 가져온다.
# 확인용 H 세션은 넣지 않는다. 기록: $OUT/step0.log
#   bash tools/device/m49_step0.sh            # 0b와 0c
#   STEPS=0b bash tools/device/m49_step0.sh   # 0b만
set -u
export MSYS_NO_PATHCONV=1
APP=hearspace.app
ROOT=/sdcard/Android/data/$APP/files
OUT=${OUT:-data/device_runs/m49}
STEPS=${STEPS:-"0b 0c"}
RUN_S=${RUN_S:-300}
MAX_TEMP=${MAX_TEMP:-310} # dumpsys battery 단위 0.1 °C
SESSIONS=${SESSIONS:-"20261102_150159_S01 20261102_150300_S01 20261102_150338_S01 20261102_150439_S01 20261102_150457_S01 20261102_150957_S01 20261102_151045_S01"}
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/step0.log"; }
temp() { adb shell dumpsys battery | grep -m1 temperature | tr -dc 0-9; }
wait_cool() { while [ "$(temp)" -gt "$MAX_TEMP" ]; do log "temp $(temp) > $MAX_TEMP, waiting"; sleep 30; done; }
start() { adb logcat -c; adb shell am start -n $APP/.ui.dev.DevHomeActivity --es autoStart "$1" > /dev/null; }
stop() { adb shell am force-stop $APP; }
resumed() { adb shell dumpsys activity top | grep -q "mResumed=true"; }

adb shell svc power stayon usb
if [ "$(adb shell dumpsys window | grep -c 'isKeyguardShowing=true')" -gt 0 ]; then log "phone is locked: unlock it first"; exit 1; fi

if [[ " $STEPS " == *" 0b "* ]]; then
  adb push tools/device/m49_semantics.json $ROOT/config.override.json > /dev/null
  for s in $SESSIONS; do
    stop
    start "$s"
    sleep 5
    resumed || log "WARN $s: screen not resumed (locked?)"
    for _ in $(seq 240); do adb logcat -d -s HEARSPACE | grep -q "semantic saved" && break; sleep 1; done
    adb logcat -d -s HEARSPACE | grep -E "semantic mode|semantic saved|playback|semantic write" | sed "s/^/  $s /" | tee -a "$OUT/step0.log"
    stop
    if adb pull $ROOT/sessions/$s/semantic data/sessions/$s/ > /dev/null; then log "0b pulled $s"; else log "0b FAILED pull $s"; fi
  done
fi

if [[ " $STEPS " == *" 0c "* ]]; then
  wait_cool
  adb shell rm -f $ROOT/config.override.json
  stop
  start live
  log "0c OFF start temp $(temp)"
  sleep 5
  resumed || log "WARN 0c OFF: screen not resumed"
  sleep "$RUN_S"
  log "0c OFF end temp $(temp)"
  stop
  wait_cool
  adb push tools/device/m49_semantics.json $ROOT/config.override.json > /dev/null
  start live
  log "0c ON start temp $(temp)"
  sleep 5
  resumed || log "WARN 0c ON: screen not resumed"
  adb logcat -d -s HEARSPACE | grep "semantic mode" | tee -a "$OUT/step0.log"
  sleep "$RUN_S"
  log "0c ON end temp $(temp)"
  stop
  adb pull $ROOT/runs "$OUT/" > /dev/null && log "0c pulled runs"
fi
adb shell svc power stayon false
log "done"
