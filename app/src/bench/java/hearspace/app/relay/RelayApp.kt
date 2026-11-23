package hearspace.app.relay

import android.app.Application
import hearspace.app.runtime.RunTap

/** bench 빌드의 Application(M67): 앱이 시작할 때 작동 화면의 고리에 데모 중계를 단다. 켜기는 기기 파일이 정한다([RelayTap]). */
class RelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RunTap.current = RelayTap(this)
    }
}
