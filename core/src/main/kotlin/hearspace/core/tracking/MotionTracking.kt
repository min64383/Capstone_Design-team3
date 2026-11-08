package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelMap
import hearspace.core.types.HeightClass
import hearspace.core.types.RepStrategy

/**
 * 이번 깊이 장의 정보(M48 정지·움직임 분해, 설계 3.1절). 느린 경로가 지도 갱신 뒤 만들어 [Tracker.update]에 넘긴다.
 * 지도 연산은 람다로 받아 추적이 지도를 직접 알지 않게 한다.
 */
class MotionFrame(
    /** 깊이 촬영 시각. */
    val tNs: Long,
    /** 장 번호(같은 장 한 번 판정). */
    val frame: Long,
    /** 이번 장 장애물 점과 관측 가중치(정지 지도와 같은 거리 가중, 지도에 넣었든 뺐든). */
    val points: List<Pair<Vec3, Float>>,
    /** 이번 장 칸(점이 든 칸의 중심, 중복 없음) 중 통로 안 칸과, 그중 정지 지도가 이미 점유로 만든 칸인지. */
    val corridorCells: List<Pair<Vec3, Boolean>>,
    /** 이번 장 칸 전부(통로 밖 포함). 칼만 측정은 이것으로 한다: 통로를 벗어난 물체를 "놓침"으로 보지 않게. */
    val allCells: List<Vec3>,
    /** 칸 목록의 대표점 후보(지금과 같은 규칙: 통로·복셀·띠). 빈 목록이면 null. */
    val reps: (List<Vec3>) -> Map<RepStrategy, Vec3>?,
    /** 설정의 대표점 방식. */
    val strategy: RepStrategy,
    /** 측정 잡음 σ_m(Z)(점까지 카메라 거리로). */
    val sigmaAt: (Vec3) -> Float,
    /** 이번 장에서 그 칸 자리가 빈 곳으로 보이는가(정지 지도의 감쇠 판정과 같음). */
    val seenFree: (Vec3) -> Boolean,
    /** 칸들의 높이 분류(바닥 기준). */
    val heightClass: (List<Vec3>) -> HeightClass?,
    /** 상자 안 칸 중 그 시각 이후 생긴 칸을 정지 지도에서 빼 온다(움직임 판정 순간). */
    val take: (Vec3, Vec3, Long) -> List<VoxelMap.CellEvidence>,
    /** 칸을 정지 지도에 넣는다(넘겨주기). */
    val seed: (VoxelMap.CellEvidence) -> Unit,
    /** 후보 묶음(지금과 같은 DBSCAN). */
    val clusterXZ: (List<Vec3>) -> List<List<Vec3>>,
    /** 칸 하나 크기(m). */
    val voxelM: Float,
    /** 점이 통로 안인지(출력은 통로 안 점유 칸만, 지금과 같음). */
    val inCorridor: (Vec3) -> Boolean,
    /**
     * 움직임 정답(평가 전용): 시각과 track의 이번 장 측정 칸으로, 그 물체가 움직이는 종류인지(라벨처럼: true = 그렇다, null = 모름 → 지금 상태를
     * 그대로 둔다). 비전 라벨이 마스크 영역으로 물체를 고르듯 칸들로 판정한다.
     * 이 함수가 있으면 추정한 판정(속도 문턱·χ²)은 쓰지 않는다. 앱은 넘기지 않는다.
     */
    val movingOracle: ((Long, List<Vec3>) -> Boolean?)? = null,
    /** 카메라 위치(측정점: 카메라에서 본 가까운 면, 설계 3.1절 3). */
    val cameraW: Vec3 = Vec3.ZERO,
    /** 관측 가중치(정지 지도와 같은 거리 가중, 점까지 카메라 거리로). */
    val weightAt: (Vec3) -> Float = { 1f },
    /** 칸(중심)에 이번 장 든 깊이 점 수(M52 `track.measureWeight` POINTS의 가중치). */
    val pointCount: (Vec3) -> Float = { 1f },
    /** 평활 깊이 뒤처짐(ns, M52): 칼만에 측정을 [tNs] − 이 값으로 넣는다. 0 = 끔. */
    val measLagNs: Long = 0L,
    /** 의미 영상(M49): 깊이 장 시각과의 차가 `vision.maxLabelAgeMs` 안인 것만. null = 라벨 모름. */
    val labels: hearspace.core.types.LabelImage? = null,
    /** track마다 라벨 표를 쌓아 움직임을 판정한다(`vision.label` SEMANTICS). [movingOracle]이 있으면 그쪽이 먼저다. */
    val labelVotes: Boolean = false,
    /** 움직일 수 있는 라벨의 화소 값(`vision.movingLabels`). */
    val movingPixels: Set<Int> = emptySet(),
    /** 새 원시 깊이 장의 장애물 점(`track.measureFrom` RAW_MASK). 이번 장에 새 원시 장이 없으면 null. */
    val rawPoints: List<Vec3>? = null,
    /** [rawPoints]의 촬영 시각과 카메라 위치. */
    val rawTNs: Long = Long.MIN_VALUE,
    val rawCameraW: Vec3 = Vec3.ZERO,
    /**
     * 가장 최근 원시 장([rawLatestTNs])에서 월드 점 자리가 빈 곳인지(M58 `track.existFrom` RAW): [rawVisible]과 같은 투영·여유로, 원시 깊이가 점보다
     * 여유 넘게 멀면 true, 그 밖(보이는 표면·가림)이면 false, 원시가 모르면(깊이 없음·신뢰도 미달·영상 밖) null.
     */
    val rawFreeAt: ((Vec3) -> Boolean?)? = null,
    /** 라벨 화소 값 → 그 종류의 최대 속도(m/s, `vision.maxSpeedMps`): RAW_MASK 시작 게이트가 마스크 점의 다수 라벨로 고른다. */
    val maxSpeedOfPixel: Map<Int, Float> = emptyMap(),
    /**
     * 가시성 검사(M49 판정 단계 결과 뒤 사용자 승인): 월드 점이 가장 최근 원시 깊이 장에서 보이는 표면인지. 그 장 카메라로 투영한 화소의 원시
     * 깊이(신뢰도 ≥ `track.rawMinConfidence`)와 점의 카메라 거리 차가 max(`map.sigmaMultiple` × σ_원시(Z), 복셀) 안이면 true, 넘으면 false,
     * 원시 깊이가 없거나 영상 밖이면 null. 라벨은 그 화소에서 보이는 표면에만 붙인다(SemanticFusion은 보이는 표면 요소만 갱신).
     */
    val rawVisible: ((Vec3) -> Boolean?)? = null,
    /** [rawVisible]이 쓰는 원시 장의 촬영 시각(속도 있는 track은 칸을 이 시각으로 옮겨 검사한다). */
    val rawLatestTNs: Long = Long.MIN_VALUE,
    /** 복셀 지도 하나(M57 `track.motionMap` OWNED)의 지도. 있으면 take·seed·lastCells 대신 칸 소유로 움직이는 물체를 다룬다. null = 두 지도. */
    val owned: VoxelMap? = null,
    /** 이번 장에 새 원시 장이 있으면 빈 곳 위반 재료(M57 `track.freeSpaceRecord`). */
    val freeSpace: FreeSpaceFrame? = null,
)

/**
 * 칼만 측정점(M48): 칸들을 카메라에서 본 방위의 중앙값 방향으로, 거리의 아래 사분위(25%)만큼 떨어진 수평 점.
 * 깊이 영상의 막·번짐은 물체 뒤(배경 쪽)에 생기므로 중심점은 가까이 올수록 뒤로 끌리고(합성 SM-1 잡음 깊이에서 속도 1.0 → 0.6~0.8 m/s),
 * 가장 앞 칸 하나는 장마다 칸이 바뀌어 크게 뛴다. 가까운 면의 아래 사분위는 둘 다 덜 받는다(자체 선택, 관례적 강건 통계).
 * [weight]가 있으면(M52 POINTS) 칸마다 그만큼 센 가중 중앙값·가중 아래 사분위(누적 가중이 처음 비율 이상이 되는 값), 없으면 칸마다 한 번.
 */
fun nearSurfacePoint(cells: List<Vec3>, cameraW: Vec3, weight: ((Vec3) -> Float)? = null): Vec3? {
    if (cells.isEmpty()) return null
    val ang = cells.map { kotlin.math.atan2((it.x - cameraW.x).toDouble(), (it.z - cameraW.z).toDouble()) }
    val ref = ang.first()
    val unwrap = ang.map { a -> var d = a - ref; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; ref + d }
    val range = cells.map { kotlin.math.hypot((it.x - cameraW.x).toDouble(), (it.z - cameraW.z).toDouble()) }
    val bearing: Double
    val r: Double
    if (weight == null) {
        val unwrapped = unwrap.sorted()
        bearing = unwrapped[unwrapped.size / 2]
        val ranges = range.sorted()
        r = ranges[(ranges.size - 1) / 4]
    } else {
        val w = cells.map { weight(it).toDouble() }
        bearing = weightedQuantile(unwrap, w, 0.5)
        r = weightedQuantile(range, w, 0.25)
    }
    return Vec3((cameraW.x + r * kotlin.math.sin(bearing)).toFloat(), 0f, (cameraW.z + r * kotlin.math.cos(bearing)).toFloat())
}

/** 가중 분위수: 값 순으로 누적 가중이 처음 [q] × 전체 이상이 되는 값. */
internal fun weightedQuantile(v: List<Double>, w: List<Double>, q: Double): Double {
    val idx = v.indices.sortedBy { v[it] }
    val total = w.sum()
    var cum = 0.0
    for (i in idx) {
        cum += w[i]
        if (cum >= q * total) return v[i]
    }
    return v[idx.last()]
}

/**
 * track 하나의 움직임 상태(M48). 움직이는 물체의 HITS 증거는 물체 단위로 쌓는다: 측정된 장마다 표 += 거리 가중, 점수 += `hitGain` × 가중(최대 1),
 * 예측 자리가 빈 곳으로 보인 장마다 점수 −= `decayPerObservation`(0 이하면 지움, 시야 밖·깊이 무효는 그대로). 점유 = 표 ≥ `minHits`이고
 * 점수 ≥ `minScore`(정지 지도와 같은 문턱). 처음 설계는 물체 좌표 칸마다 쌓았으나, 실제 사람은 걸으며 모양이 바뀌고 칼만 원점이 2~4 cm
 * 흔들려 5 cm 칸 하나에 6표가 모이지 않았다(T 150338 점유 칸 0, 합성 원기둥은 400여 칸).
 */
class MotionState(
    accelSigmaMps2: Float,
    val birthNs: Long,
    var candidate: Boolean,
    initMaxSpeedMps: Float = 0f,
    initGateSigmas: Float = 3f,
    initUpdates: Int = 0,
) {
    val filter = MotionFilter(accelSigmaMps2, initMaxSpeedMps, initGateSigmas, initUpdates)
    var moving = false
    var votes = 0f
    var score = 0f
    /** 마지막으로 측정한 장의 창 안 칸(물체 모양)과 그 시각. */
    var lastCells: List<Vec3> = emptyList()
    var lastCellsNs: Long = Long.MIN_VALUE
    /** 마지막 측정 칸의 상자(없으면 짝지은 지도 군집의 상자). */
    var boxMin: Vec3 = Vec3.ZERO
    var boxMax: Vec3 = Vec3.ZERO
    /** 상자를 잰 장의 시각(RAW_MASK: 칼만은 원시 장 시각, 상자는 평활 장 시각이라 창은 이 시각부터 옮긴다, M49). */
    var boxNs: Long = Long.MIN_VALUE
    /** 연속으로 측정이 없던 장 수. */
    var missed = 0
    /** 마지막 측정(대표점)과 그 시각. */
    var lastMeasW: Vec3? = null
    var lastMeasNs: Long = Long.MIN_VALUE
    /** 마지막 장의 측정 칸 수(기록용). */
    var nMeasCells = 0
    var lastChi2 = 0f
    /** 라벨 표(M49): 새 의미 영상마다 창 안 칸 중 움직일 수 있는 라벨 칸이 `cluster.minSamples` 이상이면 표 +1·점수 + hitGain. */
    var labelVotes = 0f
    var labelScore = 0f
    /** 마지막으로 센 의미 영상 시각(같은 영상은 한 번만 센다). */
    var lastLabelTNs = Long.MIN_VALUE
    /** 마지막 원시 장에서 고른 마스크 점 수(RAW_MASK, 기록용). −1 = 원시 측정 없음. */
    var nRawPoints = -1
    /** 마지막으로 칼만에 넣은 원시 측정의 촬영 시각(RAW_MASK). */
    var rawMeasTNs = Long.MIN_VALUE
    /** 칼만이 원시 점만으로 쌓였는지(RAW_MASK). 아니면 첫 원시 측정 때 다시 시작한다. */
    var filterRaw = false
    /** 마지막으로 받아들인 원시 측정 점이 든 칸의 중심([rawMeasTNs] 시각, M58 출력 모양). */
    var rawCells: List<Vec3> = emptyList()

    fun occupied(minHits: Float, minScore: Float) = votes >= minHits && score >= minScore

    /** 빈 곳 위반 창(M57 FREE_SPACE): 최근 원시 장들의 (비교 수, 위반 수, |회전| °/s, 칸 라벨 수). */
    val fsWin = ArrayDeque<FsRow>()
    /** 나이 ≥ T_수렴에서 S > 문턱인 연속 원시 장 수(기하 길). */
    var fsRun = 0
    /** 보류 아닌 창에서 S ≤ 문턱인 연속 원시 장 수(정지 복귀). */
    var fsBelow = 0
    /** 이번 장의 판정(새 원시 장이 없거나 보류면 null = 지금 상태 유지). */
    var fsDecision: Boolean? = null

    /** 시각 [tNs]의 물체 모양: 마지막 측정 칸을 속도 × 경과만큼 옮긴 것(측정한 장이면 그대로). */
    fun cellsAt(tNs: Long): List<Vec3> {
        val v = filter.velocityW ?: return lastCells
        val dt = ((tNs - lastCellsNs) / 1e9f).coerceAtLeast(0f)
        if (dt == 0f) return lastCells
        val d = v * dt
        return lastCells.map { it + d }
    }

    /** 원점 = 칼만 위치(준비 전이면 마지막 측정). */
    fun origin(): Vec3? = filter.positionW ?: lastMeasW

    /**
     * 시각 [tNs]의 추적 창: 측정 상자를 예측 속도 × 경과만큼 옮기고 [marginM]만큼 넓힌다. 경과는 칼만 시각부터(M48), [fromBox]면 상자를 잰
     * 시각부터(M49 RAW_MASK: 칼만 시각이 원시 장이라 최대 0.1 s 앞서 지나간 상자를 한 번 더 옮겨 창이 물체 앞으로 밀렸다, 합성 SM-9 6.9 m/s).
     */
    fun gate(tNs: Long, marginM: Float, fromBox: Boolean = false): Pair<Vec3, Vec3> {
        val v = filter.velocityW ?: Vec3.ZERO
        val t0 = if (fromBox && boxNs != Long.MIN_VALUE) boxNs else filter.tNs
        val dt = if (t0 == Long.MIN_VALUE) 0f else ((tNs - t0) / 1e9f).coerceAtLeast(0f)
        val d = v * dt
        val m = Vec3(marginM, marginM, marginM)
        return (boxMin + d - m) to (boxMax + d + m)
    }
}

/** 움직임 기록(`motion.csv`) 한 줄. */
data class MotionDebug(
    val tCaptureNs: Long,
    val id: Int,
    val candidate: Boolean,
    val moving: Boolean,
    val measW: Vec3?,
    val posW: Vec3?,
    val velW: Vec3?,
    val posSigmaM: Float,
    val velSigmaMps: Float,
    val chi2: Float,
    val nMeasCells: Int,
    val votes: Float,
    val score: Float,
    /** [measW]의 측정 시각(RAW_MASK = 원시 장 촬영 시각, 아니면 [tCaptureNs]). */
    val measTNs: Long = tCaptureNs,
    /** 라벨 표·점수(M49). */
    val labelVotes: Float = 0f,
    val labelScore: Float = 0f,
    /** 이번 장 원시 측정에서 고른 마스크 점 수(RAW_MASK, −1 = 원시 측정 없음). */
    val nRawPoints: Int = -1,
)

/** 빈 곳 위반 창의 한 원시 장(M57). */
class FsRow(val nCmp: Int, val nViol: Int, val rotDegS: Float, val labels: IntArray)

internal fun inBox(p: Vec3, lo: Vec3, hi: Vec3) = p.x in lo.x..hi.x && p.y in lo.y..hi.y && p.z in lo.z..hi.z
