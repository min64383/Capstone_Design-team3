package hearspace.core.situation

/**
 * 앱 말 내보내기의 등급 표(M65 설계 표 3). 상황 말과 앱 상태 말을 한 표로 고른다. 위험 말이 다른 말에 끊기지 않는 것이 핵심이다.
 * 줄 세우지 않는다: 받지 못한 상황 말은 상황이 이어지는 동안 core가 다음 차례에 다시 내고, 상태 말은 앱이 최신 하나만 들고 있다가 다시 시도한다.
 */
object SpeechRank {
    enum class Kind {
        /** 상황 말(M50). 등급 = 상황 우선순위(1 위험, 2 길 안내, 3 정보). */
        SITUATION,
        /** 안전에 관한 상태 말(주변·바닥을 확인할 수 없음, 시작 못 함, 오류로 멈춤). 등급 1. */
        STATUS_SAFETY,
        /** 사용자 조작에 대한 답(준비·시작·일시정지·재개·종료). 등급 2. */
        STATUS_ACTION,
    }

    fun rank(kind: Kind, situationPriority: Int = 2): Int = when (kind) {
        Kind.STATUS_SAFETY -> 1
        Kind.STATUS_ACTION -> 2
        Kind.SITUATION -> situationPriority
    }

    /** 지금 [currentRank](0 = 쉼)를 말하는 중에 [kind]·[rank] 줄이 오면 받을지. 받으면 지금 말을 끊는다. */
    fun admits(currentRank: Int, kind: Kind, rank: Int): Boolean = when {
        currentRank == 0 -> true
        rank == 1 -> currentRank != 1
        kind == Kind.STATUS_ACTION -> currentRank >= 2
        else -> false
    }
}
