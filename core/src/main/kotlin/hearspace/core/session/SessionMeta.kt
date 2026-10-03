package hearspace.core.session

import hearspace.core.geometry.Vec3
import hearspace.core.types.Intrinsics
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson

/** 녹화 기기 정보. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    /** `Build.SOC_MODEL`(API 31+), 없으면 null. */
    val socModel: String?,
    val androidRelease: String,
    val sdkInt: Int,
)

/** ARCore 버전. */
data class ArcoreInfo(
    /** 앱이 빌드에 쓴 SDK 버전. */
    val sdkVersion: String,
    /** 기기에 설치된 Google Play 서비스(AR) 버전, 모르면 null. */
    val apkVersion: String?,
)

/** 깊이 지원 여부(F1)와 실제 사용한 모드. 해상도·내부 파라미터는 첫 깊이 이미지에서 채운다(F4). */
data class DepthInfo(
    val automaticSupported: Boolean,
    val rawDepthOnlySupported: Boolean,
    /** ARCore `Config.DepthMode` 이름. */
    val modeUsed: String,
    val width: Int?,
    val height: Int?,
    /** 깊이 내부 파라미터 = 텍스처 K × (깊이 크기 / 텍스처 크기) (F4, 형식 v1). v0이거나 깊이를 못 받았으면 null. */
    val intrinsics: Intrinsics? = null,
)

/** 카메라 설정(F3). 내부 파라미터는 ARCore가 주는 회전하지 않은 센서 방향 값. */
data class CameraInfo(
    val imageIntrinsics: Intrinsics,
    val textureIntrinsics: Intrinsics,
    /** 녹화 시작 시 화면 회전(0/90/180/270). */
    val displayRotationDeg: Int,
    val fpsMin: Int?,
    val fpsMax: Int?,
)

/** 녹화 결과 통계. 녹화 종료 시 채운다. */
data class SessionStats(
    val nFrames: Long,
    val nDepthSaved: Long,
    val nRawDepthSaved: Long,
    val nRgbSaved: Long,
    /** 저장 스레드가 바빠서 파일 저장을 건너뛴 프레임 수(자세 행은 모두 기록됨). */
    val nFileJobsSkipped: Long,
    val nWriteErrors: Long,
    val durationS: Float,
)

/** `meta.json`. */
data class SessionMeta(
    val formatVersion: String,
    val sessionId: String,
    /** 부록 A 장면 ID(S01~S10, T01). */
    val sceneId: String,
    /** 벽시계 기준 생성 시각(ISO-8601, 표시용). */
    val createdAt: String,
    val device: DeviceInfo,
    val arcore: ArcoreInfo,
    val depth: DepthInfo,
    val camera: CameraInfo,
    /** 기준 파지에서 실측한 카메라 → 머리 오프셋(월드 수평 기준, m). */
    val gripOffsetM: Vec3,
    /**
     * 녹화 시작 시 `SystemClock.elapsedRealtimeNanos() − System.nanoTime()`(부팅 시계 − 단조 시계).
     * 기기가 잠든 적이 있으면 0이 아니게 되어 `Frame.getTimestamp()`의 시간 기준을 판별할 수 있다(F5). 모르면 null.
     */
    val elapsedMinusMonotonicNs: Long?,
    val depthEveryN: Int,
    val rgbEveryN: Int,
    /** 좌표·시간 규약 설명(키 → 설명). */
    val conventions: Map<String, String>,
    val stats: SessionStats?,
) {
    /** JSON 텍스트로. */
    fun toJson(): String = MiniJson.write(toJsonValue())

    private fun toJsonValue(): JsonValue = obj(
        "formatVersion" to str(formatVersion),
        "sessionId" to str(sessionId),
        "sceneId" to str(sceneId),
        "createdAt" to str(createdAt),
        "device" to obj(
            "manufacturer" to str(device.manufacturer),
            "model" to str(device.model),
            "socModel" to str(device.socModel),
            "androidRelease" to str(device.androidRelease),
            "sdkInt" to num(device.sdkInt),
        ),
        "arcore" to obj("sdkVersion" to str(arcore.sdkVersion), "apkVersion" to str(arcore.apkVersion)),
        "depth" to obj(
            "automaticSupported" to JsonBool(depth.automaticSupported),
            "rawDepthOnlySupported" to JsonBool(depth.rawDepthOnlySupported),
            "modeUsed" to str(depth.modeUsed),
            "width" to num(depth.width),
            "height" to num(depth.height),
            "intrinsics" to (depth.intrinsics?.let { intrinsics(it) } ?: JsonNull),
        ),
        "camera" to obj(
            "imageIntrinsics" to intrinsics(camera.imageIntrinsics),
            "textureIntrinsics" to intrinsics(camera.textureIntrinsics),
            "displayRotationDeg" to num(camera.displayRotationDeg),
            "fpsMin" to num(camera.fpsMin),
            "fpsMax" to num(camera.fpsMax),
        ),
        "gripOffsetM" to JsonArray(listOf(num(gripOffsetM.x), num(gripOffsetM.y), num(gripOffsetM.z))),
        "elapsedMinusMonotonicNs" to num(elapsedMinusMonotonicNs),
        "depthEveryN" to num(depthEveryN),
        "rgbEveryN" to num(rgbEveryN),
        "conventions" to JsonObject(conventions.mapValues { JsonString(it.value) }),
        "stats" to (stats?.let {
            obj(
                "nFrames" to num(it.nFrames),
                "nDepthSaved" to num(it.nDepthSaved),
                "nRawDepthSaved" to num(it.nRawDepthSaved),
                "nRgbSaved" to num(it.nRgbSaved),
                "nFileJobsSkipped" to num(it.nFileJobsSkipped),
                "nWriteErrors" to num(it.nWriteErrors),
                "durationS" to num(it.durationS),
            )
        } ?: JsonNull),
    )

    companion object {
        /** `meta.json` 텍스트를 읽는다. 필드가 없거나 타입이 틀리면 [IllegalArgumentException]. */
        fun fromJson(text: String): SessionMeta {
            val r = Obj(MiniJson.parse(text) as? JsonObject ?: throw IllegalArgumentException("meta.json: not an object"), "")
            val d = r.obj("device")
            val a = r.obj("arcore")
            val dp = r.obj("depth")
            val c = r.obj("camera")
            val g = r.floats("gripOffsetM")
            require(g.size == 3) { "gripOffsetM: need 3 values" }
            return SessionMeta(
                formatVersion = r.str("formatVersion"),
                sessionId = r.str("sessionId"),
                sceneId = r.str("sceneId"),
                createdAt = r.str("createdAt"),
                device = DeviceInfo(d.str("manufacturer"), d.str("model"), d.strOpt("socModel"), d.str("androidRelease"), d.int("sdkInt")),
                arcore = ArcoreInfo(a.str("sdkVersion"), a.strOpt("apkVersion")),
                depth = DepthInfo(
                    dp.bool("automaticSupported"), dp.bool("rawDepthOnlySupported"), dp.str("modeUsed"),
                    dp.intOpt("width"), dp.intOpt("height"),
                    // v0 meta에는 이 필드가 없다
                    dp.objOpt("intrinsics", missingIsNull = true)?.let { k ->
                        Intrinsics(k.float("fx"), k.float("fy"), k.float("cx"), k.float("cy"), k.int("width"), k.int("height"))
                    },
                ),
                camera = CameraInfo(
                    c.intrinsics("imageIntrinsics"), c.intrinsics("textureIntrinsics"), c.int("displayRotationDeg"),
                    c.intOpt("fpsMin"), c.intOpt("fpsMax"),
                ),
                gripOffsetM = Vec3(g[0], g[1], g[2]),
                elapsedMinusMonotonicNs = r.longOpt("elapsedMinusMonotonicNs"),
                depthEveryN = r.int("depthEveryN"),
                rgbEveryN = r.int("rgbEveryN"),
                conventions = r.obj("conventions").strings(),
                stats = r.objOpt("stats")?.let {
                    SessionStats(
                        it.long("nFrames"), it.long("nDepthSaved"), it.long("nRawDepthSaved"), it.long("nRgbSaved"),
                        it.long("nFileJobsSkipped"), it.long("nWriteErrors"), it.float("durationS"),
                    )
                },
            )
        }

        private fun obj(vararg fields: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*fields))
        private fun str(s: String?): JsonValue = s?.let { JsonString(it) } ?: JsonNull
        private fun num(n: Number?): JsonValue = when (n) {
            null -> JsonNull
            // Float는 최단 10진 표기를 거쳐 Double로 옮겨야 0.1f가 0.10000000149…로 써지지 않는다.
            is Float -> JsonNumber(n.toString().toDouble())
            else -> JsonNumber(n.toDouble())
        }
        private fun intrinsics(k: Intrinsics) = obj(
            "fx" to num(k.fx), "fy" to num(k.fy), "cx" to num(k.cx), "cy" to num(k.cy),
            "width" to num(k.width), "height" to num(k.height),
        )
    }

    /** 읽기 도우미. 경로를 오류 메시지에 넣는다. */
    private class Obj(private val o: JsonObject, private val prefix: String) {
        private fun get(k: String): JsonValue = o.fields[k] ?: throw IllegalArgumentException("meta.json: missing $prefix$k")
        private fun bad(k: String, what: String): Nothing = throw IllegalArgumentException("meta.json: $prefix$k must be $what")

        fun obj(k: String) = Obj(get(k) as? JsonObject ?: bad(k, "an object"), "$prefix$k.")
        fun objOpt(k: String, missingIsNull: Boolean = false) = if (missingIsNull && o.fields[k] == null) null else when (val v = get(k)) {
            JsonNull -> null
            is JsonObject -> Obj(v, "$prefix$k.")
            else -> bad(k, "an object or null")
        }
        fun str(k: String) = (get(k) as? JsonString)?.value ?: bad(k, "a string")
        fun strOpt(k: String) = when (val v = get(k)) {
            JsonNull -> null
            is JsonString -> v.value
            else -> bad(k, "a string or null")
        }
        fun bool(k: String) = (get(k) as? JsonBool)?.value ?: bad(k, "a boolean")
        private fun number(k: String) = (get(k) as? JsonNumber)?.value ?: bad(k, "a number")
        fun float(k: String) = number(k).toFloat()
        fun long(k: String) = number(k).let { if (it == Math.rint(it)) it.toLong() else bad(k, "an integer") }
        fun int(k: String) = long(k).toInt()
        fun intOpt(k: String) = if (get(k) == JsonNull) null else int(k)
        fun longOpt(k: String) = if (o.fields[k] == null || get(k) == JsonNull) null else long(k)
        fun floats(k: String) = ((get(k) as? JsonArray) ?: bad(k, "an array")).items.map {
            ((it as? JsonNumber) ?: bad(k, "an array of numbers")).value.toFloat()
        }
        fun strings(): Map<String, String> = o.fields.mapValues { (k, v) -> (v as? JsonString)?.value ?: bad(k, "a string") }
        fun intrinsics(k: String) = obj(k).let {
            Intrinsics(it.float("fx"), it.float("fy"), it.float("cx"), it.float("cy"), it.int("width"), it.int("height"))
        }
    }
}
