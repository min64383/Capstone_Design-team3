# 실내 대표점 안정화 1차 — 동률 선택 재현

2026-10-30. 기준 main `42e7cd8ebb1b4c1aa9bb69ea7682fd7a79c7ca3c`, 작업 브랜치 `fix/indoor-representative-stability`. 기하 분석기 PR #86과 독립된 M21 보완 실험이다.

## 확인한 문제와 변경

`RepPoint`의 `minBy`는 거리가 같은 점 중 먼저 입력된 것을 고른다. 복셀은 HashMap에서 넘어오므로 입력 순서는 기하 의미가 없다. 같은 합성 면의 점 순서만 바꿨을 때 앞뒤·좌우·높이가 다른 점이 선택됐다. 기존 코드에 새 회귀 시험을 붙이면 순열, 앞뒤 동률, 통로 밖 fallback, 90도 회전·이동의 4개 시험이 실패했다.

`repPoint.stableTies=true`에서는 기존 1차 거리 기준이 정확히 같을 때만 진행 방향 거리 → 높이 → 부호 있는 좌우로 순서를 정한다. 앞뒤·좌우는 사용자 통로 좌표이며, 거리 허용치나 새 숫자 문턱은 없다. 통로 밖 fallback NEAREST에도 적용한다. 기본 `false`이면 기존 첫 동률 후보를 유지한다.

이 규칙은 안정적인 **선택**을 만들 뿐, 선택한 낮은 높이가 실제 안내에 가장 적합하다는 근거는 아니다. 특히 높이 동률 해소는 낮은 표면 쪽으로 편향된다. 실제 오탐 감소·맵 드리프트 보정·회전 문제 해결을 주장하지 않는다. CENTROID 부동소수점 합산이나 거의 같은 값의 동률 처리도 이번 범위 밖이다.

## 실내 녹화 비교

`testdata/sessions`의 2026-10-24 야외분을 제외한 기존 실내 **13세션, 느린 경로 3,884단계**를 재생했다. 정답 파일이 있는 것은 10세션이다. 기준 재생의 `align.json`을 후보에 고정하고, 가상 처리시간 10 ms를 양쪽에 동일하게 썼다. 이것은 휴대폰의 실측 성능이 아니다. 이번 비교의 설정은 core 기본 JSON이며, 앱의 연속음 override를 자동 합치지 않는다.

| 확인 | 결과 |
|---|---|
| OFF 회귀 | 반복 쌍 재생 11/13세션에서 네 CSV 동일. 2세션은 로그 완전성 불일치로 판정 보류 |
| ON 수평 안내 | 첫 일괄 비교에서는 13/13 guidance.csv 동일. 반복 쌍 재생은 11/13 동일, 나머지는 로그 완전성 불일치로 판정 보류 |
| 정답 10세션 STOP 누락 | 합계 1 → 1, 신규 누락 없음 |
| 기존 누락 | `20260928_084542_S02` 의자 세션, 전후 모두 1건; 의자 좌우·크기 미측정 |
| 나머지 정답 9세션 | 전후 모두 STOP 누락 0 |
| 첫 WARN·STOP 거리, 방향 p95, 오경보 비율, ID 전환 | 계산된 정답 세션 지표 모두 동일 |
| ON 대표점 높이 | 별도 확인; 아래 설명 |

초기 직접 적용 프로토타입 비교에서 CORRIDOR_BAND 높이는 아래쪽으로 바뀌었다(바뀐 행 중앙값 약 −0.05 m, 최솟값 약 −1.39 m). 이후 옵션 연결 재생에서도 높이 변화는 확인했으나, 아래 로그 완전성 문제 때문에 전체 변화 행 수를 확정 수치로 쓰지 않는다. 기존 guidance.csv에는 높이 차·주파수가 없어 이 파일이 같다고 소리가 같다고 판단하면 안 된다. 앱의 DISTANCE_HEIGHT는 대표점 높이를 피치에 쓰므로 **실기기 청취 전 기본값으로 채택하지 않는다**.

### 반복 재생의 로그 완전성 문제

baseline → OFF → ON을 세션별로 순차 재생한 마지막 비교에서도 `20260928_190936_S01`, `20261003_135701_S01`의 로그가 불일치했다. 예를 들어 190936의 기준 재생은 slow_path의 nObstacles 합계 839에 비해 obstacles.csv가 466행이었다(OFF는 839행). 완료 표시와 정상 종료가 있어도 이 비교는 완전한 증거로 채택하지 않는다. 다른 일괄 재생에서는 다른 세션의 행 수 불일치도 관찰됐다. 원인이 실행 환경인지, 기록·읽기 경로인지 확정하지 못했다. 소스 변경의 성능 효과로 해석하지 않는다. 원시 쌍 비교 요약은 [INDOOR_REP_STABILITY_RESULTS.json](INDOOR_REP_STABILITY_RESULTS.json).

실내 개선의 다음 선행 조건은 재생 로그 완전성 확인이다: obstacles 행 수 = slow_path.nObstacles 합계, 느린 경로 단계 수·마지막 시각 일치, 재생 중 경고 없음, 동일 판 재실행 비교. 현재 STOP·방향 지표가 같은 것은 계산된 결과의 관찰일 뿐, 회귀 검증 완료나 성능 통과를 뜻하지 않는다.

## 검증과 재현

정상 개발 환경에서는 다음 순서로 확인한다. baseline과 candidate는 각 코드 판의 별도 checkout에서 실행하고 출력 경로도 분리한다. 동일 세션을 다른 정렬로 다시 맞추지 않는다.

```sh
./gradlew :core:test :viewer:test
python tools/analysis/test_analysis.py

# 기준 main에서 먼저 실행
./gradlew :core:replay -Psession=testdata/sessions/20261003_130815_S01 -Pout=data/indoor/baseline/20261003_130815_S01

# 작업 브랜치: 기본 OFF 회귀와 ON 후보를 각각 실행
./gradlew :core:replay -Psession=testdata/sessions/20261003_130815_S01 -Pout=data/indoor/off/20261003_130815_S01 -PalignFrom=data/indoor/baseline/20261003_130815_S01/align.json
./gradlew :core:replay -Psession=testdata/sessions/20261003_130815_S01 -Pout=data/indoor/on/20261003_130815_S01 -PalignFrom=data/indoor/baseline/20261003_130815_S01/align.json -PoverridesFile=core/src/test/config/indoor-stable-rep.json
python tools/analysis/metrics.py testdata/sessions/20261003_130815_S01 data/indoor/on/20261003_130815_S01
```

이 환경에서는 Gradle 배포판 다운로드가 Network is unreachable로 실패했다. 프로젝트에 의존성을 더하지 않고 레포에 지정된 Kotlin 2.4.20·JUnit Console 6.1.3으로 core·viewer 소스와 시험을 직접 컴파일했다. viewer 리소스를 classpath에 포함하고 기본 설정·HRIR·test-output·coreSrc 시스템 속성은 Gradle과 동일하게 줬다. 실제 Android 앱 빌드·설치·청취는 수행하지 않았다.

최종 소스 전체 시험은 **358개 중 357개 통과, 기존 Linux 경로 시험 1개 실패**였다. 새 시험 6개(대표점 5개·설정 1개)는 모두 통과했다. `python tools/analysis/test_analysis.py`는 합성 출력 생성 후 통과했다.

첫 전체 실행의 viewer 리소스 누락은 리소스를 복사한 뒤 해당 시험을 다시 실행해 해결했다. Linux에서 Windows 역슬래시 경로를 쓰는 기존 `ViewerTest.sessions resolve...` 실패는 기준 core + 같은 viewer 소스로도 재현했다. 이 파일은 이번 작업에서 수정하지 않는다.

## 다음 확인 대상

실내 지도 어긋남은 대표점 순서 문제와 분리해 본다. 고정된 벽 자체가 월드에서 두 겹으로 생기는지, 지도는 맞지만 진행 방향·통로가 늦게 도는지, 군집·대표점만 바뀌는지를 구별한다. 현재 녹화는 새 공간 hold-out 검증이나 체계적인 회전 녹화를 대체하지 않는다. 기존 실내 오탐과 누운 캐리어의 큰 방향 오차는 이번 실험으로 줄지 않았다.
