#!/usr/bin/env bash
# M65 실험 E1(TTS 시작 지연)·E3(감쇠) 자동화. 저장소 루트에서 Git Bash로, bench 빌드를 설치한 뒤 실행한다. 폰은 USB 연결·잠금 해제, 카메라가 무늬 있는 곳을 보게 세워 둔다.
# 사용자 모드를 `--ei ttsBench <되풀이>`로 열면 안내(실시간 ARCore)를 켠 채 고정 문구 41개를 차례로 말하고, 실행 폴더 tts.csv(kind BENCH)에 speak → onStart를 남긴다.
# 도는 동안 2 s마다 `dumpsys audio`(포커스 스택·줄어든 재생기)를 저장한다(E3).
#   LABEL=off REPS=5 bash tools/device/m65_tts.sh      # TalkBack 끔
#   LABEL=talkback REPS=1 bash tools/device/m65_tts.sh # 사용자가 TalkBack을 켠 뒤(문구마다 화면 알림으로 TalkBack이 먼저 말함)
set -u
export MSYS_NO_PATHCONV=1
APP=hearspace.app
ROOT=/sdcard/Android/data/$APP/files
OUT=${OUT:-data/device_runs/m65/E1}
LABEL=${LABEL:-off}
REPS=${REPS:-5}
MAX_S=${MAX_S:-900}
dst="$OUT/$LABEL"
mkdir -p "$dst/dumpsys"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/runs.log"; }
temp() { adb shell dumpsys battery | grep -m1 temperature | tr -dc 0-9; }

if [ "$(adb shell dumpsys window | grep -c 'isKeyguardShowing=true')" -gt 0 ]; then log "phone is locked: unlock it first"; exit 1; fi
adb shell svc power stayon usb
adb shell am force-stop $APP
adb shell rm -f $ROOT/config.override.json
adb logcat -c
t0=$(temp)
adb shell am start -n $APP/.ui.user.UserModeActivity --ei ttsBench "$REPS" > /dev/null
i=0
for _ in $(seq $((MAX_S / 2))); do
  i=$((i + 1))
  adb shell dumpsys audio > "$dst/dumpsys/$(printf %04d $i).txt"
  adb logcat -d -s HEARSPACE | grep -q "tts bench done" && break
  sleep 2
done
sleep 3
t1=$(temp)
adb logcat -d -s HEARSPACE > "$dst/logcat.txt"
latest=$(adb shell ls -t $ROOT/runs | head -1 | tr -d '\r')
if [ -n "$latest" ] && adb pull $ROOT/runs/$latest "$dst/run" > /dev/null; then log "E1 $LABEL reps $REPS temp $t0 -> $t1, runs/$latest"; else log "E1 FAILED $LABEL"; fi
adb shell am force-stop $APP
adb shell svc power stayon false
