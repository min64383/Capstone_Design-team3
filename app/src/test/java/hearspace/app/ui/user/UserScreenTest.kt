package hearspace.app.ui.user

import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth
import hearspace.core.types.SonifyMapping
import hearspace.core.types.SonifyMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    ) = UserScreen.of(running = true, notice = null, paused = paused, state = state, mapHealth = map, band = band, azimuthDeg = az)

    /** 모든 안내 중 경우: 상태 × 지도 × 구간 × 일시정지. */
    private fun allRunning() = buildList {
        for (state in listOf(null) + GuidanceState.entries) for (map in listOf(null) + MapHealth.entries)
            for (band in listOf(null) + Band.entries) for (paused in listOf(false, true))
                add(running(state, paused, map, band, if (band == null) null else 30f))
    }

    @Test
    fun `시작 전은 로고와 시작 안내, 설정 단추`() {
        val s = UserScreen.of(running = false, notice = null, paused = false, state = null, mapHealth = null, band = null, azimuthDeg = null)
        assertEquals(UserScreen.Visual.Logo, s.visual)
        assertNull(s.title)
        assertEquals("두 번 탭하여\n시작", s.label)
        assertTrue(s.idle)
        assertEquals("시작 전", s.spoken) // TalkBack 낱말은 M9부터 그대로
    }

    @Test
    fun `알림은 제목과 사유, 빨강을 쓰지 않음, 사유를 읽음`() {
        val n = UserScreen.Notice.stopped("소리 출력이 멈춰 안내를 멈췄습니다")
        val s = UserScreen.of(running = false, notice = n, paused = false, state = null, mapHealth = null, band = null, azimuthDeg = null)
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
        val idle = UserScreen.of(running = false, notice = null, paused = false, state = null, mapHealth = null, band = null, azimuthDeg = null)
        val notice = UserScreen.of(running = false, notice = UserScreen.Notice.startFailed("x"), paused = false, state = null, mapHealth = null, band = null, azimuthDeg = null)
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

    @Test
    fun `사용 방법 글은 소리 방식을 따르고 무음이 안전이 아님을 알린다`() {
        val continuous = UserTexts.howTo(SonifyMode.RISK_CONTINUOUS, SonifyMapping.DISTANCE_HEIGHT)
        val pulse = UserTexts.howTo(SonifyMode.PULSE, SonifyMapping.LEGACY_RISK)
        assertTrue("높은 곳에 있는 장애물일수록 음이 높습니다" in continuous)
        assertTrue("빨라지고" in pulse)
        assertFalse("빨라지고" in continuous)
        for (t in listOf(continuous, pulse, UserTexts.howTo(SonifyMode.RISK_CONTINUOUS, SonifyMapping.LEGACY_RISK))) {
            assertTrue("소리가 나지 않는다고 앞이 안전하다는 뜻은 아닙니다" in t)
            assertTrue("가슴 하네스" in t)
        }
    }
}
