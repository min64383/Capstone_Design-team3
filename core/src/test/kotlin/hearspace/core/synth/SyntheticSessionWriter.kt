package hearspace.core.synth

import hearspace.core.session.ArcoreInfo
import hearspace.core.session.CameraInfo
import hearspace.core.session.DepthInfo
import hearspace.core.session.DeviceInfo
import hearspace.core.session.FrameRow
import hearspace.core.session.FramesCsv
import hearspace.core.session.GrayImage
import hearspace.core.session.Png16
import hearspace.core.session.SessionFormat
import hearspace.core.session.SessionMeta
import hearspace.core.session.SessionStats
import hearspace.core.geometry.Vec3
import hearspace.core.types.HeightClass
import hearspace.core.types.Intrinsics
import java.io.File

/**
 * 합성 녹화를 실제 녹화와 같은 세션 폴더(형식 v1, 또는 호환 확인용 v0)로 쓴다. SessionReader 테스트와 M8 오프라인 재생에 쓴다.
 * 깊이는 일반 깊이(`depth/`)와 원시 깊이(`raw_depth/`) 양쪽에 같은 이미지를 둔다. 텍스처 K는 깊이 K의 12배.
 */
object SyntheticSessionWriter {

    /** [rec]을 [dir]에 쓴다. [depthOffsetNs]만큼 깊이 시각을 프레임 시각보다 앞당긴다(실제 ARCore: 약 0.3 ms). */
    fun write(
        rec: SyntheticRecording,
        dir: File,
        sceneId: String,
        depthOffsetNs: Long = 300_000L,
        version: String = SessionFormat.VERSION,
    ): File {
        dir.mkdirs()
        listOf(SessionFormat.DEPTH_DIR, SessionFormat.RAW_DEPTH_DIR, SessionFormat.DEPTH_CONF_DIR).forEach { File(dir, it).mkdirs() }
        val k = rec.frames.firstNotNullOfOrNull { it.depth }?.K ?: SyntheticGenerator.DEPTH_K
        val textureK = Intrinsics(k.fx * 12, k.fy * 12, k.cx * 12, k.cy * 12, k.width * 12, k.height * 12)
        var lastDepthT = Long.MIN_VALUE
        var saved = 0L
        File(dir, SessionFormat.FRAMES_FILE).bufferedWriter().use { w ->
            w.write(FramesCsv.headerLine(version)); w.newLine()
            for (f in rec.frames) {
                val d = f.depth
                val depthT = d?.let { it.tCaptureNs - depthOffsetNs }
                val isNew = depthT != null && depthT != lastDepthT
                if (isNew) {
                    val png = Png16.encode(GrayImage.of16(d.K.width, d.K.height, d.depthMm))
                    File(dir, SessionFormat.depthFile(f.index.toLong())).writeBytes(png)
                    File(dir, SessionFormat.rawDepthFile(f.index.toLong())).writeBytes(png)
                    val conf = ByteArray(d.depthMm.size) { i -> if (d.depthMm[i].toInt() == 0) 0 else 255.toByte() }
                    File(dir, SessionFormat.confFile(f.index.toLong())).writeBytes(Png16.encode(GrayImage.of8(d.K.width, d.K.height, conf)))
                    lastDepthT = depthT
                    saved++
                }
                val row = FrameRow(
                    frameIndex = f.index.toLong(),
                    tNs = f.pose.tCaptureNs,
                    sysElapsedNs = f.pose.tCaptureNs + 136_000_000L,
                    tracking = f.pose.tracking,
                    trackingFailure = if (f.pose.tracking.name == "TRACKING") "NONE" else "INSUFFICIENT_FEATURES",
                    pose = f.poseGl,
                    displayPose = if (version == SessionFormat.VERSION_V0) f.displayPoseGl else null,
                    depthTNs = depthT,
                    depthFile = if (isNew) SessionFormat.depthFile(f.index.toLong()) else null,
                    rawDepthTNs = depthT,
                    rawDepthFile = if (isNew) SessionFormat.rawDepthFile(f.index.toLong()) else null,
                    confFile = if (isNew) SessionFormat.confFile(f.index.toLong()) else null,
                    rgbFile = null,
                )
                w.write(FramesCsv.format(row, version)); w.newLine()
            }
        }
        val meta = SessionMeta(
            formatVersion = version,
            sessionId = dir.name,
            sceneId = sceneId,
            createdAt = "2026-01-01T00:00:00Z",
            device = DeviceInfo("synthetic", "synthetic", null, "0", 0),
            arcore = ArcoreInfo("synthetic", null),
            depth = DepthInfo(true, true, "SYNTHETIC", k.width, k.height, if (version == SessionFormat.VERSION_V0) null else k),
            camera = CameraInfo(textureK, textureK, 0, rec.walk.fps, rec.walk.fps),
            gripOffsetM = rec.walk.gripOffsetM,
            elapsedMinusMonotonicNs = 0L,
            depthEveryN = 1,
            rgbEveryN = 1,
            conventions = mapOf("pose" to "synthetic, ARCore GL camera convention"),
            stats = SessionStats(rec.frames.size.toLong(), saved, saved, 0, 0, 0, rec.walk.durationS),
        )
        File(dir, SessionFormat.META_FILE).writeText(meta.toJson())
        writeTruth(rec, dir)
        return dir
    }

    /**
     * 정답 `annotations/obstacles.json`(docs/FORMAT.md §정답): 원점 = 시작 시 머리 아래 바닥(시작 표시),
     * +z = 보행선(진행 방향), +x = 오른쪽, +y = 위, 미터. 장애물은 월드 AABB를 이 좌표로 옮긴 것. 구조물([SceneItem.structure])은
     * `kind: structure`로 함께 적는다(정답 v2).
     */
    fun writeTruth(rec: SyntheticRecording, dir: File) {
        val (_, head0) = SyntheticGenerator.cameraPose(rec.walk, 0f)
        val f = head0.headingW
        val r = head0.rightW
        val o = Vec3(head0.positionW.x, 0f, head0.positionW.z) // 바닥 y = 0
        fun toTruth(p: Vec3) = Vec3((p - o) dot r, p.y, (p - o) dot f)
        val items = rec.scene.items.filter { it.obstacle || it.structure }.map { item ->
            val (mn, mx) = when (val sh = item.shape) {
                is Box -> sh.min to sh.max
                is VerticalCylinder -> Vec3(sh.cx - sh.radiusM, sh.yMin, sh.cz - sh.radiusM) to Vec3(sh.cx + sh.radiusM, sh.yMax, sh.cz + sh.radiusM)
                else -> error("unsupported obstacle shape ${item.name}")
            }
            val corners = listOf(mn, mx, Vec3(mn.x, mn.y, mx.z), Vec3(mx.x, mx.y, mn.z)).map(::toTruth)
            fun arr(v: List<Float>) = v.joinToString(", ", "[", "]")
            val lo = listOf(corners.minOf { it.x }, mn.y, corners.minOf { it.z })
            val hi = listOf(corners.maxOf { it.x }, mx.y, corners.maxOf { it.z })
            val removed = item.removeAtS?.let { ", \"removeAtS\": $it" } ?: ""
            val kind = if (item.obstacle) "" else ", \"kind\": \"structure\""
            """    { "name": "${item.name}", "type": "${item.expectedClass ?: HeightClass.FLOOR}"$kind, "min": ${arr(lo)}, "max": ${arr(hi)}$removed }"""
        }
        val version = if (rec.scene.items.any { it.structure && !it.obstacle }) 2 else 1 // kind는 정답 v2
        File(dir, "annotations").mkdirs()
        File(dir, "annotations/obstacles.json").writeText(
            "{\n  \"version\": $version,\n  \"estimated\": false,\n  \"obstacles\": [\n" + items.joinToString(",\n") + "\n  ]\n}\n",
        )
    }
}
