package com.phonelock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.GroupSite
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.BlockActivity
import com.phonelock.app.ui.ConfirmOpenActivity
import com.phonelock.app.ui.MOTIVATIONAL_QUOTES
import com.phonelock.app.ui.StudyLockActivity
import com.phonelock.app.ui.theme.paletteFor
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
// ?ㅻⅨ 湲곌린媛 ?щ┛ "怨듬? ??대㉧ ?ㅽ뻾 以? ?좏샇媛 ?대낫???ㅻ옒 媛깆떊 ???먯쑝硫?臾댁떆?쒕떎(StudyTimerScreen??// 誘몃윭 ?쒖떆? 媛숈? 媛? ?곗뒪?ы깙??EnforcementService.REMOTE_STUDY_SIGNAL_STALE_MS? ?숈씪) ???좏샇瑜?// ?щ━??湲곌린媛 ?뺤? ?놁씠 爰쇱졇??timerActive:true媛 ?좊졊泥섎읆 ?⑥븘 ??湲곌린媛 ?곴뎄???좉린??嫄?留됰뒗??
private const val REMOTE_STUDY_SIGNAL_STALE_MS = 20 * 60 * 1000L

class AppMonitorAccessibilityService : AccessibilityService() {

    private lateinit var repository: PhoneLockRepository
    private lateinit var evaluator: LockEvaluator
    private lateinit var preferences: AppPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var launcherPackage: String? = null
    private var lastReelsShortsCheckAt: Long = 0
    private var lastSiteCheckAt: Long = 0

    // tick()/checkReelsShorts()/checkSites()???щ윭 寃쎈줈(二쇨린??polling, ?묎렐???대깽?? 李??꾪솚 ?대깽???먯꽌
    // 寃뱀퀜 ?몄텧?????덇퀬 ?쒕줈 ?ㅻⅨ ?ㅻ젅??Dispatchers.Default???ㅻ젅???)?먯꽌 ?ㅽ뻾?????덉뼱?? ?쇰컲
    // Boolean/Job 蹂?섎줈??"吏湲??ㅽ뻾 以묒씤吏" 泥댄겕 ?먯껜媛 寃쎌웳 ?곹깭(race condition)??嫄몃┛??寃????뿉??    // 媛먯?媛 源⑥????먯씤). AtomicBoolean??compareAndSet?쇰줈 "?ㅽ뻾 ?쒖옉"???먯옄?곸쑝濡?泥섎━?댁꽌, ??긽
    // 理쒕? ?섎굹???ㅽ뻾留??뚭쾶 ?쒕떎.
    private val reelsCheckInFlight = AtomicBoolean(false)
    private val siteCheckInFlight = AtomicBoolean(false)
    private val tickInFlight = AtomicBoolean(false)

    // 怨듬? ?좉툑???몄젣遺???쒖꽦 ?곹깭??붿?(寃쎄낵?쒓컙 洹쇱궗移??쒖떆?? ??鍮꾪솢?깊솕?섎㈃ 珥덇린??
    @Volatile private var studyLockStartedAt: Long? = null

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
        }
    }

    /**
     * monitorLoop??二쇨린 ?ㅽ뻾怨?李??꾪솚 ?대깽?몃줈 ?몃━嫄곕릺???ㅽ뻾??寃뱀튌 ???덉뼱?? 寃뱀튂硫?     * addUsageSeconds媛 ?댁쨷?쇰줈 ?곷┰?섍굅???뺤씤/李⑤떒 ?먯젙??以묐났 ?ㅽ뻾?????덈떎. AtomicBoolean?쇰줈
     * ??踰덉뿉 ?섎굹留??뚭쾶 留됰뒗??
     */
    private suspend fun tick() {
        repository.applyDailyGroupResetIfNeeded()
        repository.checkForUpdateIfNeeded()
        if (!tickInFlight.compareAndSet(false, true)) return
        try {
            tickInternal()
        } finally {
            tickInFlight.set(false)
        }
    }

    private suspend fun tickInternal() {
        val packageName = rootInActiveWindow?.packageName?.toString() ?: return
        // 怨듬? ?좉툑? shouldIgnore()蹂대떎 癒쇱? ?뺤씤?쒕떎 ??shouldIgnore()???곗쿂(???붾㈃)瑜?臾댁떆 ??곸뿉
        // ?ы븿?섎뒗?? 洹몃９ 李⑤떒(?먮옒遺???뱀젙 ?깅쭔 李⑤떒?섎뒗 湲곕뒫)?먮뒗 留욌뒗 ?덉쇅吏留?怨듬? ?좉툑(?ъ슜?먭?
        // "?댄뭹?泥섎읆 ???붾㈃??踰쀬뼱?섏? 紐삵븯寃? ?붿껌??湲곕뒫)?먮뒗 ??留욌뒗???????붾㈃?쇰줈 ?꾨쭩媛硫?        // 臾댁젣?쒖쑝濡?癒몃Ъ ???덇쾶 ?섏뼱踰꾨━湲??뚮Ц. checkStudyLock()? ?????먯떊留?蹂꾨룄濡??덉쇅 泥섎━?쒕떎.
        if (checkStudyLock(packageName)) return

        if (shouldIgnore(packageName)) {
            // ?곕━ ???먯떊(李⑤떒/?뺤씤 ?붾㈃ ?????꾨㈃???덉쓣 ???⑥? ?쒓컙 ?ㅻ쾭?덉씠??媛숈씠 ?대┛??
            hideUsageOverlay()
            return
        }

        // ?대깽??湲곕컲 媛먯?(onAccessibilityEvent)媛 ?볦튇 寃쎌슦瑜??鍮꾪븳 二쇨린???덉쟾留?
        checkReelsShorts(packageName)

        val groups = repository.findGroupsForPackage(packageName)
        if (groups.isEmpty()) {
            hideUsageOverlay()
            return
        }

        // 寃뱀튂??洹몃９ 以??꾩쭅 ?뺤씤????諛쏆? 洹몃９???덉쑝硫???踰덉뿉 ?섎굹???뺤씤諛쏅뒗??
        // (?뺤씤 ???ㅼ쓬 tick?먯꽌 ?⑥? 洹몃９???ㅼ떆 寃?ы븳??) 媛숈? ?대쫫??洹몃９??媛吏??ㅻⅨ 湲곌린媛 諛⑷툑
        // ?뺤씤?덈떎硫?isRecentlyConfirmedAnyDevice) ??湲곌린???ы솗???놁씠 ?댁뼱???????덈떎 ???ъ슜??        // ?붿껌: "?쒖そ?먯꽌 ?꾩옄 踰꾪듉???꾨Ⅴ硫?媛숈? ?대쫫??洹몃９??媛吏??ㅻⅨ 湲곌린??媛숈? ?쒓컙?瑜?媛吏怨?        // ?쒕룞??吏꾪뻾".
        // 스케줄 => 일일 한도 => 실행 확인 우선순위: 겹치는 그룹 중 하나라도 지금 스케줄/한도 조건이면
        // (evaluate()가 스케줄을 한도보다 먼저 검사한다) 확인창 없이 곧바로 잠근다.
        for (group in groups) {
            val result = evaluator.evaluate(group)
            if (result.locked) {
                hideUsageOverlay()
                launchBlock(packageName, result.reason!!, repository.recordBlockAttempt(group.id))
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

        // 寃뱀튂??洹몃９ 以??섎굹?쇰룄 吏湲??좉툑 議곌굔?대㈃ ?좉렐??
        groups.forEach { group -> repository.addUsageSeconds(group.id, (TICK_MS / 1000L).toInt()) }
        refreshUsageOverlay(groups)
    }

    /**
     * 怨듬?????대㉧媛 "怨듬?" ?섏씠利덈줈 吏꾪뻾 以묒씠硫??댁떇 以묒뿏 ?좉렇吏 ?딆쓬) ?ㅼ젙?먯꽌 怨좊Ⅸ ?덉슜 ????     * 紐⑤뱺 ???곗쿂/???붾㈃ ?ы븿)??媛먯??댁꽌 ?좉툑 ?붾㈃?쇰줈 ?섎룎由곕떎. 湲곌린 ?뚯쑀??沅뚰븳 ?놁씠??吏꾩쭨
     * ?ㅽ뻾 李⑤떒??遺덇??ν븯誘濡?"?대━??嫄?媛먯??댁꽌 ?ㅼ떆 ?좉툑 ?붾㈃???꾩슦?? 踰좎뒪???먰룷??諛⑹떇?대떎.
     * ?좉툑??嫄몄뿀?쇰㈃ true瑜?諛섑솚???댄썑 由댁뒪/洹몃９ ?먯젙??嫄대꼫?대떎.
     *
     * ??湲곌린??濡쒖뺄 ??대㉧肉??꾨땲?? 媛숈? 怨꾩젙???곗뒪?ы깙??怨듬? ?섏씠利덈? ?ㅽ뻾 以묒씠?쇰뒗 ?좏샇
     * (PomodoroSyncClient.isStudyTimerActive)媛 ????묎컳???좉렐?????ъ슜???붿껌: "?쒖そ?먯꽌 ??대㉧瑜?     * ?ㅽ뻾?섎㈃ ?ㅻⅨ履쎌뿉?쒕룄 怨듬? ?좉툑???ㅽ뻾?섍쾶". ??湲곌린 ?먯떊????대㉧ ?먯젙(isStudyLockActive)?
     * 洹몃?濡??먭퀬 OR濡??볧엳湲곕쭔 ?쒕떎. ?먭꺽 ?좏샇??REMOTE_STUDY_SIGNAL_STALE_MS ?섍쾶 媛깆떊???놁쑝硫?     * ?좊졊 ?곹깭濡?蹂닿퀬 臾댁떆?쒕떎.
     */
    private suspend fun checkStudyLock(packageName: String): Boolean {
        // ?????먯떊(?좉툑 ?붾㈃ ?ы븿)留??덉쇅 ??systemui/?곗쿂源뚯? ?덉쇅濡??먮㈃ ???붾㈃?쇰줈 ?꾨쭩移???        // ?덉쑝誘濡??ш린 ?ы븿?섏? ?딅뒗??
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
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomBandMinY = (screenHeight * 0.88).toInt()
        val bounds = android.graphics.Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (node.isSelected) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= bottomBandMinY) {
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

        val matches = repository.findGroupSitesForAddress(addressText)
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
                launchBlock(packageName, result.reason!!, repository.recordBlockAttempt(group.id))
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
                    launchBlock(packageName, freshResult.reason!!, repository.recordBlockAttempt(fresh.id))
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
        val rgb = paletteFor(preferences.themeMode).primary.toArgb()
        return android.graphics.Color.argb(alpha, android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb))
    }

    private fun overlayBackgroundArgb(alpha: Int): Int {
        val rgb = paletteFor(preferences.themeMode).background.toArgb()
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
