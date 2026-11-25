package hearspace.core.screen

import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth
import hearspace.core.types.SoundKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 작동 화면 상태 변환(M45 시안 §3)과 사용 방법 글. */
class UserScreenTest {

    private fun running(
        state: GuidanceState? = GuidanceState.NORMAL,
        paused: Boolean = false,
        map: MapHealth? = MapHealth.OK,
        band: Band? = null,
        az: Float? = null,
        distM: Float = 1.84f,
        heightM: Float = 0f,
        speech: String? = null,
    ) = UserScreen.of(running = true, notice = null, paused = paused, state = state, mapHealth = map,
        cmd = band?.let { cmd(it, az ?: 0f, distM, heightM) }, speech = speech, heightLowM = LOW, heightHighM = HIGH)

    private fun cmd(band: Band, az: Float, distM: Float = 1.84f, heightM: Float = 0f) =
        AudioCmd(1, az, distM, band, SoundKind.FLOOR_PULSE, 0f, true, heightM)

    private fun idle(notice: UserScreen.Notice? = null, speech: String? = null) = UserScreen.of(running = false, notice = notice, paused = false,
        state = null, mapHealth = null, cmd = null, speech = speech, heightLowM = LOW, heightHighM = HIGH)

    /** 모든 안내 중 경우: 상태 × 지도 × 구간 × 일시정지. */
    private fun allRunning() = buildList {
        for (state in listOf(null) + GuidanceState.entries) for (map in listOf(null) + MapHealth.entries)
            for (band in listOf(null) + Band.entries) for (paused in listOf(false, true))
                add(running(state, paused, map, band, if (band == null) null else 30f))
    }

    @Test
    fun `시작 전은 로고와 시작 안내, 설정 단추`() {
        val s = idle()
        assertEquals(UserScreen.Visual.Logo, s.visual)
        assertNull(s.title)
        assertEquals("두 번 탭하여 시작", s.label) // 한 줄: 기호 글 칸(48)에 든다(M72)
        assertTrue(s.idle)
        assertEquals("시작 전", s.spoken) // TalkBack 낱말은 M9부터 그대로
    }

    @Test
    fun `알림은 제목과 사유, 빨강을 쓰지 않음, 사유를 읽음`() {
        val n = UserScreen.Notice.stopped("소리 출력이 멈춰 안내를 멈췄습니다")
        val s = idle(n)
        assertEquals(UserScreen.Visual.Notice, s.visual)
        assertEquals("안내를 멈췄어요", s.title)
        assertEquals(n.reason, s.sub)
        assertEquals(n.reason, s.spoken)
        assertTrue(s.idle)
        assertTrue(s.titleTone != UserScreen.Tone.STOP && s.labelTone != UserScreen.Tone.STOP || s.label == null)
        assertEquals("시작하지 못했어요", UserScreen.Notice.startFailed("카메라 권한이 필요합니다").title)
    }

    @Test
    fun `안내 중에는 설정 단추를 숨긴다`() {
        assertTrue(allRunning().none { it.idle })
    }

    @Test
    fun `길게 누르기는 안내 중에만 안내 종료, 시작 전·알림에는 안내하지 않음`() {
        assertTrue(allRunning().all { ("길게 누르기" to "안내 종료") in it.hints })
        val idle = idle()
        val notice = idle(UserScreen.Notice.startFailed("x"))
        for (s in listOf(idle, notice)) assertTrue(s.hints.none { it.first == "길게 누르기" }, s.hints.toString())
    }

    @Test
    fun `일시정지가 다른 상태보다 먼저`() {
        for (s in allRunning().filter { it.spoken == "일시정지" }) assertEquals(UserScreen.Visual.Paused, s.visual)
        assertEquals("일시정지", running(paused = true, band = Band.STOP, az = 0f).spoken)
        assertEquals("일시정지", running(state = GuidanceState.PAUSED).spoken)
        assertEquals("재개", running(paused = true).hints.first().second)
    }

    @Test
    fun `안내 출력이 없거나 UNKNOWN이면 준비 중`() {
        assertEquals(UserScreen.Visual.Preparing, running(state = null).visual)
        assertEquals(UserScreen.Visual.Preparing, running(state = GuidanceState.UNKNOWN, band = Band.STOP, az = 0f).visual)
        assertEquals("준비 중", running(state = null).spoken)
    }

    @Test
    fun `바닥을 잃으면 주의 색 제목, 음원이 없으면 세모`() {
        val s = running(map = MapHealth.DEGRADED)
        assertEquals("바닥 확인 중", s.spoken)
        assertEquals(UserScreen.Tone.CAUTION, s.titleTone)
        assertEquals(UserScreen.Visual.FloorCheck, s.visual)
        assertEquals("지금은 물체를 볼 수 없어요", s.sub)
        val withSource = running(map = MapHealth.DEGRADED, band = Band.WARN, az = -20f)
        assertEquals(UserScreen.Visual.Arrow(-20f, stop = false), withSource.visual)
        assertNull(withSource.sub)
    }

    @Test
    fun `주의와 멈춤은 모양과 글자로도 구분`() {
        val warn = running(band = Band.WARN, az = 35f)
        val stop = running(band = Band.STOP, az = -10f)
        assertEquals(UserScreen.Visual.Arrow(35f, stop = false), warn.visual)
        assertEquals(UserScreen.Visual.Arrow(-10f, stop = true), stop.visual)
        assertEquals("주의", warn.label)
        assertEquals("멈춤", stop.label)
        assertEquals(UserScreen.Tone.CAUTION, warn.labelTone)
        assertEquals(UserScreen.Tone.STOP, stop.labelTone)
        assertEquals("안내 중", warn.spoken)
    }

    @Test
    fun `무음이나 명령 없음은 가운데를 비우고 안전하다고 말하지 않음`() {
        for (s in listOf(running(), running(band = Band.SILENT, az = 10f))) {
            assertEquals(UserScreen.Visual.None, s.visual)
            assertNull(s.label)
            assertEquals("안내 중", s.title)
            assertEquals(UserScreen.Tone.GUIDE, s.titleTone)
        }
        val words = allRunning().flatMap { listOfNotNull(it.title, it.sub, it.label, it.spoken) }
        assertTrue(words.none { "없음" in it || "안전" in it }, words.toSet().toString())
    }

    // ---- M72 정보 줄·말 줄 (milepost demo-relay/M72-design 표 4·5) ----

    private fun hour(az: Float) = UserScreen.detailOf(cmd(Band.WARN, az), LOW, HIGH).direction

    @Test
    fun `정보 줄은 화살표가 있을 때만, 없으면 비움(장애물 없음으로 보이지 않게)`() {
        for (s in allRunning()) assertEquals(s.visual is UserScreen.Visual.Arrow, s.detail != null, "${s.spoken} ${s.visual}")
        assertNull(running(band = Band.SILENT, az = 10f).detail)
        assertNull(idle().detail)
        assertEquals(UserScreen.Detail("1시 방향", "1.8 m", "가슴 높이"), running(band = Band.WARN, az = 30f).detail)
        assertEquals("1시 방향", running(map = MapHealth.DEGRADED, band = Band.STOP, az = 30f).detail?.direction)
    }

    @Test
    fun `방향은 30도마다 한 시, 12시가 정면, 경계는 반올림`() {
        assertEquals("12시 방향", hour(0f))
        assertEquals("12시 방향", hour(14.9f))
        assertEquals("1시 방향", hour(15.1f))
        assertEquals("11시 방향", hour(-15.1f))
        assertEquals("12시 방향", hour(-14.9f))
        assertEquals("3시 방향", hour(90f))
        assertEquals("9시 방향", hour(-90f))
        assertEquals("6시 방향", hour(180f))
        assertEquals("6시 방향", hour(-180f))
    }

    @Test
    fun `거리는 0점1 m, 높이는 소리 높낮이 범위의 세 구간`() {
        fun d(distM: Float = 1.84f, h: Float = 0f) = UserScreen.detailOf(cmd(Band.WARN, 0f, distM, h), LOW, HIGH)
        assertEquals("1.8 m", d(1.84f).distance)
        assertEquals("0.8 m", d(0.76f).distance)
        assertEquals("2.5 m", d(2.46f).distance)
        // 범위 −1 ~ +1 m를 셋으로: 경계 −1/3, +1/3
        assertEquals("허리 아래", d(h = -0.34f).height)
        assertEquals("가슴 높이", d(h = -0.32f).height)
        assertEquals("가슴 높이", d(h = 0.32f).height)
        assertEquals("머리 높이", d(h = 0.34f).height)
        assertEquals("허리 아래", d(h = -3f).height) // 범위 밖은 끝 구간
        assertEquals("머리 높이", d(h = 3f).height)
        assertNull(d(h = Float.NaN).height) // 높이 값이 없으면 뺀다
    }

    @Test
    fun `말 줄은 안내 중에 말하는 문장 그대로, 시작 전에는 없음`() {
        assertEquals("블록 끝, 2미터", running(speech = "블록 끝, 2미터").speech)
        assertEquals("안내를 준비합니다.", running(state = null, speech = "안내를 준비합니다.").speech)
        assertEquals("안내를 일시정지합니다.", running(paused = true, speech = "안내를 일시정지합니다.").speech)
        assertEquals("바닥을 확인할 수 없습니다. 잠시 멈춰 주세요.", running(map = MapHealth.DEGRADED, speech = "바닥을 확인할 수 없습니다. 잠시 멈춰 주세요.").speech)
        assertNull(running(speech = " ").speech)
        assertNull(running().speech)
        assertNull(idle(speech = "앱이 시작했습니다").speech)
        // 말 줄이 생겨도 다른 칸은 그대로
        val quiet = running(band = Band.WARN, az = 30f)
        val talking = running(band = Band.WARN, az = 30f, speech = "블록 위 물체, 2미터")
        assertEquals(quiet, talking.copy(speech = null))
    }

    private companion object {
        /** 앱 기본 설정 sonify.heightLowM·heightHighM. */
        const val LOW = -1f
        const val HIGH = 1f
    }
}
