package com.phonelock.shared.lock

/**
 * 관리 > 타이머 — "이거까지만 할게요!"(142차, 사용자 요청).
 *
 * 사용자가 "앞으로 N분만 더 쓰고([lockStartAtMillis]까지는 자유), 그 뒤 M분은 잠근다"를 스스로 약속한다.
 * 자유 시간을 0으로 두면 누르는 즉시 잠기므로, "지금부터 M분 잠금"도 같은 기능 하나로 다룬다.
 *
 * 잠그는 범위는 둘 중 하나다.
 * - [wholeDevice] = true: 기기 전체를 잠그고 [apps]/[sites]에 든 것만 허용한다(전화·키보드처럼 기기를 쓰는 데
 *   꼭 필요한 것은 플랫폼 쪽이 항상 허용한다).
 * - [wholeDevice] = false: [apps]/[sites]에 든 것만 잠근다.
 *
 * 한번 시작하면 자유 시간 중이든 잠금 중이든 [level]에 맞는 해제 절차([UnlockLevel])를 거쳐야만 취소할 수 있다.
 * 시각은 전부 기기 시계 기준 epoch millis이고, 기기를 껐다 켜도 이어지도록 문자열([encode])로 저장한다.
 *
 * **다른 기기와 동기화한다(143차)** — 약속의 시간·범위(기기 전체/고른 것만)·난이도·휴식 해제 여부만 오가고, 앱·사이트
 * 목록은 기기마다 다르므로([apps]는 안드로이드 패키지명/데스크탑 프로세스 이름) 받는 기기가 자기 마지막 설정
 * ([LockTimerPreset])의 목록을 쓴다. 규칙은 [LockTimerSync] 참고.
 *
 * [pomodoroBreakUnlock]은 "뽀모도로 휴식 중엔 이 약속의 잠금을 잠시 푼다"(143차) — 시작할 때 정하며 도중에 바꿀 수
 * 없다(도중에 켜면 휴식 버튼만 눌러 잠금을 피하는 길이 되므로). 휴식이 끝나면 남은 잠금 시간이 이어진다.
 */
data class LockTimer(
    val startedAtMillis: Long,
    val lockStartAtMillis: Long,
    val lockEndAtMillis: Long,
    val wholeDevice: Boolean,
    val apps: Set<String>,
    val sites: Set<String>,
    val level: Int,
    val pomodoroBreakUnlock: Boolean = false
) {
    enum class Phase { FREE, LOCKED, DONE }

    fun phaseAt(nowMillis: Long): Phase = when {
        nowMillis < lockStartAtMillis -> Phase.FREE
        nowMillis < lockEndAtMillis -> Phase.LOCKED
        else -> Phase.DONE
    }

    fun isLockedAt(nowMillis: Long): Boolean = phaseAt(nowMillis) == Phase.LOCKED

    /** 지금 단계가 끝날 때까지 남은 시간(자유 시간이면 잠금 시작까지, 잠금 중이면 잠금 끝까지). */
    fun remainingMillis(nowMillis: Long): Long = when (phaseAt(nowMillis)) {
        Phase.FREE -> lockStartAtMillis - nowMillis
        Phase.LOCKED -> lockEndAtMillis - nowMillis
        Phase.DONE -> 0L
    }

    /**
     * 잠금 중에 이 앱(안드로이드 패키지명 / 데스크탑 프로세스 이름)이 막히는지. 데스크탑은 대소문자를 가리지
     * 않으므로 저장할 때와 물어볼 때 모두 소문자로 맞춰서 넘긴다. 항상 허용되는 시스템 앱은 호출부가 먼저 거른다.
     */
    fun blocksApp(name: String): Boolean = if (wholeDevice) name !in apps else name in apps

    /** 데스크탑 브라우저 확장이 물어보는 호스트 이름 기준 — 등록한 도메인과 그 하위 도메인이 같은 사이트다. */
    fun blocksHost(hostname: String): Boolean {
        val host = hostname.lowercase()
        val listed = sites.any { site ->
            val domain = site.lowercase()
            host == domain || host.endsWith(".$domain")
        }
        return if (wholeDevice) !listed else listed
    }

    /** 안드로이드 접근성 서비스가 읽은 주소창 글자 기준 — 차단 규칙의 사이트 판정과 같은 "도메인이 들어 있는가". */
    fun blocksAddressText(addressText: String): Boolean {
        val listed = sites.any { addressText.contains(it, ignoreCase = true) }
        return if (wholeDevice) !listed else listed
    }

    fun encode(): String = listOf(
        FORMAT_VERSION,
        startedAtMillis.toString(),
        lockStartAtMillis.toString(),
        lockEndAtMillis.toString(),
        if (wholeDevice) "1" else "0",
        level.toString(),
        apps.joinToString(ITEM_SEPARATOR),
        sites.joinToString(ITEM_SEPARATOR),
        if (pomodoroBreakUnlock) "1" else "0"
    ).joinToString(FIELD_SEPARATOR)

    companion object {
        /** 입력 실수(분 대신 시간을 넣는 등)로 해제할 수 없는 잠금이 며칠씩 걸리지 않도록 둔 상한. */
        const val MAX_FREE_MINUTES = 24 * 60
        const val MIN_LOCK_MINUTES = 1
        const val MAX_LOCK_MINUTES = 24 * 60

        /** 잠금이 시작되기 이만큼 전에 한 번 미리 알린다. */
        const val WARNING_BEFORE_LOCK_MILLIS = 60_000L

        /** 143차에 "2"로 올렸다(휴식 해제 필드 추가). 진행 중이던 옛 약속("1", 8필드)도 [decode]가 그대로 읽는다. */
        private const val FORMAT_VERSION = "2"
        private const val LEGACY_FORMAT_VERSION = "1"
        private const val FIELD_SEPARATOR = "\n"
        private const val ITEM_SEPARATOR = "\t"

        fun create(
            nowMillis: Long,
            freeMinutes: Int,
            lockMinutes: Int,
            wholeDevice: Boolean,
            apps: Set<String>,
            sites: Set<String>,
            level: Int,
            pomodoroBreakUnlock: Boolean = false
        ): LockTimer {
            val free = freeMinutes.coerceIn(0, MAX_FREE_MINUTES)
            val lock = lockMinutes.coerceIn(MIN_LOCK_MINUTES, MAX_LOCK_MINUTES)
            val lockStart = nowMillis + free * 60_000L
            return LockTimer(
                startedAtMillis = nowMillis,
                lockStartAtMillis = lockStart,
                lockEndAtMillis = lockStart + lock * 60_000L,
                wholeDevice = wholeDevice,
                apps = cleanItems(apps),
                sites = cleanItems(sites),
                level = UnlockLevel.clamp(level),
                pomodoroBreakUnlock = pomodoroBreakUnlock
            )
        }

        /** 저장된 문자열을 되살린다. 비었거나 형식이 다르면 null — 호출부는 "타이머 없음"으로 본다. */
        fun decode(text: String?): LockTimer? {
            if (text.isNullOrEmpty()) return null
            val fields = text.split(FIELD_SEPARATOR)
            val legacy = fields.size == 8 && fields[0] == LEGACY_FORMAT_VERSION
            if (!legacy && !(fields.size == 9 && fields[0] == FORMAT_VERSION)) return null
            val startedAt = fields[1].toLongOrNull() ?: return null
            val lockStartAt = fields[2].toLongOrNull() ?: return null
            val lockEndAt = fields[3].toLongOrNull() ?: return null
            val level = fields[5].toIntOrNull() ?: return null
            if (lockEndAt <= lockStartAt) return null
            return LockTimer(
                startedAtMillis = startedAt,
                lockStartAtMillis = lockStartAt,
                lockEndAtMillis = lockEndAt,
                wholeDevice = fields[4] == "1",
                apps = splitItems(fields[6]),
                sites = splitItems(fields[7]),
                level = UnlockLevel.clamp(level),
                pomodoroBreakUnlock = !legacy && fields[8] == "1"
            )
        }

        private fun splitItems(field: String): Set<String> =
            if (field.isEmpty()) emptySet() else field.split(ITEM_SEPARATOR).filter { it.isNotEmpty() }.toSet()

        /** 구분자로 쓰는 탭·줄바꿈이 값에 섞이면 저장본이 깨지므로 그런 항목은 버린다. */
        private fun cleanItems(items: Set<String>): Set<String> =
            items.map { it.trim() }.filter { it.isNotEmpty() && !it.contains('\t') && !it.contains('\n') }.toSet()
    }
}

/**
 * 타이머 화면에 마지막으로 넣었던 값 — 다음에 열었을 때 그대로 채워 준다. 전체 잠금의 "허용 목록"과 특정 잠금의
 * "잠글 목록"은 뜻이 반대라 따로 기억한다(방식을 바꿔도 서로의 목록을 덮어쓰지 않는다).
 */
data class LockTimerPreset(
    val freeMinutes: Int = 10,
    val lockMinutes: Int = 60,
    val wholeDevice: Boolean = true,
    val level: Int = UnlockLevel.DEFAULT,
    val allowedApps: Set<String> = emptySet(),
    val allowedSites: Set<String> = emptySet(),
    val targetApps: Set<String> = emptySet(),
    val targetSites: Set<String> = emptySet(),
    /** "뽀모도로 휴식 중엔 잠금 잠시 풀기" 마지막 선택(143차) — 새 약속의 [LockTimer.pomodoroBreakUnlock]이 된다. */
    val pomodoroBreakUnlock: Boolean = false
) {
    fun encode(): String = listOf(
        "2",
        freeMinutes.toString(),
        lockMinutes.toString(),
        if (wholeDevice) "1" else "0",
        level.toString(),
        allowedApps.joinToString("\t"),
        allowedSites.joinToString("\t"),
        targetApps.joinToString("\t"),
        targetSites.joinToString("\t"),
        if (pomodoroBreakUnlock) "1" else "0"
    ).joinToString("\n")

    companion object {
        fun decode(text: String?): LockTimerPreset {
            if (text.isNullOrEmpty()) return LockTimerPreset()
            val fields = text.split("\n")
            // 142차 형식("1", 9필드)도 읽는다 — 휴식 해제 필드만 없다.
            val legacy = fields.size == 9 && fields[0] == "1"
            if (!legacy && !(fields.size == 10 && fields[0] == "2")) return LockTimerPreset()
            fun items(field: String): Set<String> =
                if (field.isEmpty()) emptySet() else field.split("\t").filter { it.isNotEmpty() }.toSet()
            return LockTimerPreset(
                freeMinutes = (fields[1].toIntOrNull() ?: 10).coerceIn(0, LockTimer.MAX_FREE_MINUTES),
                lockMinutes = (fields[2].toIntOrNull() ?: 60).coerceIn(LockTimer.MIN_LOCK_MINUTES, LockTimer.MAX_LOCK_MINUTES),
                wholeDevice = fields[3] == "1",
                level = UnlockLevel.clamp(fields[4].toIntOrNull() ?: UnlockLevel.DEFAULT),
                allowedApps = items(fields[5]),
                allowedSites = items(fields[6]),
                targetApps = items(fields[7]),
                targetSites = items(fields[8]),
                pomodoroBreakUnlock = !legacy && fields[9] == "1"
            )
        }
    }
}

/**
 * 타이머를 도중에 풀 때 거쳐야 하는 절차의 난이도(142차, 사용자 확정: 4단계이고 최고 단계는 해제 불가).
 * 자유 시간 중에 취소할 때도 같은 절차를 거친다 — 그러지 않으면 잠기기 직전에 취소하는 것으로 약속이 무력해진다.
 */
object UnlockLevel {
    const val MIN = 1
    const val MAX = 4
    const val DEFAULT = 2

    /** 1단계: 이만큼 기다리면 풀린다. */
    const val LEVEL1_WAIT_SECONDS = 30
    /** 3단계: 확인 질문을 전부 통과한 뒤 이만큼 더 기다려야 풀린다. */
    const val LEVEL3_WAIT_SECONDS = 5 * 60

    fun clamp(level: Int): Int = level.coerceIn(MIN, MAX)

    /** 시간이 끝나기 전에 풀 수 있는 단계인지 — 4단계는 끝날 때까지 풀 수 없다. */
    fun canUnlock(level: Int): Boolean = clamp(level) < MAX

    /** 확인 질문(회유 멘트 전체)을 통과해야 하는 단계인지. */
    fun needsQuestions(level: Int): Boolean = clamp(level) in 2..3

    /** 해제 직전에 화면을 지키며 기다려야 하는 시간(초). 0이면 기다림 없음. */
    fun waitSeconds(level: Int): Int = when (clamp(level)) {
        1 -> LEVEL1_WAIT_SECONDS
        3 -> LEVEL3_WAIT_SECONDS
        else -> 0
    }

    fun label(level: Int): String = when (clamp(level)) {
        1 -> "Lv.1 가벼움"
        2 -> "Lv.2 보통"
        3 -> "Lv.3 강함"
        else -> "Lv.4 최강"
    }

    fun description(level: Int, questionCount: Int): String = when (clamp(level)) {
        1 -> "${LEVEL1_WAIT_SECONDS}초를 기다리면 풀 수 있어요."
        2 -> "확인 질문 ${questionCount}개를 모두 통과해야 풀 수 있어요."
        3 -> "확인 질문 ${questionCount}개를 통과한 뒤 ${LEVEL3_WAIT_SECONDS / 60}분을 더 기다려야 풀 수 있어요."
        else -> "시간이 끝날 때까지 풀 수 없어요."
    }
}

/** "1시간 5분" / "12분 30초" / "45초" — 타이머 화면과 잠금 화면의 남은 시간 표기. */
fun formatLockRemaining(millis: Long): String {
    val totalSeconds = ((millis + 999) / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}시간 ${minutes}분"
        minutes > 0 -> "${minutes}분 ${seconds}초"
        else -> "${seconds}초"
    }
}
