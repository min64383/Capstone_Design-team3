#!/bin/sh
# M51 재생(명세 §13 M51 v0.4.48): sh tools/analysis/m51/run.sh <세션 목록> <출력 뿌리> <설정 이름>...
#   설정 = 이 폴더의 ov_<이름>.json(기준선 base, 앱 a, a_chest, 조합 c, c_chest). 출력 <뿌리>/<설정>/<세션>/, 하네스 녹화는 event_eval.py까지.
#   c·c_chest는 순환 확인용 기록(빈 곳 위반 표는 설정 안 track.freeSpaceRecord, 칸 라벨 덤프는 labelDump=true)을 켠다 — 안내 출력은 같다(M57).
#   H 세션 목록 sess_h.txt는 확인용이라 이 스크립트와 설정 파일을 커밋한 뒤 한 번만 돌린다.
# Gradle 시작을 건너뛰려고 :core:replay와 같은 JVM 설정으로 바로 부른다(data/sweeps/m37/rp.sh와 같음).
set -e
R=$(cd "$(dirname "$0")/../../.." && pwd)
LIST=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); mkdir -p "$2"; ROOT=$(cd "$2" && pwd); shift 2
cd "$R" && ./gradlew :core:classes -q
STDLIB=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar | head -1)
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; STDLIB=$(cygpath -m "$STDLIB"); R=$(cygpath -m "$R");; esac
CP="$R/core/build/classes/kotlin/main$SEP$R/core/build/resources/main$SEP$STDLIB"
GH=$(git -C "$R" describe --always --dirty --exclude="*")
for V in "$@"; do
  extra=""; case $V in c*) extra="labelDump=true";; esac
  for id in $(cat "$LIST"); do
    s=$R/data/sessions/$id; [ -d "$s" ] || s=$R/testdata/sessions/$id
    out=$ROOT/$V/$id; mkdir -p "$out"
    if [ ! -f "$out/replay_done" ]; then
      java -cp "$CP" -Dhearspace.defaultConfig="$R/app/src/main/assets/config/default.json" -Dhearspace.replayOut="$R/core/build/replay" \
        -Dhearspace.gitHash="$GH" hearspace.core.replay.OfflineReplayMainKt out="$out" session="$s" overridesFile="$R/tools/analysis/m51/ov_$V.json" $extra \
        > "$out/replay.log" 2>&1 || echo "FAIL $V $id"
    fi
    [ -f "$s/annotations/events.json" ] && ( cd "$R/tools/analysis" && PYTHONIOENCODING=utf-8 python event_eval.py "$s" "$out" > "$out/event_eval.txt" 2>&1 || echo "EVAL FAIL $V $id" )
  done
  echo "$V done"
done
