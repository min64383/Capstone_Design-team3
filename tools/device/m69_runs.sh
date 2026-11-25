#!/usr/bin/env bash
# M69 기기 처리 시간: T01 기기 재생을 지금 앱 기본(S)과 원래 크기 노면 입력 + 새 블록 판단(N, m69_native_arms.json)으로 한 번씩.
# 설정(기기 덮어쓰기): A = 상황 판단 끔(m65_situation_off.json, M65 전과 같은 안내), S = 앱 기본값(상황 판단·노면 모델 켬),
# V = S + 첫 60 s 노면 영상 저장(m65_surface_save.json, 기기 라벨 대 PC 라벨 비교용, 시간 판정에는 쓰지 않음).
#   push: T01 세션(arcore.mp4·frames.csv·meta.json)을 기기에 올린다(약 950 MB)
#   P: 기기 재생. 세션마다 $P_CFGS(기본 "A S"), T01은 $T01_CFGS(기본 "A S V") → data/device_runs/m65/P/<세션>_<순번><설정>/
# 기록: $OUT/runs.log, 실행마다 logcat(HEARSPACE) 사본. 끝나면 기기 덮어쓰기를 지운다.
#   bash tools/device/m65_runs.sh               # push와 P
#   STEPS=P P_SESSIONS="20260928_190833_S01" bash tools/device/m65_runs.sh
set -u
export MSYS_NO_PATHCONV=1
APP=hearspace.app
ROOT=/sdcard/Android/data/$APP/files
OUT=${OUT:-data/device_runs/m69}
STEPS=${STEPS:-"P"}
MAX_TEMP=${MAX_TEMP:-310} # dumpsys battery 단위 0.1 °C
T01=20260928_085205_T01
P_SESSIONS=${P_SESSIONS:-"$T01"}
P_CFGS=${P_CFGS:-"A S"}
T01_CFGS=${T01_CFGS:-"S N"}
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/runs.log"; }
temp() { adb shell dumpsys battery | grep -m1 temperature | tr -dc 0-9; }
wait_cool() { while [ "$(temp)" -gt "$MAX_TEMP" ]; do log "temp $(temp) > $MAX_TEMP, waiting"; sleep 30; done; }
stop() { adb shell am force-stop $APP; }
set_cfg() {
  case "$1" in
    A) adb push tools/device/m65_situation_off.json $ROOT/config.override.json > /dev/null ;;
    S) adb shell rm -f $ROOT/config.override.json ;;
    N) adb push tools/device/m69_native_arms.json $ROOT/config.override.json > /dev/null ;;
    *) log "unknown config $1"; exit 1 ;;
  esac
}
# $1 세션. 재생이 끝나 실행 로그가 닫힐 때까지 기다린다(최대 $2 초)
play() {
  adb logcat -c
  adb shell am start -n $APP/.ui.dev.DevHomeActivity --es autoStart "$1" > /dev/null
  for _ in $(seq "$2"); do adb logcat -d -s HEARSPACE | grep -q "run log closed" && return 0; sleep 1; done
  log "WARN $1: run log not closed in $2 s"
  return 1
}

adb shell svc power stayon usb
if [ "$(adb shell dumpsys window | grep -c 'isKeyguardShowing=true')" -gt 0 ]; then log "phone is locked: unlock it first"; exit 1; fi

if [[ " $STEPS " == *" push "* ]]; then
  adb shell mkdir -p $ROOT/sessions/$T01
  for f in arcore.mp4 frames.csv meta.json; do
    if adb push data/sessions/$T01/$f $ROOT/sessions/$T01/$f > /dev/null; then log "push $T01/$f"; else log "FAILED push $T01/$f"; exit 1; fi
  done
fi

if [[ " $STEPS " == *" P "* ]]; then
  mkdir -p "$OUT/P"
  for s in $P_SESSIONS; do
    cfgs=$P_CFGS
    [ "$s" = "$T01" ] && cfgs=$T01_CFGS
    n=0
    for c in $cfgs; do
      n=$((n + 1))
      wait_cool
      stop
      set_cfg "$c"
      t0=$(temp)
      play "$s" 900
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

adb shell svc power stayon false
adb shell rm -f $ROOT/config.override.json
log "done (device override removed)"
