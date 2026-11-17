#!/usr/bin/env bash
# M61(IMPROVE_SPEC §13 M61, milepost M61 설계 표 6) 기기 자동화. 저장소 루트에서 Git Bash로 실행한다. 폰은 USB로 연결하고 잠금을 풀어 둔다.
# 디버그 빌드의 DevHomeActivity `autoStart`로 재생 화면을 연다. 설정: A = 덮어쓰기 없음(기본값), B = 의미 모드만(m49_semantics.json),
# C = 조합(m61_semantic_combo.json). 실행마다 덮어쓰기를 쓰고, 끝나면 지운다(기기에 남은 덮어쓰기가 디버그 빌드 기본값을 바꾸지 않게).
#   S: 의미 영상 저장(--ez saveSemantic true) → PC data/sessions/<세션>/semantic/
#   P: 기기 재생, 세션마다 설정 순서 $P_CFGS(기본 "A B C A"), 150159는 "A B C" → data/device_runs/m61/P/<세션>_<순번><설정>/
#   Lprep CFG=A|C: 실시간 걷기 전 덮어쓰기만 쓴다(케이블을 빼고 개발 화면 "실시간"으로 약 1분 걷기)
#   Lpull CFG=A|C: 다시 연결한 뒤 가장 최근 실시간 실행 로그를 data/device_runs/m61/L/<설정>/로
# 기록: $OUT/runs.log, 실행마다 logcat(HEARSPACE) 사본.
#   bash tools/device/m61_runs.sh                 # S와 P
#   STEPS=P bash tools/device/m61_runs.sh
#   STEPS=Lprep CFG=C bash tools/device/m61_runs.sh
set -u
export MSYS_NO_PATHCONV=1
APP=hearspace.app
ROOT=/sdcard/Android/data/$APP/files
OUT=${OUT:-data/device_runs/m61}
STEPS=${STEPS:-"S P"}
MAX_TEMP=${MAX_TEMP:-310} # dumpsys battery 단위 0.1 °C
S_SESSIONS=${S_SESSIONS:-"20261030_104428_S01 20261102_150231_S01 20261102_150317_S01 20261102_150357_S01 20261102_150515_S01 20261102_151019_S01 20261102_151108_S01"}
P_SESSIONS=${P_SESSIONS:-"20261030_104428_S01 20261116_145335_S01 20261116_145348_S01 20261102_150159_S01"}
P_CFGS=${P_CFGS:-"A B C A"}
P_CFGS_SHORT=${P_CFGS_SHORT:-"A B C"} # 150159
CFG=${CFG:-A}
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/runs.log"; }
temp() { adb shell dumpsys battery | grep -m1 temperature | tr -dc 0-9; }
wait_cool() { while [ "$(temp)" -gt "$MAX_TEMP" ]; do log "temp $(temp) > $MAX_TEMP, waiting"; sleep 30; done; }
stop() { adb shell am force-stop $APP; }
set_cfg() {
  case "$1" in
    A) adb shell rm -f $ROOT/config.override.json ;;
    B) adb push tools/device/m49_semantics.json $ROOT/config.override.json > /dev/null ;;
    C) adb push tools/device/m61_semantic_combo.json $ROOT/config.override.json > /dev/null ;;
    *) log "unknown config $1"; exit 1 ;;
  esac
}
# $1 세션, $2 추가 인자. 재생이 끝나 실행 로그가 닫힐 때까지 기다린다(최대 $3 초)
play() {
  adb logcat -c
  adb shell am start -n $APP/.ui.dev.DevHomeActivity --es autoStart "$1" $2 > /dev/null
  for _ in $(seq "$3"); do adb logcat -d -s HEARSPACE | grep -q "run log closed" && return 0; sleep 1; done
  log "WARN $1: run log not closed in $3 s"
  return 1
}

adb shell svc power stayon usb
if [ "$(adb shell dumpsys window | grep -c 'isKeyguardShowing=true')" -gt 0 ]; then log "phone is locked: unlock it first"; exit 1; fi

if [[ " $STEPS " == *" S "* ]]; then
  set_cfg B
  for s in $S_SESSIONS; do
    stop
    adb logcat -c
    adb shell am start -n $APP/.ui.dev.DevHomeActivity --es autoStart "$s" --ez saveSemantic true > /dev/null
    for _ in $(seq 240); do adb logcat -d -s HEARSPACE | grep -q "semantic saved" && break; sleep 1; done
    adb logcat -d -s HEARSPACE | grep -E "semantic mode|semantic saved|semantic write" | sed "s/^/  $s /" | tee -a "$OUT/runs.log"
    stop
    if adb pull $ROOT/sessions/$s/semantic data/sessions/$s/ > /dev/null; then log "S pulled $s"; else log "S FAILED pull $s"; fi
  done
fi

if [[ " $STEPS " == *" P "* ]]; then
  mkdir -p "$OUT/P"
  for s in $P_SESSIONS; do
    cfgs=$P_CFGS
    [ "$s" = "20261102_150159_S01" ] && cfgs=$P_CFGS_SHORT
    n=0
    for c in $cfgs; do
      n=$((n + 1))
      wait_cool
      stop
      set_cfg "$c"
      t0=$(temp)
      play "$s" "" 300
      t1=$(temp)
      stop
      dst="$OUT/P/${s}_${n}${c}"
      latest=$(adb shell ls -t $ROOT/sessions/$s/run_log | head -1 | tr -d '\r')
      if [ -n "$latest" ] && adb pull $ROOT/sessions/$s/run_log/$latest "$dst" > /dev/null; then
        adb logcat -d -s HEARSPACE > "$dst/logcat.txt"
        log "P $s $n$c temp $t0 -> $t1, run_log $latest"
      else
        log "P FAILED $s $n$c"
      fi
    done
  done
fi

if [[ " $STEPS " == *" Lprep "* ]]; then
  stop
  set_cfg "$CFG"
  adb logcat -c
  log "Lprep $CFG: override set, temp $(temp). Unplug, open 실시간 on the dev screen, walk about 1 min, go back, plug in, then STEPS=Lpull CFG=$CFG"
fi

if [[ " $STEPS " == *" Lpull "* ]]; then
  mkdir -p "$OUT/L"
  latest=$(adb shell ls -t $ROOT/runs | head -1 | tr -d '\r')
  dst="$OUT/L/$CFG"
  if [ -n "$latest" ] && adb pull $ROOT/runs/$latest "$dst" > /dev/null; then
    adb logcat -d -s HEARSPACE > "$dst/logcat.txt"
    log "L $CFG pulled runs/$latest, temp $(temp)"
  else
    log "L FAILED $CFG"
  fi
fi

adb shell svc power stayon false
if [[ " $STEPS " == *" Lprep "* ]]; then log "done (override $CFG kept for the walk)"; exit 0; fi
adb shell rm -f $ROOT/config.override.json
log "done (device override removed)"
