package hearspace.app.ui.user

import hearspace.core.types.SonifyMapping
import hearspace.core.types.SonifyMode

/** 사용자에게 보이고 읽히는 긴 글(M45). 소리 설명은 설정의 소리 방식에 맞춰 고른다(설정이 바뀌면 글도 따라간다). */
object UserTexts {

    /** ARCore Additional Terms의 고지(docs/LICENSES.md). 문구는 정해져 있지 않고 "ARCore 기능 포함 + Google 약관·개인정보처리방침 적용"을 알리면 된다. */
    const val ARCORE_NOTICE = "이 앱은 Google의 ARCore 기능을 포함합니다. ARCore 사용에는 Google 서비스 약관(policies.google.com/terms)과 " +
        "Google 개인정보처리방침(google.com/policies/privacy)의 최신 버전이 적용됩니다"

    /** 앱에 들어 있는 다른 사람의 작품. 뒤에 HRTF 고지(assets/hrtf/NOTICE.txt)와 Apache 2.0 전문을 붙여 보인다. */
    const val LICENSES_INTRO = "이 앱에 들어 있는 다른 사람의 작품과 라이선스입니다.\n\n" +
        "• SADIE II HRTF 데이터 — University of York, Apache License 2.0 (아래 고지와 전문)\n" +
        "• Kotlin 표준 라이브러리 — JetBrains, Apache License 2.0\n" +
        "• ARCore SDK — Google, ARCore 추가 서비스 약관(developers.google.com/ar/develop/terms)\n\n"

    /** 설정 > 사용 방법. 장애물 소리 설명은 [mode]·[mapping](설정 `sonify`)에 맞춘다. */
    fun howTo(mode: SonifyMode, mapping: SonifyMapping): String {
        val obstacle = when {
            mode == SonifyMode.PULSE ->
                "장애물이 있는 방향에서 짧은 소리가 되풀이됩니다. 가까워질수록 빨라지고, 멈춰야 할 만큼 가까우면 아주 빠르고 커집니다. " +
                    "머리 높이의 장애물은 높고 날카로운 소리로 알립니다."
            mapping == SonifyMapping.DISTANCE_HEIGHT ->
                "장애물이 있는 방향에서 이어지는 소리가 납니다. 가까울수록 소리가 커지고, 높은 곳에 있는 장애물일수록 음이 높습니다. " +
                    "장애물에 다가가는 동안 소리가 나고, 멈춰야 할 만큼 가까우면 소리가 계속 납니다."
            else ->
                "장애물이 있는 방향에서 이어지는 소리가 납니다. 가까울수록 음이 높고 커지며, 머리 높이의 장애물은 더 높은 음입니다. " +
                    "장애물에 다가가는 동안 소리가 나고, 멈춰야 할 만큼 가까우면 소리가 계속 납니다."
        }
        return "HEARSPACE는 휴대폰 뒷면 카메라로 앞을 보고, 가까운 장애물을 헤드폰 소리로 알려 줍니다.\n\n" +
            "착용\n휴대폰을 가슴 하네스에 끼워 뒷면 카메라가 앞을 보게 하고, 헤드폰을 씁니다.\n\n" +
            "조작\n화면 아무 데나 두 번 탭하면 시작합니다. 안내 중에 두 번 탭하면 일시정지하고, 다시 두 번 탭하면 이어서 안내합니다. " +
            "안내 중에 화면을 길게 누르면 안내를 끝내고 시작 화면으로 돌아옵니다. 앱을 나갈 때는 휴대폰의 뒤로 가기나 홈 버튼을 씁니다. " +
            "TalkBack을 쓸 때는 두 번 탭이 시작과 일시정지, 두 번 탭한 뒤 누르고 있기가 안내 종료입니다.\n\n" +
            "알림음\n시작은 오르는 세 음, 준비 완료는 짧게 오르는 두 음, 일시정지는 같은 음 두 번, 확인할 수 없음은 낮게 내려가는 두 음입니다. " +
            "준비하는 동안과 바닥을 확인하는 동안에는 짧고 부드러운 한 음이 되풀이됩니다. 상태가 바뀔 때는 진동도 함께 옵니다.\n\n" +
            "장애물 소리\n$obstacle\n\n" +
            "꼭 알아 두세요\n소리가 나지 않는다고 앞이 안전하다는 뜻은 아닙니다. 카메라가 보지 못한 장애물이나 움직이는 사람과 차는 알리지 못할 수 있습니다."
    }
}
