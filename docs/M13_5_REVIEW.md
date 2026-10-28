# M13.5 기하 분석기 검토 수정 및 부분 재생 결과

기준 main: `f7a0555ef5b9a702b9edcef64faae1b8147ff038`. 작업 브랜치: `fix/m13-5-geometry-review`.
기존 작업 브랜치의 `674ddd6`는 기준 main의 조상이며 기존 코드 변경은 없었다. 최신 main에서 수정했다.

## 구현 범위

IMPROVE_SPEC §6.1.1의 9개 검토 항목 및 OPENING 연속 중복을 수정했다. 알고리즘·설정·로그·Viewer 연결과 합성 회귀 테스트를 추가했다. 실제 사용자 안내 연결, 복셀 감쇠 및 core evidence 옵션 변경은 하지 않았다. 활성화는 `floor.mode=ELEVATION`, 기본은 `SINGLE`이다. 독립 거리 한도 2 m는 검증 전 후보이며 기하 진단에만 적용된다.

## 실제 세션 부분 비교

이 저장소의 `testdata/sessions` 중 2026-10-24 이전 **13개 실내 세션**을 동일한 설정/10 ms 가상 처리 지연으로 재생했다. 이들은 물체가 있는 장면도 포함한다. 이후 48개 야외 세션은 평지 음성 자료로 쓰지 않았다. 과거 **38개 평지 세션의 정확한 manifest 및 원본 전체를 확보하지 못해 그 실험을 재현한 결과가 아니다.**

이전은 기준 main 소스와 해당 버전 default.json + `geometry.enabled=true`, 이후는 수정 코드의 default.json + `floor.mode=ELEVATION`이다. frontend·맵·군집·대표점·안내 설정은 그대로다. 각 버전의 OfflineReplay와 LocalMap에서 직접 계산한 결과를 ReplayListener로 수집했다. 같은 세션·깊이 시각 **3,884회**가 일치하며, primary를 장당 한 번씩 센다. 바닥 미확정으로 이전에 결과가 없던 220회는 NO_FLOOR로 따로 표시한다. 이후는 명시적 UNKNOWN이다.

| primary | 이전 횟수 (전체 비율) | 이후 횟수 (전체 비율) |
|---|---:|---:|
| FLAT | 17 (0.44%) | 0 (0.00%) |
| FLOOR_LOSS | 12 (0.31%) | 0 (0.00%) |
| NARROW_PASSAGE | 310 (7.98%) | 16 (0.41%) |
| NO_FLOOR | 220 (5.66%) | 0 (0.00%) |
| OCCUPIED | 30 (0.77%) | 808 (20.80%) |
| OVERHANG | 2751 (70.83%) | 687 (17.69%) |
| SLOPE_UP | 10 (0.26%) | 0 (0.00%) |
| STAIRS_DOWN | 276 (7.11%) | 46 (1.18%) |
| STAIRS_UP | 6 (0.15%) | 2 (0.05%) |
| STEP_DOWN | 223 (5.74%) | 602 (15.50%) |
| STEP_UP | 24 (0.62%) | 48 (1.24%) |
| UNKNOWN | 5 (0.13%) | 1675 (43.13%) |

| 지면 위험 집계 | 이전 | 이후 |
|---|---:|---:|
| primary가 단차·계단·경사·FLOOR_LOSS | 551 (14.19%) | 698 (17.97%) |
| findings에 하나 이상 지면 위험(장당 1회) | 2539 (65.37%) | 957 (24.64%) |

OVERHANG·NARROW_PASSAGE는 줄었지만 primary 지면 위험 비율은 증가했다. 이는 해당 위험이 findings에 남아 있는 상태에서 더 높은 우선순위 점유 분류가 줄어 primary가 바뀌는 효과도 포함한다. findings의 지면 위험 빈도 감소만으로 전체 정확도 개선을 주장하지 않는다. UNKNOWN 증가와 FLAT 0도 함께 보고하며, 남은 깊이 편향 및 지면 추정 오류를 해결했다고 볼 수 없다. 이 결과로 임계값을 추가 튜닝하지 않았다. 실제 단차·계단·경사의 검출률·누락률은 미검증이다.

## 세션 manifest

| 세션 | 느린 경로 수 |
|---|---:|
| 20260928_084542_S02 | 322 |
| 20260928_101025_S01 | 220 |
| 20260928_102615_S01 | 293 |
| 20260928_190833_S01 | 457 |
| 20260928_190936_S01 | 297 |
| 20261003_130646_S01 | 354 |
| 20261003_130815_S01 | 281 |
| 20261003_130834_S01 | 297 |
| 20261003_131003_S01 | 271 |
| 20261003_131039_S01 | 253 |
| 20261003_135701_S01 | 279 |
| 20261003_135718_S01 | 285 |
| 20261003_135734_S01 | 275 |

## 남은 검증

- 기존 38개 평지 세션의 정확한 목록을 확보해 같은 시각·같은 조건으로 재생 비교.
- 요청했던 기준용 촬영본과 실제 단차/계단/경사 정답 세션 확보. 촬영 지시의 구체 조건은 아직 확인되지 않아 임의로 정하지 않음.
- 기기에서 진단 모드 로그 저장과 처리 시간·기존 STOP 회귀 확인.
- 대표점 문제의 추가 개선은 이번 기하 분석기 수정과 별도다. `CORRIDOR_BAND`와 기존 안내 기본값은 유지한다.

## 실행한 검사

- Kotlin **2.4.20** CLI, JVM target 17로 core + viewer 소스 및 전체 테스트 소스 컴파일 성공(저장소에 고정된 버전 사용, 프로젝트 의존성 추가 없음).
- JUnit Platform Console **6.1.3**으로 전체 core/viewer 테스트 실행: **365개 중 364 통과, 1 실패**. 클래스 경로에 viewer/src/main/resources를 포함하고 Gradle의 hearspace.* 테스트 속성을 동일하게 전달했다. 새 기하 분석·설정·재생·Viewer 테스트는 모두 통과.
- 실패는 기존 `ViewerTest.sessions resolve by id, partial id or repo-relative path and display relative paths`: Linux에서 Windows 역슬래시 경로를 처리하지 못함. **기준 main의 소스에서도 같은 실패를 재현**했다. 이번 범위에서 Sessions 코드는 변경하지 않음.
- `python tools/analysis/test_analysis.py`: **analysis self-check ok**.
- SINGLE/ELEVATION 합성 재생: slow_path, guidance, obstacles, cluster_debug 로그 바이트 일치. 새 로그의 느린 경로 시각 전부 일치 및 SINGLE로 같은 출력 폴더를 다시 재생할 때 오래된 geometry 로그 제거 확인.
- Viewer ELEVATION 재생 기록·과거 시각 조회·화면 렌더링 테스트 통과, 생성 화면에서 primary와 findings 표시 확인.
- `git diff --check` 통과.
- 표준 `./gradlew :core:test :viewer:test`는 이 환경의 배포/플러그인 네트워크 문제로 완료하지 못함. Gradle 배포본을 체크섬 검증 후 실행해도 Android plugin 9.4.0 해석에서 중단됐다. 직접 컴파일·테스트 결과를 Gradle 성공으로 보고하지 않는다. **Android 앱 빌드·실기기 로그/성능은 미검증**. Windows CI에서 표준 명령을 다시 확인해야 한다.
