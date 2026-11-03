package hearspace.core.truth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.Intrinsics
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson
import java.io.File

/**
 * 클릭한 점이 놓인 수평면(IMPROVE_SPEC §9.5): FEET = 바닥면, HEAD = 바닥면 + 그 물체의 몸 기준점 높이(`heightM`). 기준점은 허리를 쓴다
 * (2026-11-02: 카메라 1.5 m에서 머리 끝은 0.2 m 위라 거리가 크게 틀림). 높이는 [ClickTruth.fitHeadPlaneY]로 같은 장 발 짝에 맞춘다.
 */
enum class ClickPlane { FEET, HEAD }

/**
 * 클릭 하나. [tNs]는 그 RGB 장의 카메라 시각(frames.csv `tNs`), [u]·[v]는 센서 방향 RGB 영상 좌표(px), [floorY]는 그때 쓴 바닥면 월드 높이,
 * [floorFrom]은 그 출처(`rgb` = floor_rgb.json, `file` = 파일의 floorYFallback, `depth` = 재생의 깊이 바닥: 깊이와 독립이 아니라 표시해 둠),
 * [pointW]는 계산한 세계 점(면이 카메라 앞에 없으면 null).
 */
data class Click(val tNs: Long, val u: Float, val v: Float, val plane: ClickPlane, val floorY: Float, val floorFrom: String, val pointW: Vec3?)

/**
 * 클릭 정답 물체. kind: `moving`(시각별 점) | `static`. shape(정지 물체): `point`(모든 클릭이 같은 점: 위치 = 중앙값, 흩어짐 = 검증)
 * | `line`(클릭이 순서대로 꼭짓점). added: `blind`(재생 결과를 보기 전) | `review`(기준선 경고를 보며 더함).
 * [situation]·[action]은 상황(S1~S3)과 정답 행동(STOP·LEFT·RIGHT·PASS), 없으면 null.
 */
data class ClickObject(
    val id: String,
    val kind: String,
    val shape: String,
    val label: String,
    val radiusM: Float,
    val heightM: Float,
    val added: String,
    val situation: String?,
    val action: String?,
    val clicks: List<Click>,
)

/** 세션의 클릭 정답(`annotations/events.json`). [floorYFallback]은 `floor_rgb.json`이 없을 때 쓰는 바닥면 월드 높이. */
data class ClickAnnotations(val objects: List<ClickObject>, val floorYFallback: Float? = null)

/**
 * 실외 클릭 정답(IMPROVE_SPEC §9.5, M47). 누른 화소의 광선을 수평면과 만나게 해 세계 점을 얻는다. 깊이 값을 쓰지 않으므로 평가 대상과 독립이다.
 * 이 계산은 여기 하나만 두고, viewer가 결과를 파일에 쓰며 `tools/analysis/event_eval.py`는 세계 점만 읽는다.
 */
object ClickTruth {
    const val FILE = "annotations/events.json"

    /**
     * 센서 영상 화소 ([u], [v])의 광선이 수평면 y = [planeY]와 만나는 세계 점. [worldFromCam]은 세계 ← C_cv(+X 오른쪽, +Y 아래, +Z 앞,
     * `SessionReader.toPoseFrame`), [k]는 `camera.imageIntrinsics`. 광선이 면과 카메라 앞에서 만나지 않으면 null.
     */
    fun planePoint(worldFromCam: Mat4, k: Intrinsics, u: Float, v: Float, planeY: Float): Vec3? {
        val dir = worldFromCam.transformDir(Vec3((u - k.cx) / k.fx, (v - k.cy) / k.fy, 1f))
        val o = worldFromCam.translation()
        if (kotlin.math.abs(dir.y) < 1e-6f) return null
        val t = (planeY - o.y) / dir.y
        return if (t <= 0f) null else o + dir * t
    }

    /** 클릭의 면 높이: FEET = [floorY], HEAD = [floorY] + 물체 키. */
    fun planeY(plane: ClickPlane, floorY: Float, heightM: Float) = if (plane == ClickPlane.HEAD) floorY + heightM else floorY

    /**
     * 같은 장에 누른 발·머리 끝 짝으로 머리 끝 면의 월드 높이를 맞춘다(M47, 2026-11-02 첫 주석에서). 카메라가 머리 끝과 높이가 비슷하면
     * (가슴 높이 1.5 m, 키 1.7 m) 키·카메라 높이의 몇 cm 오차가 머리 끝 거리를 수십 cm 바꾸므로, 발 점(바닥면, 오차 작음)과 수평 위치가
     * 같아지는 면을 찾는다. 짝 i마다 머리 끝 광선(원점 oᵢ, 방향 dᵢ)의 면 y = Y 교점 수평 위치는 oᵢ + dᵢ(Y − oᵢ.y)/dᵢ.y라 발 점 fᵢ와의
     * 차가 Y의 1차식 rᵢ + cᵢY이고, Σ|rᵢ + cᵢY|²를 최소로 하는 Y = −Σcᵢ·rᵢ / Σcᵢ·cᵢ. [pairs]는 (발 세계 점, 머리 끝 화소의 세계 ←
     * C_cv, u, v). 쓸 짝이 없으면 null.
     */
    fun fitHeadPlaneY(pairs: List<HeadPair>, k: Intrinsics): Float? {
        var num = 0.0
        var den = 0.0
        for (p in pairs) {
            val d = p.worldFromCam.transformDir(Vec3((p.u - k.cx) / k.fx, (p.v - k.cy) / k.fy, 1f))
            if (kotlin.math.abs(d.y) < 1e-6f) continue
            val o = p.worldFromCam.translation()
            val cx = d.x / d.y
            val cz = d.z / d.y
            val rx = o.x - cx * o.y - p.feetW.x
            val rz = o.z - cz * o.y - p.feetW.z
            num += cx * rx + cz * rz
            den += cx * cx + cz * cz
        }
        return if (den <= 0.0) null else (-num / den).toFloat()
    }

    /** [fitHeadPlaneY]의 짝 하나: 같은 장의 발 세계 점과 머리 끝 클릭. */
    data class HeadPair(val feetW: Vec3, val worldFromCam: Mat4, val u: Float, val v: Float)

    fun load(file: File): ClickAnnotations {
        val root = MiniJson.parse(file.readText()) as? JsonObject ?: throw IllegalArgumentException("$file: not an object")
        require((root.fields["version"] as? JsonNumber)?.value?.toInt() == 1) { "$file: version must be 1" }
        val objs = (root.fields["objects"] as? JsonArray)?.items.orEmpty().map { v ->
            val o = (v as JsonObject).fields
            fun s(k: String) = (o[k] as? JsonString)?.value
            fun f(k: String) = (o[k] as? JsonNumber)?.value?.toFloat()
            ClickObject(
                id = s("id") ?: throw IllegalArgumentException("$file: object without id"),
                kind = s("kind") ?: "static", shape = s("shape") ?: "point", label = s("label") ?: "",
                radiusM = f("radiusM") ?: 0f, heightM = f("heightM") ?: 0f, added = s("added") ?: "blind",
                situation = s("situation"), action = s("action"),
                clicks = (o["clicks"] as? JsonArray)?.items.orEmpty().map { c ->
                    val cf = (c as JsonObject).fields
                    fun n(k: String) = (cf[k] as? JsonNumber)?.value
                    val p = cf["pointW"] as? JsonArray
                    Click(
                        tNs = n("tNs")!!.toLong(), u = n("u")!!.toFloat(), v = n("v")!!.toFloat(),
                        plane = ClickPlane.valueOf((cf["plane"] as JsonString).value), floorY = n("floorY")!!.toFloat(),
                        floorFrom = (cf["floorFrom"] as? JsonString)?.value ?: "rgb",
                        pointW = p?.items?.map { (it as JsonNumber).value.toFloat() }?.let { Vec3(it[0], it[1], it[2]) },
                    )
                },
            )
        }
        return ClickAnnotations(objs, (root.fields["floorYFallback"] as? JsonNumber)?.value?.toFloat())
    }

    fun save(file: File, a: ClickAnnotations) {
        fun num(x: Number) = JsonNumber(x.toDouble())
        fun str(x: String?): JsonValue = x?.let { JsonString(it) } ?: JsonNull
        val root = JsonObject(
            linkedMapOf(
                "version" to num(1),
                "floorYFallback" to (a.floorYFallback?.let { num(it) } ?: JsonNull),
                "objects" to JsonArray(a.objects.map { o ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonString(o.id), "kind" to JsonString(o.kind), "shape" to JsonString(o.shape),
                            "label" to JsonString(o.label), "radiusM" to num(o.radiusM), "heightM" to num(o.heightM),
                            "added" to JsonString(o.added), "situation" to str(o.situation), "action" to str(o.action),
                            "clicks" to JsonArray(o.clicks.map { c ->
                                JsonObject(
                                    linkedMapOf(
                                        "tNs" to num(c.tNs), "u" to num(c.u), "v" to num(c.v), "plane" to JsonString(c.plane.name),
                                        "floorY" to num(c.floorY), "floorFrom" to JsonString(c.floorFrom),
                                        "pointW" to (c.pointW?.let { JsonArray(listOf(num(it.x), num(it.y), num(it.z))) } ?: JsonNull),
                                    ),
                                )
                            }),
                        ),
                    )
                }),
            ),
        )
        file.parentFile?.mkdirs()
        file.writeText(MiniJson.write(root))
    }
}
