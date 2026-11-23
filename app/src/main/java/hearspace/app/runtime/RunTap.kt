package hearspace.app.runtime

import hearspace.core.screen.UserScreen
import hearspace.core.types.AlertKind

/**
 * 작동 화면의 출력을 밖에서 보는 고리(M67 설계 3.2~3.3절). 데모용 bench 빌드의 중계 업로더만 [current]를 채운다(bench `RelayApp`).
 * debug·release 빌드에서는 늘 비어 있어 아무것도 하지 않는다. 네트워크 코드와 인터넷 권한은 bench 폴더에만 있다.
 * [alert]만 오디오 스레드에서, 나머지는 UI 스레드에서 부른다. 구현은 막지 않고 바로 돌아와야 한다.
 */
interface RunTap {
    /** 작동 화면이 앞에 나옴. [clockNs]는 파이프라인 시계, [configLayers]는 앱이 합친 설정 층 글(기본이 먼저), [notice]는 시작 화면 고지 글. */
    fun resumed(clockNs: () -> Long, configLayers: List<String>, notice: String?)

    /** 안내 시작(파이프라인)과 끝(null). */
    fun run(pipeline: LivePipeline?)

    /** 화면이 바뀌어 다시 그릴 때. */
    fun screen(screen: UserScreen)

    /** TTS 이벤트(줄마다 시작·끝). */
    fun speech(event: AndroidTts.Event)

    /** 파이프라인 알림(오디오 스레드). */
    fun alert(kind: AlertKind)

    fun paused()

    companion object {
        @Volatile
        var current: RunTap? = null
    }
}
