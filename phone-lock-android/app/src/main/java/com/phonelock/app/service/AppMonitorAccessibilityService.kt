package com.phonelock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.GroupSite
import com.phonelock.app.data.LockTimerSyncResult
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.enforcedLockTimer
import com.phonelock.app.data.syncLockTimer
import com.phonelock.app.ui.BlockActivity
import com.phonelock.app.ui.ConfirmOpenActivity
import com.phonelock.app.ui.FullLockActivity
import com.phonelock.shared.MOTIVATIONAL_QUOTES
import com.phonelock.app.ui.StudyLockActivity
import com.phonelock.shared.lock.LockTimer
import com.phonelock.shared.lock.formatLockRemaining
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TICK_MS = 2000L
// 다른 기기가 올린 "공부 타이머 실행 중" 신호가 이 시간보다 오래 갱신되지 않았으면 무시한다
// (StudyTimerScreen의 미러 표시와 같은 값이고, 데스크탑 EnforcementService.REMOTE_STUDY_SIGNAL_STALE_MS와도 동일).
// 신호를 올리던 기기가 타이머를 정지하지 않고 꺼지면 timerActive:true가 유령처럼 남아 이 기기가
// 영구히 잠길 수 있는데, 그걸 막는 신선도 컷오프다.
private const val REMOTE_STUDY_SIGNAL_STALE_MS = 20 * 60 * 1000L
// 잠긴 앱 백그라운드 재생 차단(125차): 루프가 잠깐 밀려도 재생 시간을 한 번에 과하게 더하지 않도록 한 번에 셀 최대 간격,
// 사용자가 계속 다시 재생을 눌러도 안내 토스트가 도배되지 않도록 앱별 최소 간격.
private const val MAX_BACKGROUND_USAGE_STEP_MS = 10_000L
private const val PAUSE_NOTICE_INTERVAL_MS = 60_000L

class AppMonitorAccessibilityService : AccessibilityService() {

    private lateinit var repository: PhoneLockRepository
    private lateinit var evaluator: LockEvaluator
    private lateinit var preferences: AppPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var launcherPackage: String? = null
    private var lastReelsShortsCheckAt: Long = 0
    private var lastSiteCheckAt: Long = 0

    // tick()/checkReelsShorts()/checkSites()는 여러 경로(주기적 polling, 접근성 이벤트, 창 전환 이벤트)에서
    // 겹쳐 호출될 수 있고 서로 다른 스레드(Dispatchers.Default의 스레드 풀)에서 실행될 수도 있다. 일반
    // Boolean/Job 변수로는 "지금 실행 중인지" 확인과 표시 사이가 갈라져 경쟁 상태(race condition)에 걸린다
    // (겹침 감지가 깨지는 원인). AtomicBoolean의 compareAndSet으로 "실행 시작"을 원자적으로 처리해서, 언제나
    // 최대 하나만 실행되게 한다.
    private val reelsCheckInFlight = AtomicBoolean(false)
    private val siteCheckInFlight = AtomicBoolean(false)
    private val tickInFlight = AtomicBoolean(false)

    // 공부 잠금이 언제부터 활성 상태였는지(경과시간 근사치 표시용). 잠금이 비활성화되면 초기화한다.
    @Volatile private var studyLockStartedAt: Long? = null

    // 잠긴 앱 백그라운드 재생 차단(125차) — 재생 시간 적립용 기준 시각/그룹별 1초 미만 나머지, 앱별 안내 토스트 시각.
    private var lastBackgroundMediaCheckAt = 0L
    private val backgroundUsageRemainderMs = mutableMapOf<Long, Long>()
    private val lastPauseNoticeAt = mutableMapOf<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 타이머(142차) 안내를 한 약속당 한 번씩만 띄우기 위한 표시 — 값은 그 약속의 잠금 시작 시각.
    private var lockTimerWarnedFor = 0L
    private var lockTimerStartNoticeFor = 0L

    // 타이머의 다른 기기 동기화(143차) — 마지막으로 맞춘 시각과 진행 중 여부.
    private var lastLockTimerSyncAt = 0L
    private val lockTimerSyncInFlight = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = PhoneLockRepository(applicationContext)
        evaluator = LockEvaluator(repository)
        preferences = AppPreferences(applicationContext)
        launcherPackage = resolveLauncherPackage()
        serviceScope.launch { monitorLoop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // checkReelsShorts/checkSites???묎렐???몃━瑜?理쒕? 1500媛??몃뱶源뚯? ?묐뒗 臾닿굅???묒뾽?대떎.
        // 寃????쿂??content-changed ?대깽?멸? ?꾩＜ ?먯＜ 諛쒖깮?섎뒗 ?붾㈃?먯꽌???대깽?멸? ???뚮쭏??        // ??肄붾（?댁쓣 ?꾩슦硫?吏㏃? ?쒓컙???щ윭 ?먯깋???숈떆??寃뱀퀜???????덈떎(?⑥씪 ?ㅽ뻾 蹂댁옣?
        // 媛??⑥닔 ?대???AtomicBoolean 媛?쒓? ?대떦?섎?濡? ?ш린?쒕뒗 洹몃깷 launch留??섎㈃ ?쒕떎).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            serviceScope.launch { checkReelsShorts(packageName) }
            if (BROWSER_PACKAGES.contains(packageName)) {
                serviceScope.launch { checkSites(packageName) }
            }
        }

        // 李????꾪솚 ?대깽?몄뿉?쒕룄 利됱떆 由댁뒪/寃???щ?瑜??ы솗?명븳?? content-changed ?대깽?몃쭔 誘우쑝硫?        // ?깆쓣 留?耳곗쓣 ??泥??곹깭蹂寃쎈쭔 ?ㅺ퀬 content-changed媛 ??쾶 ?ㅺ굅?????ㅻ뒗 寃쎌슦)????쓣
        // 鍮좊Ⅴ寃??붾떎媛붾떎 ?꾪솚????吏㏃? ?쒓컙???щ윭 ?곹깭蹂寃쎌씠 寃뱀퀜 content-changed媛 ?ㅻ줈???臾삵엳??        // 寃쎌슦) 媛먯?瑜??볦튂??臾몄젣媛 ?덉뿀??
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            serviceScope.launch { checkReelsShorts(packageName) }
        }

        // TYPE_VIEW_CLICKED???쇰??????ｋ뒗????content-changed/state-changed留뚯쑝濡쒕룄 異⑸텇??        // 鍮좊Ⅴ寃?諛섏쓳?섍퀬, ?대┃ ?대깽?멸퉴吏 ?뷀븯硫??대깽???뚯뒪媛 ?섎굹 ???섏뼱?섏꽌(?뱁엳 ?곕━媛 吏곸젒
        // ?ㅼ씠?됲듃 ??쓣 ?대┃????洹??대┃ ?먯껜媛 ?????대┃ ?대깽?몃? 留뚮뱾?대궡???먭린利앺룺 猷⑦봽??        // ?꾪뿕???덉뿀?? ?대깽???뚯뒪瑜?理쒖냼?뷀빐??媛먯? 濡쒖쭅???ㅼ뒪濡쒕? ?ㅼ떆 ?몃━嫄고븯??寃쎌슦瑜?以꾩씤??

        // 李??꾪솚 ?대깽?멸? ?ㅻ㈃ ???뺤씤/李⑤떒? ?ㅼ쓬 tick(理쒕? 2珥???湲곕떎由ъ? ?딄퀬 利됱떆 ?ы룊媛?쒕떎.
        // ???대깽?몄뿉留??섏〈?섎㈃ ?쇰? ?꾪솚(?뚮┝李??대졇???щ━湲??????대깽?몃? ???쇱쑝耳?媛먯?媛
        // 硫덉텛??臾몄젣(?룻뵆由?뒪 踰꾧렇)媛 ?덉뿀?쇰?濡? ?꾨옒 monitorLoop??二쇨린???대쭅???덉쟾留앹쑝濡?怨꾩냽 ?붾떎.
        // (monitorLoop??二쇨린 ?ㅽ뻾怨?寃뱀튌 ???덈뒗?? tick() ?대???AtomicBoolean 媛?쒓? 以묐났 ?ㅽ뻾??留됰뒗??)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            serviceScope.launch { runCatching { tick() } }
        }
    }

    /**
     * 李??꾪솚 ?대깽??TYPE_WINDOW_STATE_CHANGED)瑜?罹먯떛?대??ㅺ? ?곕뒗 ??? 留?tick留덈떎
     * rootInActiveWindow?먯꽌 吏곸젒 ?꾩옱 ?쒖꽦 李쎌쓽 ?⑦궎吏紐낆쓣 ?ㅼ떆 ?쎌뼱?⑤떎. ?뚮┝李쎌쓣 ?대졇???щ━????     * ?쇰? ?꾪솚? ???곹깭蹂寃??대깽?몃? ???쇱쑝?ㅻ뒗 寃쎌슦媛 ?덉뼱, ?대깽??罹먯떆???섏〈?섎㈃ 洹??곹깭濡?     * 怨꾩냽 硫덉떠?덈뒗 媛먯? ?꾨씫(?? ?룻뵆由?뒪 踰꾧렇)???앷꼈?? 吏곸젒 ?대쭅?섎㈃ ?대깽???꾨씫怨?臾닿??섍쾶 ??긽
     * 吏湲??ㅼ젣濡????덈뒗 李쎌쓣 湲곗??쇰줈 ?먮떒?쒕떎. (利됯컖 諛섏쓳? ??onAccessibilityEvent媛 ?대떦)
     */
    private suspend fun monitorLoop() {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            runCatching { tick() }
            runCatching { enforceBackgroundMedia() }
            runCatching { syncLockTimerIfDue() }
        }
    }

    /**
     * monitorLoop??二쇨린 ?ㅽ뻾怨?李??꾪솚 ?대깽?몃줈 ?몃━嫄곕릺???ㅽ뻾??寃뱀튌 ???덉뼱?? 寃뱀튂硫?     * addUsageSeconds媛 ?댁쨷?쇰줈 ?곷┰?섍굅???뺤씤/李⑤떒 ?먯젙??以묐났 ?ㅽ뻾?????덈떎. AtomicBoolean?쇰줈
     * ??踰덉뿉 ?섎굹留??뚭쾶 留됰뒗??
     */
    private suspend fun tick() {
        repository.applyDailyGroupResetIfNeeded()
        repository.checkForUpdateIfNeeded()
        checkStudyNotificationFlush()
        if (!tickInFlight.compareAndSet(false, true)) return
        try {
            tickInternal()
        } finally {
            tickInFlight.set(false)
        }
    }

    private suspend fun tickInternal() {
        val packageName = rootInActiveWindow?.packageName?.toString() ?: return
        // 공부 잠금은 shouldIgnore()보다 먼저 확인한다 — shouldIgnore()는 내 앱 자신(잠금 화면)을 무시 대상에
        // 포함하는데, 그룹 차단(원래부터 특정 앱만 차단하는 기능)에는 맞는 예외지만 공부 잠금(사용자가
        // "감옥처럼 이 화면을 벗어나지 못하게" 요청한 기능)에는 맞지 않는다 — 내 앱 화면으로 도망가서
        // 무제한으로 머물 수 있게 되어버리기 때문. checkStudyLock()은 내 앱 자신을 따로 예외 처리한다.
        if (checkStudyLock(packageName)) return
        // 타이머(142차)의 "곧 잠깁니다" 안내는 어떤 화면을 보고 있든 떠야 하므로 무시 대상 검사보다 먼저 한다.
        notifyLockTimerIfNeeded()

        if (shouldIgnore(packageName)) {
            // 우리 앱 자신(차단/확인 화면 등)이 떠 있을 땐 남은 시간 오버레이도 같이 내린다.
            hideUsageOverlay()
            return
        }

        if (checkLockTimer(packageName)) return

        // 이벤트 기반 감지(onAccessibilityEvent)가 놓친 경우를 대비한 주기적 안전망.
        checkReelsShorts(packageName)

        val groups = repository.findGroupsForPackage(packageName)
        if (groups.isEmpty()) {
            hideUsageOverlay()
            return
        }

        // 겹치는 그룹 중 아직 확인을 안 받은 그룹이 있으면 그 중 하나만 확인받는다
        // (확인 후 다음 tick에서 남은 그룹을 다시 검사한다). 같은 이름의 그룹을 가진 다른 기기가 방금
        // 확인했다면(isRecentlyConfirmedAnyDevice) 이 기기에서는 다시 확인받지 않고 들어갈 수 있다 —
        // 한쪽에서 확인을 통과했으면 같은 이름의 그룹은 다른 기기에서도 같은 시간만큼 열려 있게 한다는,
        // 사용자가 요청한 동작이다.
        // 스케줄 => 일일 한도 => 실행 확인 우선순위: 겹치는 그룹 중 하나라도 지금 스케줄/한도 조건이면
        // (evaluate()가 스케줄을 한도보다 먼저 검사한다) 확인창 없이 곧바로 잠근다.
        for (group in groups) {
            val result = evaluator.evaluate(group)
            if (result.locked) {
                hideUsageOverlay()
                val attempts = repository.recordBlockAttempt(group.id)
                // 전체 잠금 방식 규칙(142차)은 허용한 앱을 바로 열 수 있는 전용 화면으로 보낸다.
                if (group.allowlistMode) launchFullLockForGroup(group, result.reason!!)
                else launchBlock(packageName, result.reason!!, attempts)
                return
            }
        }

        var needsConfirm: AppGroup? = null
        for (g in groups) {
            if (evaluator.isConfirmActiveNow(g) && !isRecentlyConfirmedAnyDevice(g)) {
                needsConfirm = g
                break
            }
        }
        if (needsConfirm != null) {
            hideUsageOverlay()
            launchConfirm(packageName, needsConfirm.id, repository.getConfirmWaitSeconds(needsConfirm), repository.getCurrentLevel(needsConfirm))
            return
        }

        // 여기까지 왔으면 잠긴 그룹도, 확인이 필요한 그룹도 없다는 뜻 — 겹치는 그룹 전부에 사용시간을 적립한다.
        groups.forEach { group -> repository.addUsageSeconds(group.id, (TICK_MS / 1000L).toInt()) }
        refreshUsageOverlay(groups)
    }

    /**
     * 공부 타이머가 "공부" 페이즈로 진행 중이면(휴식 중엔 잠그지 않음) 설정에서 고른 허용 앱 밖의
     * 모든 앱(런처/홈 화면 포함)을 감지해서 잠금 화면으로 되돌린다. 기기 소유자 권한 없이는 진짜
     * 실행 차단이 불가능하므로, "열리는 걸 감지해서 즉시 잠금 화면을 띄운다"는 베스트 에포트 방식이다.
     * 잠금을 걸었으면 true를 반환해 이후 릴스/그룹 판정을 건너뛴다.
     *
     * 이 기기의 로컬 타이머만이 아니라, 같은 계정의 다른 기기에서 공부 페이즈가 진행 중이라는 신호
     * (PomodoroSyncClient.isStudyTimerActive)가 있어도 똑같이 잠근다 — 한쪽에서 타이머를 실행하면
     * 다른 쪽에서도 공부 잠금이 걸리게 하라는 사용자 요청. 이 기기 자신의 타이머 판정(isStudyLockActive)은
     * 그대로 두고 OR로 얹기만 한다. 원격 신호가 REMOTE_STUDY_SIGNAL_STALE_MS 넘게 갱신되지 않았으면
     * 유령 상태로 보고 무시한다.
     */
    private suspend fun checkStudyLock(packageName: String): Boolean {
        // 내 앱 자신(잠금 화면 포함)과 systemui/android는 예외 — 런처까지 예외로 두면 홈 화면으로
        // 도망가 버틸 수 있으므로 런처는 일부러 넣지 않는다.
        if (packageName == applicationContext.packageName ||
            packageName == "com.android.systemui" || packageName == "android"
        ) {
            return false
        }
        val localActive = repository.isStudyLockActive()
        val remoteActive = !localActive && isRemoteStudyTimerActive()
        if (!localActive && !remoteActive) {
            studyLockStartedAt = null
            return false
        }
        val startedAt = studyLockStartedAt ?: run {
            val remoteStartedAt = if (remoteActive) {
                PomodoroSyncClient.remotePhaseStartedAt(repository.fbDatabaseUrl, repository.fbApiKey)
            } else {
                0L
            }
            (if (remoteStartedAt > 0) remoteStartedAt else System.currentTimeMillis()).also { studyLockStartedAt = it }
        }
        val allowedPackages = preferences.studyLockAllowedPackages
        if (allowedPackages.contains(packageName)) return false

        val isPomodoroMode = if (localActive) {
            repository.isTimerPomodoroMode()
        } else {
            PomodoroSyncClient.isPomodoroMode(repository.fbDatabaseUrl, repository.fbApiKey)
        }
        hideUsageOverlay()
        launchStudyLock(allowedPackages, startedAt, isPomodoroMode, isRemote = remoteActive)
        return true
    }

    private suspend fun isRemoteStudyTimerActive(): Boolean {
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (!PomodoroSyncClient.isStudyTimerActive(url, key)) return false
        val updatedAt = PomodoroSyncClient.remoteUpdatedAtMillis(url, key)
        return updatedAt > 0 && System.currentTimeMillis() - updatedAt < REMOTE_STUDY_SIGNAL_STALE_MS
    }

    /**
     * 공부 페이즈 동안 StudyNotificationGate가 미뤄둔 알림이 있으면, 공부가 끝나는 대로 전부 다시 띄운다.
     *
     * 136차에 "직전 tick이 공부 중이었는지(wasStudying)"를 보던 방식에서 "미뤄둔 알림이 실제로 있는지"를
     * 먼저 보는 방식으로 바꿨다. 두 가지가 같이 해결된다.
     * 1) **네트워크**: 예전엔 큐가 비어 있어도 매 tick(2초) 공부 여부를 물었고, 로컬 타이머가 꺼져 있으면
     *    그 판정이 [isRemoteStudyTimerActive] → Firebase HTTPS까지 갔다. 5초 캐시가 있어도 결국
     *    **5초에 한 번씩, 하루 종일** 요청이 나가서 배터리와 데이터를 계속 먹었다. 이제 미뤄둔 알림이
     *    있을 때만(= 공부 중에 일회성 알림이 실제로 도착했을 때만) 묻는다.
     * 2) **누락**: 전환(true -> false)이 일어난 그 tick을 놓치면(서비스 재시작, 코루틴 취소 등) 큐가
     *    영영 안 비워졌다. 이제는 "큐가 남아 있고 지금 공부 중이 아니면" 언제든 비우므로 그 틈이 없다.
     */
    private suspend fun checkStudyNotificationFlush() {
        if (!preferences.hasQueuedStudyNotifications) return
        if (repository.isStudyLockActive() || isRemoteStudyTimerActive()) return
        StudyNotificationGate.flushQueued(applicationContext)
    }

    /**
     * 125차(사용자 요청): 잠긴 앱은 화면뿐 아니라 백그라운드 재생도 못 하게 한다. 주기 루프(monitorLoop)에서만
     * 호출한다 — tick()은 창 전환 이벤트에서도 불려 호출 간격이 일정하지 않으므로, 여기서 세는 재생 시간이 겹칠 수 있다.
     *
     * 재생 중인 앱마다 화면에 떠 있을 때와 같은 기준으로 판단한다(판정 함수는 호출만 하고 고치지 않는다).
     * - 공부 잠금 중이고 허용 앱이 아니면 멈춘다(checkStudyLock과 같은 기준).
     * - 그룹이 스케줄/일일한도로 잠겼거나(evaluate), 실행확인이 필요한데 아직 통과하지 않았으면 멈춘다(tickInternal과 같은 기준).
     * - 그 외엔 재생을 허용하고, 재생 시간을 그 앱 그룹의 사용시간에 더한다(일일한도도 이 값으로 판정). 지금 화면에 떠 있는
     *   앱의 그룹은 tickInternal이 이미 세므로 건너뛴다.
     * 알림 접근이 없으면 재생 중인 앱을 알 수 없어 아무것도 하지 않는다(권한 설정 경고로 안내).
     */
    private suspend fun enforceBackgroundMedia() {
        val now = System.currentTimeMillis()
        val elapsedMs = if (lastBackgroundMediaCheckAt == 0L) 0L else (now - lastBackgroundMediaCheckAt).coerceIn(0L, MAX_BACKGROUND_USAGE_STEP_MS)
        lastBackgroundMediaCheckAt = now
        val playing = BackgroundMediaGuard.playingPackages(applicationContext) - applicationContext.packageName
        if (playing.isEmpty()) return

        val foreground = rootInActiveWindow?.packageName?.toString()
        val foregroundGroupIds = foreground?.let { pkg -> repository.findGroupsForPackage(pkg).map { it.id }.toSet() }.orEmpty()
        val studyLocked = repository.isStudyLockActive() || isRemoteStudyTimerActive()
        val studyAllowed = preferences.studyLockAllowedPackages
        val lockedTimer = repository.enforcedLockTimer(activeLockTimer(), now)
        val usageGroupIds = mutableSetOf<Long>()
        for (pkg in playing) {
            // 타이머 잠금(142차)에 걸린 앱도 화면과 같은 기준으로 재생을 멈춘다.
            if (lockedTimer != null && lockedTimer.blocksApp(pkg) &&
                !(lockedTimer.wholeDevice && pkg in EssentialApps.packages(applicationContext))
            ) {
                pauseBackgroundMedia(pkg)
                continue
            }
            if (studyLocked && pkg !in studyAllowed) {
                pauseBackgroundMedia(pkg)
                continue
            }
            val groups = repository.findGroupsForPackage(pkg)
            if (groups.isEmpty()) continue
            val blocked = groups.any { evaluator.evaluate(it).locked } ||
                groups.any { evaluator.isConfirmActiveNow(it) && !isRecentlyConfirmedAnyDevice(it) }
            if (blocked) {
                pauseBackgroundMedia(pkg)
            } else if (pkg != foreground) {
                groups.filter { it.id !in foregroundGroupIds }.forEach { usageGroupIds += it.id }
            }
        }
        for (groupId in usageGroupIds) {
            val totalMs = (backgroundUsageRemainderMs[groupId] ?: 0L) + elapsedMs
            backgroundUsageRemainderMs[groupId] = totalMs % 1000L
            val seconds = (totalMs / 1000L).toInt()
            if (seconds > 0) repository.addUsageSeconds(groupId, seconds)
        }
    }

    private fun pauseBackgroundMedia(packageName: String) {
        if (!BackgroundMediaGuard.pause(applicationContext, packageName)) return
        val now = System.currentTimeMillis()
        if (now - (lastPauseNoticeAt[packageName] ?: 0L) < PAUSE_NOTICE_INTERVAL_MS) return
        lastPauseNoticeAt[packageName] = now
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        mainHandler.post {
            Toast.makeText(applicationContext, "'$label'은(는) 지금 잠겨 있어 재생을 멈췄습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ConfirmationGate(濡쒖뺄)?, 媛숈? ?대쫫??洹몃９??媛吏??ㅻⅨ 湲곌린媛 ?숆린?붾줈 ?щ┛ 留덉?留??뺤씤 ?쒓컖 以?     * ???섏쨷??履?湲곗??쇰줈 ?⑥? ?좎삁?쒓컙??怨꾩궛?쒕떎. ConfirmationGate ?먯껜??嫄대뱶由ъ? ?딅뒗??
     */
    private suspend fun effectiveRemainingCooldownSeconds(group: AppGroup): Int {
        val local = ConfirmationGate.remainingCooldownSeconds(group.id, group.confirmCooldownSeconds)
        val syncedAt = repository.syncedLastConfirmedAtEpochMillis(group)
        val syncedRemaining = if (syncedAt <= 0) {
            0
        } else {
            val elapsedSeconds = (System.currentTimeMillis() - syncedAt) / 1000L
            (group.confirmCooldownSeconds - elapsedSeconds).coerceAtLeast(0L).toInt()
        }
        return maxOf(local, syncedRemaining)
    }

    private suspend fun isRecentlyConfirmedAnyDevice(group: AppGroup): Boolean = effectiveRemainingCooldownSeconds(group) > 0

    private fun launchStudyLock(allowedPackages: Set<String>, startedAt: Long, isPomodoroMode: Boolean, isRemote: Boolean = false) {
        val intent = Intent(this, StudyLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_STUDY_LOCK_ALLOWED_PACKAGES, allowedPackages.toTypedArray())
            putExtra(IntentExtras.EXTRA_STUDY_LOCK_STARTED_AT, startedAt)
            putExtra(IntentExtras.EXTRA_STUDY_LOCK_IS_POMODORO, isPomodoroMode)
            putExtra(IntentExtras.EXTRA_STUDY_LOCK_IS_REMOTE, isRemote)
        }
        startActivity(intent)
    }

    /**
     * 釉뚮씪?곗?(BROWSER_PACKAGES)媛 怨듬? ?좉툑 ?덉슜 ?깆씠???대젮 ?덈뜑?쇰룄, 怨듬?????대㉧ ??뿉 ?깅줉??     * ?덉슜 ?ъ씠???곗뒪?ы깙怨?怨듭쑀?섎뒗 媛숈? Firebase 媛? ???ъ씠?몃뒗 ???⑥닔媛 ?곕줈 留됰뒗?? 洹몃９
     * ?ъ씠???먯젙怨?媛숈? 諛⑹떇(二쇱냼 ?띿뒪?몄뿉 ?꾨찓?몄씠 ?ы븿?섎뒗吏)?쇰줈 留ㅼ묶?쒕떎. checkStudyLock怨??숈씪?섍쾶
     * 濡쒖뺄 ??대㉧肉??꾨땲???ㅻⅨ 湲곌린???먭꺽 ?좏샇濡쒕룄 李⑤떒?쒕떎.
     */
    private suspend fun checkStudyLockSite(packageName: String, addressText: String): Boolean {
        val active = repository.isStudyLockActive() || isRemoteStudyTimerActive()
        if (!active) return false
        val allowedSites = repository.studyLockAllowedSites
        val allowed = allowedSites.any { addressText.contains(it, ignoreCase = true) }
        if (allowed) return false
        hideUsageOverlay()
        launchBlock(packageName, LockReason.STUDY_LOCK)
        return true
    }

    /**
     * ??쓣 鍮좊Ⅴ寃?諛섎났 ?꾪솚?섎㈃ ?댁쟾 ?대깽?몄뿉???살? AccessibilityNodeInfo媛 ?대? 媛깆떊?섏뼱 臾댄슚?붾맂
     * (stale) ?곹깭?먯꽌 ?묎렐?섎젮???덉쇅(IllegalStateException ??媛 ?섎뒗 寃쎌슦媛 ?덉뿀?? ??踰덉쓽 寃??     * ?ㅽ뙣濡?媛먯? 湲곕뒫 ?꾩껜媛 硫덉텛硫????섎?濡? ?ш린???덉쇅瑜??쇳궎怨??ㅼ쓬 ?대깽???깆뿉???ㅼ떆 ?쒕룄?쒕떎.
     *
     * 寃????쿂???대깽?멸? ?꾩＜ ??? ?붾㈃?먯꽌?????⑥닔媛 ?щ윭 ?ㅻ젅?쒖뿉??嫄곗쓽 ?숈떆???몄텧?????덉뼱??
     * AtomicBoolean?쇰줈 ??踰덉뿉 ?섎굹留??ㅼ젣濡??ㅽ뻾?섍쾶 留됰뒗???대? ?ㅽ뻾 以묒씠硫?議곗슜??嫄대꼫?곌퀬
     * ?ㅼ쓬 ?대깽???깆뿉???ㅼ떆 ?쒕룄??.
     */
    private fun checkReelsShorts(packageName: String) {
        if (!reelsCheckInFlight.compareAndSet(false, true)) return
        try {
            checkReelsShortsInternal(packageName)
        } catch (e: Exception) {
            // 議곗슜??臾댁떆 ???ㅼ쓬 ?대깽????理쒕? 2珥??먯꽌 ?ㅼ떆 ?쒕룄?쒕떎.
        } finally {
            reelsCheckInFlight.set(false)
        }
    }

    private fun checkReelsShortsInternal(packageName: String) {
        val watchReels = packageName == INSTAGRAM_PACKAGE && preferences.blockReels
        val watchShorts = packageName == YOUTUBE_PACKAGE && preferences.blockShorts
        if (!watchReels && !watchShorts) return

        val now = System.currentTimeMillis()
        if (now - lastReelsShortsCheckAt < REELS_SHORTS_THROTTLE_MS) return
        lastReelsShortsCheckAt = now

        val root = rootInActiveWindow ?: return
        // 寃????쿂???붾㈃???ㅻ낫??IME)媛 ?④퍡 ?⑤뒗 寃쎌슦, ?대깽?멸? ?뚮젮二쇰뒗 packageName?
        // ?몄뒪?洹몃옩?몃뜲 ?ㅼ젣 rootInActiveWindow??洹??쒓컙 ?ㅻ낫??李쎌쓣 媛由ы궎??寃쎌슦媛 ?덉뿀??
        // 洹??곹깭濡??몃━瑜??묒쑝硫??ㅻ낫?쒖쓽 ???섎굹瑜???諛??꾩씠肄섏쑝濡?李⑷컖?댁꽌 ?뚮윭踰꾨┫ ???덉뼱??        // (??댄븨 ?대깽?몄? 留욌Ъ??怨꾩냽 ?ㅼ옉?숉븯??寃껋쿂??蹂댁씠???먯씤), ?ㅼ젣 猷⑦듃???⑦궎吏紐낆씠
        // 湲곕????⑦궎吏紐낃낵 ?ㅻⅤ硫?=吏湲??쒖꽦 李쎌씠 ?몄뒪?洹몃옩???꾨땲硫? 洹몃깷 嫄대꼫?대떎.
        if (root.packageName?.toString() != packageName) return

        if (watchReels && containsSelectedKeyword(root, REELS_KEYWORDS)) {
            launchBlock(packageName, LockReason.REELS)
            return
        }

        if (watchShorts && containsSelectedKeyword(root, SHORTS_KEYWORDS)) {
            launchBlock(packageName, LockReason.SHORTS)
        }
    }

    /**
     * 由댁뒪/?쇱툩 ??踰꾪듉? ???붾㈃?먯꽌????긽 議댁옱?섎?濡??띿뒪?몃쭔?쇰줈???ㅽ깘???쒕떎. "?꾩옱 ?좏깮??     * (isSelected)" ?곹깭?몄?濡?援щ텇?섎릺, ???쇰뱶???쇱썙???덈뒗 由댁뒪 誘몃━蹂닿린 ?몃젅??媛숈? 蹂몃Ц
     * 肄섑뀗痢좎뿉?쒕룄 isSelected + ?ㅼ썙???쇱튂媛 諛쒖깮?????덉뼱?? ?섎떒 ??諛??붾㈃ ?꾨옒履?12% ?곸뿭)
     * ?덉뿉 ?덈뒗 ?몃뱶留???곸쑝濡?寃?ы빐??蹂몃Ц 肄섑뀗痢??덉쓽 ?ㅽ깘??嫄몃윭?몃떎.
     *
     * ?덈퉬 ?곗꽑(BFS)?쇰줈 ?묐뒗?? ?곷떒諛??섎떒 ??媛숈? ?붾㈃ 堉덈?(chrome)???몃━?먯꽌 ?뺤? 怨녹뿉 ?덇퀬,
     * ?쇰뱶 寃뚯떆臾쇱쿂??源딄퀬 ?몃뱶 ?섍? 留롮? 肄섑뀗痢좊뒗 ?⑥뵮 源딆? 怨녹뿉 ?덉뼱?? 源딆씠 ?곗꽑 ?먯깋?대㈃
     * maxNodes瑜????⑤쾭由ш린 ?쎈떎.
     */
    private fun containsSelectedKeyword(root: AccessibilityNodeInfo, keywords: List<String>, maxNodes: Int = 1500): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomBandMinY = (screenHeight * 0.88).toInt()
        // 태블릿에서 릴스/쇼츠 차단이 전혀 안 되던 버그(78차) — 폰은 하단 탭바지만 태블릿은 화면이 넓어
        // Instagram/YouTube가 좌(또는 우)측 세로 내비게이션 레일을 쓴다. 하단 12% 밴드만 보던 기존 조건이
        // 이 경우 선택된 탭 노드를 계속 걸러내고 있었다 — 좌우 16% 폭의 사이드 레일 밴드도 함께 허용한다.
        val sideBandWidth = (screenWidth * 0.16).toInt()
        val bounds = android.graphics.Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (node.isSelected) {
                node.getBoundsInScreen(bounds)
                val inBottomBand = bounds.top >= bottomBandMinY
                val inSideRailBand = bounds.right <= sideBandWidth || bounds.left >= screenWidth - sideBandWidth
                if (inBottomBand || inSideRailBand) {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    if (keywords.any { text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true) }) {
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return false
    }

    /**
     * 釉뚮씪?곗? 二쇱냼李?EditText)???띿뒪?몃? ?쎌뼱 洹몃９???깅줉???꾨찓?멸낵 ?쇱튂?섎뒗吏 ?뺤씤?쒕떎.
     * 釉뚮씪?곗?蹂??뺥솗??由ъ냼??ID ???EditText ????몃뱶瑜?李얜뒗 諛⑹떇?대씪 踰꾩쟾???щ씪??鍮꾧탳???덉젙?곸씠??
     *
     * checkReelsShorts? 媛숈? ?댁쑀濡?AtomicBoolean 媛?쒕? ?붾떎 ???섏씠吏 濡쒕뵫/??댄븨 以묒뿉??     * content-changed ?대깽?멸? ??븘???щ윭 ?ㅽ뻾??寃뱀튌 ???덈떎.
     */
    private suspend fun checkSites(packageName: String) {
        if (!siteCheckInFlight.compareAndSet(false, true)) return
        try {
            checkSitesInternal(packageName)
        } finally {
            siteCheckInFlight.set(false)
        }
    }

    private suspend fun checkSitesInternal(packageName: String) {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastSiteCheckAt
        if (elapsedMs < SITE_CHECK_THROTTLE_MS) return

        val root = rootInActiveWindow ?: return
        // checkReelsShortsInternal怨?媛숈? ?댁쑀(?ㅻ낫???깆쑝濡??쒖꽦 李쎌씠 諛붾뚯뼱 ?덈뒗 寃쎌슦) 諛⑹뼱.
        if (root.packageName?.toString() != packageName) return
        val addressText = findAddressBarText(root)
        lastSiteCheckAt = now
        if (addressText == null) return

        if (checkStudyLockSite(packageName, addressText)) return
        if (checkLockTimerSite(packageName, addressText)) return

        val matches = repository.findGroupSitesForAddress(addressText, packageName)
        if (matches.isEmpty()) {
            hideUsageOverlay()
            return
        }

        // 寃뱀튂??洹몃９ 以??꾩쭅 ?뺤씤????諛쏆? 洹몃９???덉쑝硫???踰덉뿉 ?섎굹???뺤씤諛쏅뒗????tickInternal怨?        // 媛숈? ?щ줈?ㅻ뵒諛붿씠??荑⑤떎???먯튃).
        // 스케줄 => 일일 한도 => 실행 확인 우선순위: 겹치는 그룹 중 하나라도 지금 스케줄/한도 조건이면
        // (evaluate()가 스케줄을 한도보다 먼저 검사한다) 확인창 없이 곧바로 잠근다.
        for ((_, group) in matches) {
            val result = evaluator.evaluate(group)
            if (result.locked) {
                hideUsageOverlay()
                launchBlock(packageName, siteLockReason(group, result.reason!!), repository.recordBlockAttempt(group.id))
                return
            }
        }

        var needsConfirm: Pair<GroupSite, AppGroup>? = null
        for (m in matches) {
            if (evaluator.isConfirmActiveNow(m.second) && !isRecentlyConfirmedAnyDevice(m.second)) {
                needsConfirm = m
                break
            }
        }
        if (needsConfirm != null) {
            hideUsageOverlay()
            val (site, group) = needsConfirm
            launchConfirmSite(site.domain, group.id, repository.getConfirmWaitSeconds(group), repository.getCurrentLevel(group))
            return
        }

        // 寃뱀튂??洹몃９ 以??섎굹?쇰룄 吏湲??좉툑 議곌굔?대㈃ ?좉렐??
        val elapsedSeconds = (elapsedMs / 1000L).toInt().coerceIn(0, MAX_SITE_TICK_SECONDS)
        if (elapsedSeconds > 0) {
            matches.forEach { (_, group) ->
                if (group.dailyLimitSeconds != null) {
                    repository.addUsageSeconds(group.id, elapsedSeconds)
                }
            }
            val freshGroups = matches.mapNotNull { (_, group) -> repository.getGroup(group.id) }
            for (fresh in freshGroups) {
                val freshResult = evaluator.evaluate(fresh)
                if (freshResult.locked) {
                    hideUsageOverlay()
                    launchBlock(packageName, siteLockReason(fresh, freshResult.reason!!), repository.recordBlockAttempt(fresh.id))
                    return
                }
            }
        }

        refreshUsageOverlay(matches.map { (_, group) -> group }.distinctBy { it.id })
    }

    /**
     * 二쇱냼李쎌씠 "?낅젰 以??ъ빱??" ?곹깭硫?寃?됱뼱瑜???댄븨?섎뒗 以묒씠嫄곕굹 異붿쿇 紐⑸줉?????곹깭?대?濡?臾댁떆?섍퀬,
     * ?ъ빱?ㅺ? ?놁뼱???ㅼ젣 ?대룞???앸궃 ??寃곌낵 URL??蹂댁뿬二쇰뒗 ?곹깭???뚮쭔 ?쎈뒗??
     */
    /**
     * 釉뚮씪?곗? 二쇱냼李?EditText)???곗꽑?댁?留? 援ш? ???몄빋 釉뚮씪?곗?(Custom Tab)泥섎읆 二쇱냼瑜??섏젙
     * 遺덇??ν븳 ?쇰컲 TextView濡쒕쭔 蹂댁뿬二쇰뒗 寃쎌슦???덉뼱??EditText瑜?紐?李얠쑝硫??꾨찓?몄쿂???앷릿
     * ?띿뒪?몃? ???李얜뒗???? "namu.wiki").
     */
    private fun findAddressBarText(root: AccessibilityNodeInfo, maxNodes: Int = 400): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        var domainLikeFallback: String? = null
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            if (node.className == "android.widget.EditText" && !node.isFocused) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) return text
            }
            if (domainLikeFallback == null) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank() && looksLikeDomain(text)) {
                    domainLikeFallback = text
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return domainLikeFallback
    }

    private fun looksLikeDomain(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 100 || trimmed.contains(" ")) return false
        return DOMAIN_LIKE_REGEX.matches(trimmed)
    }

    private fun shouldIgnore(packageName: String): Boolean {
        return packageName == applicationContext.packageName ||
            packageName == launcherPackage ||
            packageName == "com.android.systemui" ||
            packageName == "android"
    }

    private fun resolveLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    /**
     * ?ㅽ뻾?뺤씤?먯꽌 "吏꾪뻾"??怨⑤씪 ?듦낵?????ㅼ젣濡????ъ씠?몃? ?곕뒗 ?숈븞, ?ㅽ뻾?뺤씤 ?붾㈃怨?媛숈? ?붿옄?몄쓽
     * ?ㅻ쾭?덉씠濡??ㅼ쓬 ?ㅽ뻾?뺤씤源뚯? ?⑥? ?쒓컙????대㉧濡?怨꾩냽 ?꾩슫?? ?묎렐???쒕퉬?ㅻ뒗 SYSTEM_ALERT_WINDOW 沅뚰븳 ?놁씠??     * TYPE_ACCESSIBILITY_OVERLAY濡??ㅻ쾭?덉씠瑜??꾩슱 ???덈떎. FLAG_NOT_TOUCHABLE濡??곗튂瑜??꾨?
     * 諛묒쓽 ?깆쑝濡??섎젮蹂대궡誘濡??ㅼ젣 ?ъ슜?먮뒗 ?꾪? 諛⑺빐媛 ?섏? ?딅뒗??
     */
    // ???꾨뱶?ㅼ? Main ?ㅻ젅??酉?議곗옉)? Dispatchers.Default(tick()/checkSites()媛 ?꾨뒗 ?ㅻ젅???)
    // ?묒そ?먯꽌 ?쎄퀬 ?대떎. ?쇰컲 var???ㅻ젅??媛?理쒖떊媛믪씠 蹂댁씠??嫄?蹂댁옣?섏? ?딆븘??媛?쒖꽦 臾몄젣),
    // ?덈? ?ㅼ뼱 hideUsageOverlay()媛 諛깃렇?쇱슫???ㅻ젅?쒖뿉??overlayView瑜??쎌쓣 ??諛⑷툑 硫붿씤 ?ㅻ젅?쒓?
    // ?⑤넃? 媛믪쓣 紐?蹂닿퀬 ?≪? 媛믪쓣 蹂????덈떎. @Volatile濡???긽 理쒖떊媛믪쓣 蹂닿쾶 ?쒕떎.
    @Volatile private var overlayView: android.view.View? = null
    @Volatile private var overlayTimerView: android.widget.TextView? = null
    @Volatile private var overlayCountdownJob: Job? = null
    @Volatile private var overlayRemainingSeconds: Int = 0

    // 媛숈? ?ы솗??荑⑤떎???ъ씠?댁씠 ?꾨뒗 ?숈븞(remaining??怨꾩냽 以꾧린留??섎뒗 ?숈븞)?? 罹먯떆 ?щ룞湲고솕??    // 援ш? ?쒕씪?대툕 怨듭쑀 ?뚯씪(?ㅻⅨ 湲곌린???ы솗??濡??명빐 ?덈꺼???꾩쨷??????댁삱?쇰룄 ?붾㈃??蹂댁씠??    // 遺덊닾紐낅룄???щ씪媛吏 ?딄쾶(?대젮媛??嫄??덉슜) 遺숈옟???붾떎. remaining???댁쟾蹂대떎 而ㅼ?硫?=吏꾩쭨濡?    // ?덈줈 ?ы솗?명빐??荑⑤떎?댁씠 苑?李④쾶 由ъ뀑??寃? 洹??쒖젏?????덈꺼??洹몃?濡?諛섏쁺??罹≪쓣 ?ㅼ떆 ?몄슫??
    // ?뺤씤李쎌씠 ?⑤뒗 ?쒖젏/鍮덈룄, ?덈꺼???ㅻⅤ?대━??洹쒖튃 ?먯껜???꾪? 嫄대뱶由ъ? ?딅뒗?????쒖닔?섍쾶 ?붾㈃??    // "蹂댁뿬吏?? 媛믩쭔 ?대젃寃??뚮윭?붾떎.
    @Volatile private var lastDisplayedOverlayAlpha: Int = -1
    @Volatile private var lastOverlayRemainingSeconds: Int = -1

    private suspend fun refreshUsageOverlay(groups: List<AppGroup>) {
        // ???쒖젏??groups???대? tick()/checkSites()?먯꽌 "?뺤씤???꾩슂?쒕뜲 ?꾩쭅 ??諛쏆? 洹몃９"
        // 寃?щ? ?듦낵???ㅼ씠誘濡? confirmEnabled??洹몃９? ?꾨? 諛⑷툑 ?뺤씤??留덉튂怨??좎삁?쒓컙(荑⑤떎??
        // ?덉뿉 ?덈뒗 ?곹깭????利?"吏꾪뻾??怨좊Ⅴ怨??곌퀬 ?덈뒗 以?. 洹??좎삁?쒓컙???ㅼ떆 ?뺤씤??臾쇱뼱蹂닿린源뚯?
        // ?⑥? ?쒓컙?대?濡? ?쇱씪 ?ъ슜 ?쒕룄? 臾닿??섍쾶 ??媛믪쓣 洹몃?濡???대㉧濡?蹂댁뿬以??
        val candidate = groups.firstOrNull { evaluator.isConfirmActiveNow(it) }
        if (candidate != null) {
            // ?ㅻ쾭?덉씠 ?쒖떆??洹몃９留덈떎 耳쒓퀬 ?????덈떎 ????洹몃９??爰쇰??쇰㈃ ?쒖떆?섏? ?딅뒗??
            if (!candidate.usageOverlayEnabled) {
                hideUsageOverlay()
                return
            }
            val remaining = effectiveRemainingCooldownSeconds(candidate)
            if (remaining <= 0) {
                hideUsageOverlay()
                return
            }
            val level = repository.getCurrentLevel(candidate)
            showOrResyncUsageOverlay(remaining, level, candidate.overlayLevelStepsToMax)
            return
        }

        // ?ㅽ뻾?뺤씤 ??곸씠 ?꾨땲?대룄, 怨듬???戮紐⑤룄濡??댁떇?쇰줈 ?꾩떆 ?댁젣??洹몃９?대㈃ "吏湲덉? ?먮옒 ?좉꺼??        // ?섎뒗???댁떇?대씪 ?꾩떆濡???ㅼ엳????嫄??딆? ?딅룄濡?媛숈? ?ㅼ쓽 ?ㅻ쾭?덉씠瑜??꾩슫??湲곕낯蹂대떎 吏꾪븳
        // 遺덊닾紐낅룄濡?援щ텇).
        val pomodoroCandidate = groups.firstOrNull { it.pomodoroUnlockEnabled && evaluator.isPomodoroUnlockActive(it) }
        if (pomodoroCandidate == null || !pomodoroCandidate.usageOverlayEnabled) {
            hideUsageOverlay()
            return
        }
        val phaseEndAt = PomodoroSyncClient.currentPhaseEndAt(repository.fbDatabaseUrl, repository.fbApiKey)
        val remainingBreakSeconds = ((phaseEndAt - System.currentTimeMillis()) / 1000L).toInt()
        if (remainingBreakSeconds <= 0) {
            hideUsageOverlay()
            return
        }
        showOrResyncPomodoroOverlay(remainingBreakSeconds)
    }

    /**
     * 酉??앹꽦/異붽?/?띿뒪??媛깆떊? ?꾨? 硫붿씤 ?ㅻ젅?쒖뿉?쒕쭔 ?댁빞 ?쒕떎 ??tick()/checkSites()??     * serviceScope(Dispatchers.Default, 諛깃렇?쇱슫???ㅻ젅???먯꽌 ?몄텧?섎뒗?? ?ш린??洹몃?濡?View瑜?     * 嫄대뱶由щ㈃ CalledFromWrongThreadException?쇰줈 ?묎렐???쒕퉬???먯껜媛 二쎌뼱踰꾨━怨?洹??ы뙆濡?     * 由댁뒪 媛먯? 湲곕뒫源뚯? 媛숈씠 硫덉떠踰꾨┝), 洹몃옒??諛섎뱶??Dispatchers.Main?쇰줈 ?섍꺼??泥섎━?쒕떎.
     */
    private fun showOrResyncUsageOverlay(remainingSeconds: Int, level: Int, levelStepsToMax: Int = 5) {
        // remaining??吏곸쟾蹂대떎 而ㅼ죱??= ?덈줈 ?ы솗?명빐??荑⑤떎?댁씠 苑?李④쾶 由ъ뀑??寃???洹??쒖젏??吏꾩쭨
        // ?덈꺼??罹??놁씠 洹몃?濡?諛섏쁺?쒕떎. 洹몃젃吏 ?딄퀬 怨꾩냽 以꾧퀬 ?덉뿀?ㅻ㈃ 媛숈? ?ъ씠?댁씠 ?댁뼱吏??        // 以묒씠誘濡? ?붾㈃??蹂댁씠??遺덊닾紐낅룄媛 洹??ъ씠 ?꾨줈 ?吏 ?딄쾶 吏곸쟾 媛??댄븯濡?罹≪쓣 ?뚯슫??
        val isNewConfirmCycle = lastOverlayRemainingSeconds < 0 || remainingSeconds > lastOverlayRemainingSeconds
        lastOverlayRemainingSeconds = remainingSeconds
        // ???⑥닔??2珥덈쭏???꾨뒗 tick()/checkSites()?먯꽌 ?몄텧?섎뒗?? ?ㅻ쾭?덉씠 ??대㉧ ?먯껜??蹂꾨룄濡?        // 1珥덈쭏??濡쒖뺄?먯꽌 ?먮Ⅴ怨??덈떎(overlayCountdownJob). ?ш린??留ㅻ쾲 ?쒕쾭 怨꾩궛媛믪쑝濡?洹몃?濡?        // ??뼱?곕㈃, ????대컢????留욎븘?⑥뼱吏吏 ?딆쓣 ???ㅼ?以꾨쭅 吏???깆쑝濡?1珥??대궡 ?ㅼ감) 2珥덈쭏??        // ?レ옄媛 ??移??ㅽ궢?섍굅???좉퉸 硫덉톬???먮Ⅴ??寃껋쿂??踰꾨쾮嫄곕젮 蹂댁씤?? 濡쒖뺄 媛믨낵 1珥??대궡濡쒕쭔
        // 李⑥씠?섎㈃(=?뺤긽?곸씤 ?ㅼ감 踰붿쐞) 洹몃?濡??먭퀬, ?ㅻ쾭?덉씠媛 諛⑷툑 ??寃쎌슦???ш쾶 ?닿툔??寃쎌슦(??        // ?ы솗?????ㅼ젣 ?곹깭 蹂???먮쭔 ?ㅼ젣濡?媛깆떊?쒕떎.
        val overlayJustStarted = overlayCountdownJob?.isActive != true
        if (overlayJustStarted || kotlin.math.abs(overlayRemainingSeconds - remainingSeconds) > 1) {
            overlayRemainingSeconds = remainingSeconds
        }
        serviceScope.launch(Dispatchers.Main) {
            ensureUsageOverlayView()
            applyOverlayOpacityForLevel(level, allowIncrease = isNewConfirmCycle, levelStepsToMax = levelStepsToMax)
            overlayTimerView?.text = formatRemainingTime(overlayRemainingSeconds)
        }
        if (overlayCountdownJob?.isActive != true) {
            overlayCountdownJob = serviceScope.launch(Dispatchers.Main) {
                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    if (overlayRemainingSeconds > 0) overlayRemainingSeconds -= 1
                    overlayTimerView?.text = formatRemainingTime(overlayRemainingSeconds)
                }
            }
        }
    }

    /**
     * 戮紐⑤룄濡??댁떇?쇰줈 ?꾩떆 ?댁젣???숈븞 ?⑤뒗 ?ㅻ쾭?덉씠. ?덈꺼???곕씪 蹂?섎뒗 ?ㅽ뻾?뺤씤 ?ㅻ쾭?덉씠? ?щ━
     * ??긽 怨좎젙??湲곕낯媛믩낫??吏꾪븳) 遺덊닾紐낅룄瑜??대떎 ??"?닿굔 ?ы솗???듦낵媛 ?꾨땲???댁떇 ?꾩떆 ?댁젣"?꾩쓣
     * ?쒓컖?곸쑝濡?援щ텇?섍린 ?꾪븿. 移댁슫?몃떎???ъ궗??濡쒖쭅? showOrResyncUsageOverlay? ?숈씪???⑦꽩.
     */
    private fun showOrResyncPomodoroOverlay(remainingSeconds: Int) {
        val overlayJustStarted = overlayCountdownJob?.isActive != true
        if (overlayJustStarted || kotlin.math.abs(overlayRemainingSeconds - remainingSeconds) > 1) {
            overlayRemainingSeconds = remainingSeconds
        }
        serviceScope.launch(Dispatchers.Main) {
            ensureUsageOverlayView()
            overlayView?.setBackgroundColor(overlayBackgroundArgb(POMODORO_OVERLAY_ALPHA))
            overlayTimerView?.setTextColor(overlayPrimaryArgb(POMODORO_OVERLAY_ALPHA))
            overlayTimerView?.text = formatRemainingTime(overlayRemainingSeconds)
        }
        if (overlayCountdownJob?.isActive != true) {
            overlayCountdownJob = serviceScope.launch(Dispatchers.Main) {
                while (currentCoroutineContext().isActive) {
                    delay(1000)
                    if (overlayRemainingSeconds > 0) overlayRemainingSeconds -= 1
                    overlayTimerView?.text = formatRemainingTime(overlayRemainingSeconds)
                }
            }
        }
    }

    /**
     * ?곗뒪?ы깙 ?ㅻ쾭?덉씠? 媛숈? 洹쒖튃: ?덈꺼 0????湲곕낯 ?щ챸?꾩뿉?? ?ы솗?몄쓣 諛섎났???덈꺼???ㅻ??섎줉
     * 議곌툑????遺덊닾紐낇빐吏怨?理쒕?移섍퉴吏), ?덈꺼???먯뿰 媛먯냼/?쇱씪 珥덇린?붾줈 ?대젮媛硫??ㅼ쓬 媛깆떊 ??     * 洹몃쭔???ㅼ떆 ?щ챸?댁쭊????留ㅻ쾲 "?꾩옱 ?덈꺼" 湲곗??쇰줈 泥섏쓬遺???ㅼ떆 怨꾩궛?섍린 ?뚮Ц???먮룞?쇰줈
     * ?묐갑?μ쑝濡??묐룞?쒕떎. ??대㉧ ?レ옄??諛곌꼍怨??묎컳? 遺덊닾紐낅룄濡?洹몃젮??媛숈? alpha 媛믪쓣 ?띿뒪??     * ?됱긽?먮룄 ?곸슜) ??대㉧ ?먯껜媛 ?ㅻ쾭?덉씠???쇰?濡?蹂댁씠寃??쒕떎 ???덈꺼????쓣 ???レ옄??嫄곗쓽 ??     * 蹂댁씠?ㅺ? ?덈꺼???ㅻ??섎줉 ?먯젏 ?쒕졆?댁쭊??
     */
    private fun applyOverlayOpacityForLevel(level: Int, allowIncrease: Boolean, levelStepsToMax: Int = 5) {
        val alphaPerLevel = (OVERLAY_MAX_ALPHA - OVERLAY_BASE_ALPHA).toFloat() / levelStepsToMax.coerceAtLeast(1)
        val rawAlpha = (OVERLAY_BASE_ALPHA + level * alphaPerLevel).toInt().coerceAtMost(OVERLAY_MAX_ALPHA)
        val cap = lastDisplayedOverlayAlpha
        val alpha = if (allowIncrease || cap < 0) rawAlpha else minOf(rawAlpha, cap)
        lastDisplayedOverlayAlpha = alpha
        overlayView?.setBackgroundColor(overlayBackgroundArgb(alpha))
        overlayTimerView?.setTextColor(overlayPrimaryArgb(alpha))
    }

    /** 사용 중 오버레이(실행확인 통과 후 유예시간/뽀모도로 임시해제) 색상 — 설정된 테마 팔레트를 그대로 따른다. */
    private fun overlayPrimaryArgb(alpha: Int): Int {
        val rgb = preferences.currentPalette().primary.toArgb()
        return android.graphics.Color.argb(alpha, android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb))
    }

    private fun overlayBackgroundArgb(alpha: Int): Int {
        val rgb = preferences.currentPalette().background.toArgb()
        return android.graphics.Color.argb(alpha, android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb))
    }

    /**
     * ?⑥? ?쒓컙 ??대㉧留??붾㈃ ?뺤쨷?숈뿉 ?ш쾶 ?꾩슫??臾멸뎄 ?놁쓬). 諛곌꼍? ?ㅽ뻾?뺤씤 ?붾㈃怨?媛숈? 諛앹?
     * 諛곌꼍???곕릺, ?쒕룞??吏???놁씠 FLAG_NOT_TOUCHABLE濡??곗튂???꾨? 諛묒쑝濡??섎젮蹂대궦??
     */
    private fun ensureUsageOverlayView() {
        if (overlayView != null) return
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val timer = android.widget.TextView(this).apply {
            setTextColor(overlayPrimaryArgb(OVERLAY_BASE_ALPHA))
            textSize = 64f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        val container = android.widget.FrameLayout(this).apply {
            // ?ㅽ뻾?뺤씤 ?붾㈃怨?媛숈? 諛앹? 諛곌꼍(?섏???怨꾩뿴)?대릺, ?щ챸?꾨? ?믪뿬??諛묒쓽 ???ъ씠???댁슜??            // 鍮꾩퀜 蹂댁씠寃??쒕떎 ???댁감??FLAG_NOT_TOUCHABLE???곗튂??洹몃?濡??듦낵?섎땲, ?쒓컖?곸쑝濡쒕룄
            // ?ㅼ궗??湲 ?쎄린 ????吏?μ씠 ?녾쾶 ?쒕떎.
            setBackgroundColor(overlayBackgroundArgb(OVERLAY_BASE_ALPHA))
            addView(
                timer,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(container, params) }.onSuccess {
            overlayView = container
            overlayTimerView = timer
        }
    }

    private fun hideUsageOverlay() {
        overlayCountdownJob?.cancel()
        overlayCountdownJob = null
        // ?ㅻ쾭?덉씠媛 ?대젮媛硫??ㅼ쓬?????뚮뒗(媛숈? 洹몃９?대뱺 ?ㅻⅨ 洹몃９?대뱺) ?꾩쟾?????ъ씠?댁씠誘濡?        // 遺덊닾紐낅룄 罹≪쓣 珥덇린?뷀븳??????洹몃윭硫??댁쟾 ?몄뀡????? 媛믪뿉 怨꾩냽 ?뚮젮 ?덇쾶 ?쒕떎.
        lastDisplayedOverlayAlpha = -1
        lastOverlayRemainingSeconds = -1
        if (overlayView == null) return
        serviceScope.launch(Dispatchers.Main) {
            val view = overlayView ?: return@launch
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            runCatching { windowManager.removeView(view) }
            overlayView = null
            overlayTimerView = null
        }
    }

    private fun formatRemainingTime(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    // ---- 관리 > 타이머("이거까지만 할게요!") · 전체 잠금 방식 규칙(142차) ----

    /** 진행 중인 타이머. 잠금까지 다 끝난 약속은 여기서 지운다(다음 약속을 새로 걸 수 있게). */
    private fun activeLockTimer(): LockTimer? {
        val timer = preferences.lockTimer ?: return null
        if (timer.phaseAt(System.currentTimeMillis()) == LockTimer.Phase.DONE) {
            preferences.lockTimer = null
            return null
        }
        return timer
    }

    /**
     * 자유 시간이 1분 남았을 때와 잠금이 시작될 때 한 번씩 알려준다 — 보던 화면이 예고 없이 잠금 화면으로 바뀌지
     * 않게 하기 위함이다. 자유 시간이 1분 이하였던 약속(바로 잠금 포함)은 방금 직접 누른 것이라 알리지 않는다.
     */
    private fun notifyLockTimerIfNeeded() {
        val timer = activeLockTimer() ?: return
        val now = System.currentTimeMillis()
        val hadRealFreeTime = timer.lockStartAtMillis - timer.startedAtMillis > LockTimer.WARNING_BEFORE_LOCK_MILLIS
        if (!hadRealFreeTime) return
        when (timer.phaseAt(now)) {
            LockTimer.Phase.FREE -> {
                val untilLock = timer.lockStartAtMillis - now
                if (untilLock <= LockTimer.WARNING_BEFORE_LOCK_MILLIS && lockTimerWarnedFor != timer.lockStartAtMillis) {
                    lockTimerWarnedFor = timer.lockStartAtMillis
                    showLockTimerToast("이거까지만! ${formatLockRemaining(untilLock)} 뒤에 잠깁니다.")
                }
            }
            LockTimer.Phase.LOCKED -> if (lockTimerStartNoticeFor != timer.lockStartAtMillis) {
                lockTimerStartNoticeFor = timer.lockStartAtMillis
                showLockTimerToast("약속한 시간이 끝났습니다. 지금부터 ${formatLockRemaining(timer.remainingMillis(now))} 동안 잠급니다.")
            }
            LockTimer.Phase.DONE -> {}
        }
    }

    private fun showLockTimerToast(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * 다른 기기와 타이머를 맞춘다(143차). 약속이 있으면 해제·교체를 빨리 알아채도록 자주, 없으면 새 약속이 걸렸는지만
     * 가끔 확인한다 — 하루 종일 도는 루프라 한가할 때의 요청 수를 줄인다. 네트워크 대기가 판정 루프를 막지 않게
     * 별도 코루틴에서 돌고, 이전 요청이 끝나기 전에는 새로 시작하지 않는다.
     */
    private fun syncLockTimerIfDue() {
        val now = System.currentTimeMillis()
        val interval = if (preferences.lockTimer != null) LOCK_TIMER_SYNC_ACTIVE_MS else LOCK_TIMER_SYNC_IDLE_MS
        if (now - lastLockTimerSyncAt < interval) return
        if (!lockTimerSyncInFlight.compareAndSet(false, true)) return
        lastLockTimerSyncAt = now
        serviceScope.launch {
            try {
                when (repository.syncLockTimer()) {
                    LockTimerSyncResult.ADOPTED -> showLockTimerToast("다른 기기에서 시작한 타이머가 이 기기에도 걸렸습니다.")
                    LockTimerSyncResult.CLEARED -> showLockTimerToast("다른 기기에서 타이머를 풀어서 이 기기에서도 풀렸습니다.")
                    LockTimerSyncResult.NONE -> {}
                }
            } finally {
                lockTimerSyncInFlight.set(false)
            }
        }
    }

    /**
     * 타이머가 잠금 단계면 지금 화면의 앱이 막히는지 본다. 전체 잠금이면 허용 목록과 [EssentialApps] 밖의 앱을
     * 전부 전체 잠금 화면으로 보내고, 특정 잠금이면 고른 앱만 차단 화면으로 보낸다. 막았으면 true.
     */
    private suspend fun checkLockTimer(packageName: String): Boolean {
        // 143차: "뽀모도로 휴식 중엔 풀기"를 켠 약속은 휴식 중에 잠금이 없는 것으로 본다(enforcedLockTimer).
        val timer = repository.enforcedLockTimer(activeLockTimer()) ?: return false
        if (!timer.blocksApp(packageName)) return false
        if (timer.wholeDevice && packageName in EssentialApps.packages(applicationContext)) return false
        hideUsageOverlay()
        if (timer.wholeDevice) {
            launchFullLock(
                title = "타이머 잠금",
                message = "약속한 시간이 끝났습니다. 잠금이 풀릴 때까지 허용한 앱만 쓸 수 있습니다.",
                allowedPackages = timer.apps,
                endsAtMillis = timer.lockEndAtMillis
            )
        } else {
            launchBlock(packageName, LockReason.TIMER)
        }
        return true
    }

    /** 브라우저가 열려 있을 때 주소 기준으로 한 번 더 본다 — 전체 잠금이면 허용 사이트 밖, 특정 잠금이면 고른 사이트. */
    private suspend fun checkLockTimerSite(packageName: String, addressText: String): Boolean {
        val timer = repository.enforcedLockTimer(activeLockTimer()) ?: return false
        // 전체 잠금에서 브라우저 자체가 허용 앱이 아니면 앱 단위 잠금(checkLockTimer)이 이미 막는다.
        if (timer.wholeDevice && timer.blocksApp(packageName)) return false
        if (!timer.blocksAddressText(addressText)) return false
        hideUsageOverlay()
        launchBlock(packageName, if (timer.wholeDevice) LockReason.FULL_LOCK else LockReason.TIMER)
        return true
    }

    /** 전체 잠금 방식 규칙에서 막힌 사이트는 "허용한 사이트만"이라는 안내가 맞다(시간대/한도 문구는 앱 기준 문장이다). */
    private fun siteLockReason(group: AppGroup, reason: LockReason): LockReason =
        if (group.allowlistMode) LockReason.FULL_LOCK else reason

    private suspend fun launchFullLockForGroup(group: AppGroup, reason: LockReason) {
        val message = if (reason == LockReason.LIMIT) {
            "오늘 사용 시간 한도를 모두 썼습니다. 허용한 앱만 쓸 수 있습니다."
        } else {
            "지금은 이 차단 규칙의 전체 잠금 시간대입니다. 허용한 앱만 쓸 수 있습니다."
        }
        launchFullLock(
            title = group.name,
            message = message,
            allowedPackages = repository.getMembers(group.id).map { it.packageName }.toSet(),
            endsAtMillis = 0L,
            groupId = group.id
        )
    }

    private fun launchFullLock(
        title: String,
        message: String,
        allowedPackages: Set<String>,
        endsAtMillis: Long,
        groupId: Long = -1L
    ) {
        val intent = Intent(this, FullLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_FULL_LOCK_TITLE, title)
            putExtra(IntentExtras.EXTRA_FULL_LOCK_MESSAGE, message)
            putExtra(IntentExtras.EXTRA_FULL_LOCK_ALLOWED_PACKAGES, allowedPackages.toTypedArray())
            putExtra(IntentExtras.EXTRA_FULL_LOCK_ENDS_AT, endsAtMillis)
            putExtra(IntentExtras.EXTRA_GROUP_ID, groupId)
        }
        startActivity(intent)
    }
    private fun launchBlock(packageName: String, reason: LockReason, blockAttempts: Int = 0) {
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_PACKAGE_NAME, packageName)
            putExtra(IntentExtras.EXTRA_REASON, reason.name)
            putExtra(IntentExtras.EXTRA_BLOCK_ATTEMPTS, blockAttempts)
        }
        startActivity(intent)
    }

    private fun launchConfirm(packageName: String, groupId: Long, waitSeconds: Int, level: Int = 0) {
        val intent = Intent(this, ConfirmOpenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_PACKAGE_NAME, packageName)
            putExtra(IntentExtras.EXTRA_GROUP_ID, groupId)
            putExtra(IntentExtras.EXTRA_WAIT_SECONDS, waitSeconds)
            putExtra(IntentExtras.EXTRA_LEVEL, level)
        }
        startActivity(intent)
    }

    private fun launchConfirmSite(domain: String, groupId: Long, waitSeconds: Int, level: Int = 0) {
        val intent = Intent(this, ConfirmOpenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_IS_SITE, true)
            putExtra(IntentExtras.EXTRA_SITE_DOMAIN, domain)
            putExtra(IntentExtras.EXTRA_GROUP_ID, groupId)
            putExtra(IntentExtras.EXTRA_WAIT_SECONDS, waitSeconds)
            putExtra(IntentExtras.EXTRA_LEVEL, level)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // ?꾩뿭 monitorLoop???쒕퉬???앸챸二쇨린(onDestroy)??臾띠뿬?덉뼱 蹂꾨룄 泥섎━媛 ?꾩슂 ?녿떎.
    }

    override fun onDestroy() {
        overlayCountdownJob?.cancel()
        // onDestroy??硫붿씤 ?ㅻ젅?쒖뿉???몄텧?섎?濡??ш린?쒕뒗 肄붾（?댁쑝濡??섍린吏 ?딄퀬 諛붾줈 ?쒓굅?쒕떎
        // (serviceScope.cancel() ?댄썑濡??섍린硫?removeView 肄붾（?댁씠 痍⑥냼??李쎌씠 ?덉뼱?섍컝 ???덈떎).
        overlayView?.let { view ->
            runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(view) }
        }
        overlayView = null
        overlayTimerView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val REELS_SHORTS_THROTTLE_MS = 1000L
        private val REELS_KEYWORDS = listOf("릴스", "Reels")
        private val SHORTS_KEYWORDS = listOf("쇼츠", "Shorts")
        private const val SITE_CHECK_THROTTLE_MS = 800L
        private const val MAX_SITE_TICK_SECONDS = 30
        // 타이머 동기화(143차) 확인 간격 — 진행 중인 약속이 있을 때 / 없을 때.
        private const val LOCK_TIMER_SYNC_ACTIVE_MS = 10_000L
        private const val LOCK_TIMER_SYNC_IDLE_MS = 30_000L
        // ?곗뒪?ы깙 ?ㅻ쾭?덉씠? 媛숈? 鍮꾩쑉(0.1/0.85瑜?0~255 ?뚰뙆媛믪쑝濡??섏궛). ?덈꺼留덈떎 ?ㅻⅤ?????
        // 怨좎젙媛믪씠 ?꾨땲??洹몃９??overlayLevelStepsToMax濡쒕???留ㅻ쾲 怨꾩궛?쒕떎(applyOverlayOpacityForLevel).
        private const val OVERLAY_BASE_ALPHA = 26
        private const val OVERLAY_MAX_ALPHA = 242
        // 戮紐⑤룄濡??댁떇 ?꾩떆 ?댁젣 ?ㅻ쾭?덉씠????湲곕낯 遺덊닾紐낅룄(OVERLAY_BASE_ALPHA)蹂대떎 ?덉뿉 ?꾧쾶 吏꾪븯寃?
        private const val POMODORO_OVERLAY_ALPHA = 70
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "com.google.android.googlequicksearchbox"
        )
        // "namu.wiki", "www.google.com/search" 媛숈? ?꾨찓??+?좏깮??寃쎈줈) ?뺥깭留?留ㅼ묶?쒕떎.
        private val DOMAIN_LIKE_REGEX = Regex("^(https?://)?([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(/\\S*)?$")
    }
}
