package hearspace.core.types

import hearspace.core.geometry.Vec3

// 설정 (MVP_SPEC §12). 기본값은 코드에 두지 않는다. 유일한 원본은 app/src/main/assets/config/default.json이다.

/** 전체 설정. [ConfigLoader]로만 만든다. */
data class Config(
    val head: HeadConfig,
    val heading: HeadingConfig,
    val corridor: CorridorConfig,
    val depth: DepthConfig,
    val frontend: FrontendConfig,
    val map: MapConfig,
    val floor: FloorConfig,
    val cluster: ClusterConfig,
    val falsePositiveFilter: FalsePositiveFilterConfig,
    val track: TrackConfig,
    val repPoint: RepPointConfig,
    val policy: PolicyConfig,
    val state: StateConfig,
    val audio: AudioConfig,
    val haptics: HapticsConfig,
    val record: RecordConfig,
    val align: AlignConfig,
    val sonify: SonifyConfig,
)

/** 머리 좌표계 설정. */
data class HeadConfig(
    /** 카메라 → 머리 원점 오프셋(월드 수평 기준, 미터). 기준 파지에서 실측. */
    val offsetFromCameraM: Vec3,
)

/** 진행 방향 추정 설정. */
data class HeadingConfig(val windowS: Float, val minTravelM: Float)

/** 통로 치수와 가장자리 구조물 판정. */
data class CorridorConfig(
    val widthM: Float,
    val heightM: Float,
    val lengthM: Float,
    val behindM: Float,
    /** 물체의 모든 점이 |좌우| ≥ 이 값인 한쪽 가장자리에 있고 */
    val edgeInnerM: Float,
    /** 진행 방향으로 이 길이 이상 이어지면 나란한 가장자리 구조물(벽·담장·난간)로 보고 안내하지 않는다(v0.2.5). */
    val edgeMinLengthM: Float,
)

/** 깊이 입력·역투영 설정. */
data class DepthConfig(
    /** 역투영 픽셀 간격(1 = 모든 픽셀). */
    val subsample: Int,
    /** 느린 경로 입력 깊이 종류(F6, M3 비교로 결정). */
    val source: DepthSource,
    /** 원시 깊이 신뢰도(0~255)가 이보다 낮은 픽셀은 무효로 본다. 일반 깊이에는 적용하지 않는다. */
    val minConfidence: Int,
)

/**
 * 깊이 영상 앞단(IMPROVE_SPEC §6.1.1 M13.1). ① 경계 판정: 주사선을 선형 조각으로 나눠, 긴 조각 둘 사이를 짧은 경사로 잇는
 * 픽셀(막)을 지도·바닥 추정에 넣지 않는다. [enabled]가 false면 기준선(바이트 동일).
 */
data class FrontendConfig(
    val enabled: Boolean,
    /** 경계로 볼 경사의 최대 길이(픽셀). 이보다 긴 조각은 실제 표면(비스듬한 벽 등)으로 본다. M12.0 실측 막 경사 p90 3~5픽셀. */
    val edgeMaxRampPx: Int,
    /** 경사 양쪽 표면의 맞닿는 끝 깊이 차가 가까운 쪽 깊이의 이 비율을 넘어야 불연속(작은 단차는 경계가 아님). */
    val edgeMinStepRatio: Float,
    /** 픽셀당 깊이 변화가 깊이의 이 비율을 넘는 조각은 경사(막) 후보, 아니면 표면(M13.1a 실측: 막 9~25%, 뒤 배경 끌림 1~3%). */
    val edgeSteepRatio: Float,
    /** 선형 조각 맞춤 허용 오차(깊이에 대한 비율). 양쪽 조각 직선에서 이만큼 안인 경사 픽셀은 표면 끝으로 보고 남긴다. */
    val edgeFitTolRatio: Float,
    /** 수평면 예외(다가가며 보이는 상자 윗면, SC-23): 경사 픽셀들의 높이(중력 방향) 변화가 깊이 변화의 이 배 이하. */
    val levelMaxSlope: Float,
    /** 수평면 예외의 높이 잡음 허용(m). */
    val levelTolM: Float,
    /** 수평면 예외는 카메라보다 이만큼 이상 낮을 때만(카메라 높이의 막은 높이가 같아 보이므로, m). */
    val levelMinBelowCameraM: Float,
    /** ③ 평면 추출(M13.1c). NONE = 안 함(기준선). 아래 값은 RANSAC에서만 쓴다. */
    val planes: PlaneMode,
    /** 평면 허용 오차 = planeTolM + planeTolPerM × 카메라에서 수평거리(깊이 오차가 거리에 비례, m). */
    val planeTolM: Float,
    val planeTolPerM: Float,
    /** 바닥 허용 오차의 거리 비례분(m/m): 평활 깊이의 바닥은 곡선이라 벽보다 크다(M3 실측 0.08, `floor.tolerancePerM`과 같은 값). */
    val floorTolPerM: Float,
    /** 바닥 후보는 카메라보다 이만큼 이상 낮은 점만(m). 가까운 물체 윗면·카메라 앞 잡음 제외. */
    val planeFloorMinBelowM: Float,
    /** 바닥에 필요한 점 수. */
    val floorMinPoints: Int,
    /**
     * 바닥 점의 수평 퍼짐(작은 주축 표준편차) 하한(m). 수직 벽의 점은 수평으로 벽 두께만큼(평활 깊이 잡음 포함 0.05 m 안팎)만
     * 퍼지므로 이보다 작다. 바닥이 시야의 좁은 띠(가까운 1~2 m)에만 보여도(퍼짐 0.25 안팎) 통과해야 한다.
     */
    val floorMinSpreadM: Float,
    /** 바닥 점의 거리 방향 폭(5~95%) 하한(m). 작은 물체 윗면은 이보다 좁다. */
    val floorMinSpanM: Float,
    /** 바닥 추정에 쓰는 점의 수평거리 상한(m). 평활 깊이의 먼 바닥은 비선형으로 위로 휘어 쓰지 않는다. */
    val floorMaxDistM: Float,
    /** 바닥 기울기 하한(m/m, 음수 = 잡음 허용: 깊이 잡음으로 완전한 평면의 기울기가 0 아래로 조금 나온다). */
    val floorMinLiftPerM: Float,
    /** 거리에 따라 들리는 바닥 기울기 상한(m/m). 평활 깊이의 바닥은 멀수록 위로 들려 보인다(M3 실측 약 0.08). */
    val floorMaxLiftPerM: Float,
    /** 바닥 아래 검사: 바닥 선보다 이만큼(m) 넘게 아래인 후보 점이 바닥 점 수의 이 비율을 넘으면 바닥이 아니다(벽 띠·물체 면). */
    val floorUnderM: Float,
    val floorUnderFraction: Float,
    /** 바닥은 카메라보다 이 범위(m)만큼 아래(손에 든 폰 높이의 합리적 범위). */
    val floorMinDropM: Float,
    val floorMaxDropM: Float,
    /** 벽 후보 점의 높이 띠: 카메라보다 이만큼 아래부터(m), 이만큼 위까지(m). 바닥·천장을 뺀다. */
    val wallBandBelowM: Float,
    val wallBandAboveM: Float,
    /** 벽 직선의 최소 점 수·길이(m)·높이 범위(m), 한 장에서 찾을 최대 개수, RANSAC 시도 횟수. */
    val wallMinPoints: Int,
    val wallMinLengthM: Float,
    /**
     * 벽 직선 위 점을 따라가며 간격이 이보다 벌어지면 끊고 가장 긴 연속 구간만 벽으로 본다(m). 연속성 검사가 없으면 상자 앞면과 양쪽 옆 벽의
     * 같은 거리 점이 한 직선으로 이어져 가짜 벽이 됐다(합성 C2 장면). 처리 해상도(`depth.subsample` 2)의 이웃 점 간격은 반경 5 m에서
     * 약 0.08 m라 그 두 배 남짓.
     */
    val wallMaxGapM: Float,
    val wallMinHeightM: Float,
    val wallMaxPlanes: Int,
    val planeIterations: Int,
)

/** 로컬 복셀 맵 설정. */
data class MapConfig(
    val voxelSizeM: Float,
    /** 관측 1회(깊이 1장)당 score 증가량. */
    val hitGain: Float,
    val minHits: Int,
    val minScore: Float,
    val freeMarginM: Float,
    val decayPerObservation: Float,
    val passedMarginM: Float,
    val maxUnseenS: Float,
    val radiusM: Float,
    /** 갱신 규칙. 아래 log·weight·freeMarginRatio 값은 [MapMode.LOG_ODDS]에서만 쓴다(HITS는 위 값만, 기준선 바이트 동일). */
    val mode: MapMode,
    /** 점이 든 칸의 로그 오즈 증가. 기본값은 OctoMap(Hornung 2013) 적중 확률 0.7 = ln(0.7/0.3). */
    val logHit: Float,
    /** 시야 안에서 칸을 지나 더 멀리 보였을 때 증가(음수). OctoMap 빈 칸 확률 0.4 = ln(0.4/0.6). */
    val logMiss: Float,
    /** 로그 오즈 하·상한(OctoMap 0.12·0.97). 상한이 있어 오래 쌓인 칸도 바뀐 관측에 다시 반응한다. 하한에 닿은 칸은 지운다. */
    val logMin: Float,
    val logMax: Float,
    /**
     * 이 값 이상이면 점유. OctoMap 기본(확률 0.5 = 0)은 한 번 맞은 칸도 점유라 상자 둘레의 한 번짜리 점이 대표점을 밀고(SC-09)
     * 물체를 가르고(SC-23) 막을 남겼다(SC-21). 기존 SC를 모두 통과하는 가장 낮은 후보 1.7(약 두 번 연속 관측, 확률 0.85)을 쓴다.
     * 실제 세션이 아니라 합성 장면만으로 골랐다(M13.2, 과적합 방지).
     */
    val logOccupied: Float,
    /** 관측 가중치 = min(1, (weightRefM / 거리)²): 깊이 오차가 거리 제곱으로 커지므로 먼 관측일수록 약하게(m). */
    val weightRefM: Float,
    /** 빈 칸 판정 여유 = max(복셀 크기, 이 비율 × 칸까지 깊이). 고정 여유(`freeMarginM`) 대신 깊이 오차에 비례. */
    val freeMarginRatio: Float,
    /** HITS의 표 세기([HitWeighting]). DISTANCE면 점유 문턱 `minHits`를 표의 가중합에 적용하고 score 증가에도 같은 가중치를 곱한다. */
    val hitWeighting: HitWeighting,
    /**
     * TSDF(M13.6) 잘림 폭 = max(`tsdfTruncMinM`, `tsdfTruncPerM` × 칸 깊이)(m). 최소는 복셀 두 칸(Voxblox 기본 2~4칸), 거리 비례는 조정용
     * 세션의 깊이 한 장 앞면 퍼짐(캐리어 앞면 p90 − p10 0.06~0.17 m @ 1.5~2.5 m, σ 약 0.02~0.03 × 깊이)의 약 2σ.
     */
    val tsdfTruncMinM: Float,
    val tsdfTruncPerM: Float,
    /**
     * TSDF 가중치 상한. 평균은 최근 관측 이만큼까지만 기억하고, 확실한 빈 곳 관측은 가중치를 `decayPerObservation` / `hitGain`(HITS의
     * 감쇠·증가 비율, 1.5)씩 깎아 0이면 지운다. `minHits`의 두 배(12): 치운 물체가 약 8장(0.27 s) 만에 지워진다(SC-04, HITS는 4장).
     */
    val tsdfMaxWeight: Float,
    /**
     * TSDF 거리 보정의 cos(입사각) 하한. 시선 방향 거리(측정 깊이 − 칸 깊이)를 깊이 영상의 이웃 픽셀로 구한 면 법선과 시선의 cos로 곱해
     * 면에 수직인 거리로 바꾼다(점–평면 거리). 보정 없이는 비스듬한 면(옆 벽·머리 높이 판 아래면)의 칸이 1/cos배 멀게 재져 지워졌다
     * (SC-03·SC-06·SC-22). 하한 0.1 = 복도 반폭 0.5 m를 반경 5 m에서 보는 각(sin ≈ 0.1).
     */
    val tsdfMinCosIncidence: Float,
)

/** 바닥 추정 설정. */
data class FloorConfig(
    /** 바닥을 한 번 찾은 뒤에는 직전 바닥 ± 이 범위에서만 찾는다(첫 추정은 카메라보다 낮은 점 전체). */
    val searchBandM: Float,
    /** 바닥 점 판정: |y − floorY| < toleranceM + tolerancePerM × 수평거리. */
    val toleranceM: Float,
    /** 수평거리 1 m당 바닥 허용 오차 증가(실제 깊이는 멀수록 바닥이 위로 퍼져 보인다, M3 실측). */
    val tolerancePerM: Float,
    /** 높이 히스토그램 칸 크기. */
    val binM: Float,
    /** 바닥 높이 지수 평활 계수(새 추정의 비중). */
    val emaAlpha: Float,
    /** 추정에 필요한 최소 후보 점 수. */
    val minPoints: Int,
    /** 바닥보다 이만큼 이상 낮은 점은 내려가는 단차 후보(개수만 기록). */
    val belowMarginM: Float,
    /** 직전 바닥 근처 후보가 모자란 깊이가 이만큼 이어지면 바닥을 잊고 다시 찾는다(v0.2.7). */
    val lostFrames: Int,
    /**
     * 바닥 후보는 카메라보다 이만큼 이상 낮은 점만(M12.1): 손에 든 폰은 가슴 높이라 바닥은 카메라 아래 약 1~1.3 m다.
     * 가까운 물체 윗면이나 벽 면이 보이는 바닥보다 점이 많을 때 바닥이 그리로 올라가는 것을 막는다. M13 1차 시도 실측: 캐리어
     * 윗면(카메라 아래 약 0.5 m)으로 올라가 캐리어 점이 "바닥 아래"로 버려짐. M12.0 실측: 끝 벽 1 m 앞에서 바닥이 시야에서
     * 빠지면 후보가 벽 면뿐이라 탐색 폭 안에서 벽을 타고 약 1 m 올라감. 0이면 카메라보다 낮은 점 전부(기준선).
     */
    val minBelowCameraM: Float,
    /** 바닥 추정 원천(M13.1c). PLANE이면 `frontend.planes`가 RANSAC이어야 한다. */
    val source: FloorSource,
)

/** 군집·높이 분류 설정. */
data class ClusterConfig(
    val epsM: Float,
    val minSamples: Int,
    val headMinM: Float,
    val bodyMinM: Float,
    /**
     * 벽 칸과 나머지 칸을 따로 군집한다(IMPROVE_SPEC §6 C2, M13.5 뒤). 벽 칸 = 평면 추출(`frontend.planes` RANSAC 필요)의 벽 표가
     * 맞은 장 수의 [wallFraction] 이상. 벽을 빼지 않고 따로 묶으므로 진행 방향의 벽은 그대로 장애물이다(무음 ≠ 장애물 없음).
     * 물체가 막·벽 밑동 칸을 거쳐 벽과 한 덩어리가 되는 것을 막는다. false = 기준선.
     */
    val separateWalls: Boolean,
    val wallFraction: Float,
)

/** 실측에서 반복된 '통로 전체를 채우는 깊이 sheet' 오인식 실험 필터. 기본은 꺼 둔다. */
data class FalsePositiveFilterConfig(
    val enabled: Boolean,
    val minVoxels: Int,
    val minLateralSpanM: Float,
    val minAlongSpanM: Float,
    /** depth sheet의 가장 가까운 앞쪽 경계. 이보다 가까운 군집은 안전상 필터하지 않는다. */
    val minAlongMinM: Float,
    val minHeightSpanM: Float,
    val maxHeightMinM: Float,
    val minHeightMaxM: Float,
)

/** 추적 설정. */
data class TrackConfig(
    val matchRadiusM: Float,
    val emaAlpha: Float,
    /** 새 군집을 실제 물체로 내보내기 전에 연속해서 관측해야 하는 횟수. */
    val minConfirmObservations: Int,
    /** 군집이 잠깐 빠져도 같은 id를 복구할 수 있도록 내부 track을 유지하는 느린 경로 갱신 횟수. */
    val maxMissedUpdates: Int,
    /** depth sheet 모양([FalsePositiveFilterConfig] 조건)인 군집의 확인 횟수. 지우지 않고 더 오래 확인한다. */
    val suspiciousConfirmObservations: Int,
    /** 확인 횟수로 세는 관측의 최소 confidence(군집 복셀 score 평균). 0이면 모두 센다. */
    val minConfirmConfidence: Float,
    /** 확인 중 이전 중심점(EMA)에서 이보다 멀리 튄 관측은 연속 횟수를 1부터 다시 센다. `matchRadiusM`이면 끈 것과 같다. */
    val maxConfirmCentroidJumpM: Float,
)

/** 대표점 설정. */
data class RepPointConfig(val strategy: RepStrategy)

/** 거리 구간·음원 선택 설정. */
data class PolicyConfig(
    val stopM: Float,
    val warnMaxM: Float,
    val silentMaxM: Float,
    val hysteresisM: Float,
    val maxSources: Int,
    val maxInfoAgeMs: Float,
    val prioritizeStop: Boolean = false,
)

/** 안내 상태 기계 설정. */
data class StateConfig(
    val recoverFrames: Int,
    val recoverScoreScale: Float,
    val unknownRepeatS: Float,
    /** 연속 자세 사이 이동 속도가 이보다 크면 자세 불연속(월드 좌표 재정렬)으로 본다. */
    val maxSpeedMps: Float,
    /** 연속 자세 사이 회전 각속도가 이보다 크면 자세 불연속으로 본다. */
    val maxAngularSpeedDps: Float,
)

/** 오디오 렌더링 설정. */
data class AudioConfig(
    val sampleRate: Int,
    val blockSize: Int,
    val masterGainDb: Float,

    /** 단순 비프 프로토타입의 음높이. */
    val toneHz: Float,

    /** 한 번 울릴 때 실제 소리가 나는 시간. */
    val beepOnMs: Float,

    /** 가까운 장애물의 비프 반복 주기. */
    val nearPeriodMs: Float,

    /** 먼 장애물의 비프 반복 주기. */
    val farPeriodMs: Float,

    /** 뒤쪽(|방위각| > 90°) 장애물의 음높이. */
    val rearToneHz: Float,

    /** 완전히 옆(±90°)일 때 두 귀 시간차. */
    val maxItdMs: Float,

    // --- HRTF 바이노럴 렌더러 (M6, §7.6). 위 비프 키는 SimpleBeepRenderer 전용 ---

    /** FLOOR_PULSE 버스트 길이(분홍 잡음 + 포락선). */
    val pulseMs: Float,
    /** WARN 구간 반복 주기: 거리 warnMaxM에서 이 값. */
    val warnFarPeriodMs: Float,
    /** WARN 구간 반복 주기: 거리 stopM에서 이 값(사이는 선형). */
    val warnNearPeriodMs: Float,
    /** STOP 구간 반복 주기. */
    val stopPeriodMs: Float,
    /** STOP 구간 음량 상승. */
    val stopGainDb: Float,
    /** HEAD_TONE 버스트의 음높이(고역 강조, 높이는 음색으로 구분). */
    val headToneHz: Float,
    /** 상태 알림음(비공간) 음량. */
    val alertGainDb: Float,
    /** 출력 리미터 상한(선형 진폭, 0~1). */
    val limiterCeiling: Float,
    /** `AudioTrack` 버퍼 크기(블록 수). 작을수록 출력 지연이 짧고 끊김 위험이 크다(M7 S10: 4 → 끊김, 8 → 약 52 ms·끊김 0). */
    val bufferBlocks: Int,
)

/** 상태 전이별 진동 길이(ms, §9.3, 가설 — 사용자 평가에서 조정). */
data class HapticsConfig(
    /** 준비 완료: 짧게 1회. */
    val readyMs: Int,
    /** 일시정지: 짧게 2회(각 [pauseMs], 사이 [pauseGapMs]). */
    val pauseMs: Int,
    val pauseGapMs: Int,
    /** 확인 불가: 길게 1회. */
    val unknownMs: Int,
    /** 종료: 길게 1회. */
    val exitMs: Int,
)

/** 녹화 저장 간격 설정. */
data class RecordConfig(
    val depthEveryN: Int,
    val rgbEveryN: Int,
    /** device.csv(발열·배터리) 기록 주기. */
    val deviceLogIntervalS: Float,
)

/**
 * 분석 도구용 정답 정렬 설정. 카메라가 [minTravelM]보다 적게 움직인 세션(정지 녹화)은 궤적 방향이 잡음이라
 * 처음 [headingWindowS]초의 카메라 시선(수평)을 보행선 방향으로 쓴다.
 */
data class AlignConfig(val fitLengthM: Float, val minTravelM: Float, val headingWindowS: Float)

/** 설정 파일의 키 누락·미지의 키·타입 불일치·값 범위 오류. [path]는 `map.voxelSizeM` 같은 점 표기. */
class ConfigException(val path: String, message: String) : IllegalArgumentException("config '$path': $message")


/** 기존 펄스와 접근 연속음 중 선택한다. */
enum class SonifyMode { PULSE, RISK_CONTINUOUS }

/** 접근 연속음 설정. 수치 원본은 default.json이다. */
data class SonifyConfig(
    val mode: SonifyMode,
    val historyS: Float,
    val minHistoryS: Float,
    val approachOnMps: Float,
    val approachOffMps: Float,
    val onceS: Float,
    val nearM: Float,
    val farM: Float,
    val minHz: Float,
    val maxHz: Float,
    val minGain: Float,
    val maxGain: Float,
    val ttcHorizonS: Float,
    val ttcWeight: Float,
    val minSecondHarmonic: Float,
    val maxSecondHarmonic: Float,
    val thirdHarmonic: Float,
    val headPitchRatio: Float,
    val attackS: Float,
    val releaseS: Float,
    val pitchSmoothS: Float,
    val prototypeGain: Float,
    val duckDb: Float,
)
