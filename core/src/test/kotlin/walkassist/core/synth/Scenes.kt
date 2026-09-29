package walkassist.core.synth

import walkassist.core.geometry.Vec3
import walkassist.core.types.HeightClass

/** 합성 장면 한 개의 전체 사양(부록 B). */
data class SceneSpec(val id: String, val scene: Scene, val walk: Walk, val noise: Noise = Noise(), val depthEveryNFrames: Int = 1) {
    /** 생성. */
    fun generate() = SyntheticGenerator.generate(scene, walk, noise, depthEveryNFrames)
}

/**
 * 부록 B 합성 장면 SC-01~SC-13.
 * 공통: 바닥 y = 0, 카메라 높이 1 m, 시작 카메라 (0, 1, 0), 2 s 정지 후 월드 −Z로 1 m/s 보행.
 * 거리는 시작 카메라 위치에서 보행선(−Z)을 따라 잰다. 장애물 크기는 가설(실측 장면 S01~S10과 비슷하게).
 */
object Scenes {
    private val floor = SceneItem("floor", HorizontalPlane(0f), obstacle = false)

    /** 보행선 위 [distM] 지점(앞면)에 놓인 상자. 기본은 의자 크기(폭 0.45, 깊이 0.45, 높이 0.8). */
    fun boxOnLine(distM: Float, heightM: Float = 0.8f, name: String = "box", depthM: Float = 0.45f) = SceneItem(
        name,
        Box(Vec3(-0.225f, 0f, -distM - depthM), Vec3(0.225f, heightM, -distM)),
        obstacle = true,
        expectedClass = HeightClass.FLOOR, // 바닥에서 시작하는 물체: 통로 안 최저점이 바닥(§7.4)
    )

    /** 보행선에서 [xM] 떨어진 곳의 벽(두께 0.1, 높이 2.5, 길이 10). */
    fun wall(xM: Float, name: String) = SceneItem(
        name,
        if (xM > 0) Box(Vec3(xM, 0f, -10f), Vec3(xM + 0.1f, 2.5f, 1f)) else Box(Vec3(xM - 0.1f, 0f, -10f), Vec3(xM, 2.5f, 1f)),
        obstacle = false,
    )

    /** 기본 보행: 2 s 정지 + 걸어서 상자 앞 0.5 m 부근까지. */
    private fun walkTo(distM: Float, extra: Walk.() -> Walk = { this }) =
        Walk(durationS = 2f + (distM - 0.5f)).extra()

    /** SC-01 바닥만. */
    val SC01 get() = SceneSpec("SC-01", Scene(listOf(floor)), Walk(durationS = 5f))

    /** SC-02 보행선 위 2 m 상자. */
    val SC02 get() = SceneSpec("SC-02", Scene(listOf(floor, boxOnLine(2f))), walkTo(2f))

    /** SC-03 통로 양옆 벽(보행선에서 ±0.8 m, 통로 폭 0.8 밖). */
    val SC03 get() = SceneSpec("SC-03", Scene(listOf(floor, wall(0.8f, "wall_r"), wall(-0.8f, "wall_l"))), Walk(durationS = 5f))

    /** 보행선 앞 [distM] 지점의 끝 벽(폭 6, 높이 2.5). */
    fun endWall(distM: Float) = SceneItem("end_wall", Box(Vec3(-3f, 0f, -distM - 0.1f), Vec3(3f, 2.5f, -distM)), obstacle = false)

    /**
     * SC-04 상자가 3 s에 제거됨(이동 물체 흔적). 6 m 앞에 끝 벽이 있다:
     * 빈 공간 감쇠는 복셀 뒤에 유효 깊이가 보여야 일어나므로(무효 = 관측 없음), 뒤가 트인 곳에서는 흔적이 남는다.
     */
    val SC04 get() = SceneSpec(
        "SC-04",
        Scene(listOf(floor, boxOnLine(3f).copy(removeAtS = 3f), endWall(6f))),
        Walk(durationS = 5f),
    )

    /** SC-05 테이블 모서리 돌출: 상판(높이 0.72~0.75)이 오른쪽에서 보행선 쪽으로 x = 0.2까지 들어옴, 다리는 통로 밖. */
    val SC05 get() = SceneSpec(
        "SC-05",
        Scene(
            listOf(
                floor,
                SceneItem("table_top", Box(Vec3(0.2f, 0.72f, -2.8f), Vec3(1.2f, 0.75f, -2f)), obstacle = true, expectedClass = HeightClass.BODY),
                SceneItem("table_leg", Box(Vec3(1.1f, 0f, -2.1f), Vec3(1.15f, 0.72f, -2.05f)), obstacle = false),
            ),
        ),
        walkTo(2f),
    )

    /** SC-06 벽(x = 0.6)에 붙은 머리 높이 판(1.55~1.60 m, 벽에서 0.3 m 돌출 → 통로 안 x 0.3~0.4). */
    val SC06 get() = SceneSpec(
        "SC-06",
        Scene(
            listOf(
                floor,
                wall(0.6f, "wall_r"),
                SceneItem("head_plate", Box(Vec3(0.3f, 1.55f, -2.4f), Vec3(0.6f, 1.6f, -2f)), obstacle = true, expectedClass = HeightClass.HEAD),
            ),
        ),
        walkTo(2f),
    )

    /**
     * SC-07 1.5 m 지점 낮은 상자(높이 0.3, 깊이 0.3) → 1.2 m 걸어 0.3 m 앞까지 접근하며 시야 밖으로 나감.
     * 세로 파지·10° 숙임이면 아래쪽 시야 끝이 약 42.7° → 높이 0.3 m 윗면은 0.76 m 안쪽부터 안 보인다.
     */
    val SC07 get() = SceneSpec(
        "SC-07",
        Scene(listOf(floor, boxOnLine(1.5f, heightM = 0.3f, name = "low_box", depthM = 0.3f))),
        Walk(durationS = 2f + 1.2f),
    )

    /** SC-08 SC-02 + 손목 요 흔들림 ±20°. */
    val SC08 get() = SC02.copy(id = "SC-08", walk = walkTo(2f) { copy(wristYawAmpDeg = 20f, bobAmpM = 0.02f) })

    /** SC-09 SC-02 + 손–머리 좌우 오프셋 0.2 m(폰이 몸 오른쪽). */
    val SC09 get() = SC02.copy(id = "SC-09", walk = walkTo(2f) { copy(gripOffsetM = Vec3(-0.2f, 0.5f, -0.3f)) })

    /** SC-10 SC-02 + 1 s 추적 상실(2.2~3.2 s). */
    val SC10 get() = SC02.copy(id = "SC-10", noise = Noise(trackingLossS = listOf(2.2f..3.2f)))

    /** SC-11 SC-02 + 깊이 스케일 편향 +10%. */
    val SC11 get() = SC02.copy(id = "SC-11", noise = Noise(depthScaleBias = 0.1f))

    /** SC-12 SC-02 + 추적 중 월드 좌표 점프(2.5 s, 3 m·60°). */
    val SC12 get() = SC02.copy(id = "SC-12", noise = Noise(poseJumps = listOf(PoseJump(2.5f, Vec3(3f, -0.5f, 1f), 60f))))

    /** SC-13 SC-02 + 깊이 정지 1 s(2.2~3.2 s). */
    val SC13 get() = SC02.copy(id = "SC-13", noise = Noise(depthFreezeS = listOf(2.2f..3.2f)))

    /** 전체 목록. */
    val ALL get() = listOf(SC01, SC02, SC03, SC04, SC05, SC06, SC07, SC08, SC09, SC10, SC11, SC12, SC13)
}
