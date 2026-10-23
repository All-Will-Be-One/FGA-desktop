package io.github.fate_grand_automata.runner

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.SystemClock
import android.widget.Toast
import dagger.hilt.EntryPoints
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.accessibility.AccessibilityGestureException
import io.github.fate_grand_automata.accessibility.AccessibilityGestures
import io.github.fate_grand_automata.accessibility.GestureFailure
import io.github.fate_grand_automata.automation.AutomationBridge
import io.github.fate_grand_automata.di.script.ScriptComponent
import io.github.fate_grand_automata.di.script.ScriptComponentBuilder
import io.github.fate_grand_automata.di.script.ScriptEntryPoint
import io.github.fate_grand_automata.di.service.ServiceCoroutineScope
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.IImageLoader
import io.github.fate_grand_automata.scripts.decision.DisabledNativeBattleDecisionProvider
import io.github.fate_grand_automata.scripts.entrypoints.AutoCEBomb
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.entrypoints.AutoGiftBox
import io.github.fate_grand_automata.scripts.entrypoints.AutoLottery
import io.github.fate_grand_automata.scripts.entrypoints.AutoServantLevel
import io.github.fate_grand_automata.scripts.entrypoints.SupportImageMaker
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.ui.exit.BattleExit
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.fate_grand_automata.ui.launcher.ScriptLauncherResponse
import io.github.fate_grand_automata.ui.runner.ScriptRunnerUIState
import io.github.fate_grand_automata.ui.runner.ScriptRunnerUIStateHolder
import io.github.fate_grand_automata.ui.support_img_namer.showSupportImageNamer
import io.github.fate_grand_automata.util.FakedComposeView
import io.github.fate_grand_automata.util.KnownException
import io.github.fate_grand_automata.util.ScriptMessages
import io.github.fate_grand_automata.util.ScriptState
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.messageAndStackTrace
import io.github.fate_grand_automata.util.set
import io.github.fate_grand_automata.util.showOverlayDialog
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.OcrService
import io.github.lib_automata.ScreenshotService
import io.github.lib_automata.ScriptAbortException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

@ServiceScoped
class ScriptManager @Inject constructor(
    private val service: Service,
    @param:ApplicationContext private val context: Context,
    private val imageLoader: IImageLoader,
    private val preferences: IPreferences,
    private val prefsCore: PrefsCore,
    private val storageProvider: StorageProvider,
    private val messages: ScriptMessages,
    private val uiStateHolder: ScriptRunnerUIStateHolder,
    private val clipboardManager: ClipboardManager,
    private val messageBox: ScriptRunnerMessageBox,
    @param:ServiceCoroutineScope private val scope: CoroutineScope,
    private val launcherResponseHandler: ScriptLauncherResponseHandler,
    private val screenshotServiceHolder: ScreenshotServiceHolder,
    private val automationComponentBuilder: ScriptComponentBuilder,
    private val battleRequests: BattleRunRequestHandler,
    private val completedRuns: CompletedRunsHolder
) {
    @Volatile
    var scriptState: ScriptState = ScriptState.Stopped
        private set

    private val runGate = ProcessScriptRunGate.gate
    // Native exit and drain callbacks must survive cancellation of the Android service scope.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var serviceDestroyed = false
    private class RunSession(
        val reservation: ScriptRunGate.Reservation,
        val listener: NativeAutomationListener? = null
    ) {
        var recording: AutoCloseable? = null
        @Volatile var pendingExit: NativeAutomationExit? = null
        @Volatile var pendingInteractiveExit: Exception? = null
        @Volatile var threadStarted = false
        @Volatile var threadExited = false
        @Volatile var nativeThread: Thread? = null
        @Volatile var preparation: Job? = null
        var gestures: AccessibilityGestures? = null
        var deadlineElapsedRealtimeMillis: Long? = null
        var maxRuns: Int? = null
        var cleaning = false
        var retryRequested = false
    }

    @Volatile
    private var activeSession: RunSession? = null

    val isAutomationBusy: Boolean get() = runGate.isAutomationBusy()
    val isExecutorBusy: Boolean get() = runGate.currentReservation() != null
    fun onProcessRunnerIdle(callback: () -> Unit) = runGate.afterCurrentRun(callback)
    val isAutomationPrepared: Boolean
        get() = TapperService.instance != null && screenshotServiceHolder.screenshotService != null

    /** One read-only capture, sharing the existing runner reservation and screenshot service. */
    fun captureDiagnostic(store: io.github.fate_grand_automata.automation.ExternalFrameStore): kotlinx.serialization.json.JsonObject {
        val reservation = runGate.reserve(ScriptRunGate.Owner.Automation)
            ?: throw io.github.fga.automation.RpcFailure("Busy", "Native runner is occupied")
        try {
            val screenshot = screenshotServiceHolder.screenshotService
                ?: throw io.github.fga.automation.RpcFailure("RequiresUserConsent", "Prepare the capture service first")
            val component = automationComponentBuilder.screenshotService(screenshot)
                .runPreferences(ScriptRunPreferences(preferences)).build()
            return EntryPoints.get(component, ScriptEntryPoint::class.java).captureDiagnostic().capture(store)
        } finally {
            runGate.finish(reservation)
        }
    }

    init {
        AutomationBridge.attach(this)
    }

    private fun notifyState(session: RunSession, state: NativeAutomationState) {
        if (activeSession !== session) return
        runCatching { session.listener?.onStateChanged(state) }
            .onFailure { Timber.w(it, "Automation state listener failed") }
    }

    private fun finishSession(session: RunSession, exit: NativeAutomationExit? = null) {
        if (activeSession !== session) return
        if ((session.threadStarted && !session.threadExited) || session.gestures?.isInputQuiescent == false) {
            reportCleanupFailure(session, "Native thread or Android input quiescence is unconfirmed")
            return
        }
        session.gestures?.observeQuiescence(null)
        activeSession = null
        scriptState = ScriptState.Stopped
        uiStateHolder.uiState = ScriptRunnerUIState.Idle
        uiStateHolder.isPlayButtonEnabled = true
        if (!runGate.finish(session.reservation)) return
        if (exit != null) {
            runCatching { session.listener?.onExit(exit) }
                .onFailure { Timber.w(it, "Automation exit listener failed") }
        }
    }

    private suspend fun failPreparation(session: RunSession, reason: String, error: String? = null) = withContext(NonCancellable) {
        if (activeSession !== session) return@withContext
        completeAutomation(session, NativeAutomationExit(reason, 0, error))
    }

    private suspend fun mayStart(session: RunSession): Boolean {
        val reason = when {
            serviceDestroyed -> "ServiceDestroyed"
            !runGate.canPrepare(session.reservation) -> "Abort"
            startupDeadlineExpired(session.deadlineElapsedRealtimeMillis, SystemClock.elapsedRealtime()) -> "DeadlineExceeded"
            else -> null
        }
        if (reason != null) failPreparation(session, reason)
        return reason == null
    }

    private fun reportCleanupFailure(session: RunSession, error: String) {
        scriptState.let { if (it is ScriptState.Started) scriptState = ScriptState.Stopping(it) }
        notifyState(session, NativeAutomationState.Stopping)
        Timber.w("Native cleanup unconfirmed: %s", error)
        runCatching { session.listener?.onCleanupFailure(error) }
    }

    private fun beginCleanup(session: RunSession): Boolean = synchronized(session) {
        if (activeSession !== session) return@synchronized false
        if (session.cleaning) {
            session.retryRequested = true
            return@synchronized false
        }
        session.cleaning = true
        true
    }

    private fun endCleanup(session: RunSession) {
        val retry = synchronized(session) {
            session.cleaning = false
            session.retryRequested.also { session.retryRequested = false }
        }
        if (retry) retryCleanup(session)
    }

    private fun retryCleanup(session: RunSession) {
        if (activeSession !== session) return
        session.pendingExit?.let { exit -> cleanupScope.launch { completeAutomation(session, exit) }; return }
        session.pendingInteractiveExit?.let { exit -> cleanupScope.launch { handleScriptExit(exit, session) } }
    }

    private suspend fun releaseSessionResources(session: RunSession): List<String> {
        val failures = mutableListOf<String>()
        if (session.threadStarted && !session.threadExited) {
            session.threadExited = withContext(Dispatchers.IO) { awaitNativeThreadTermination(session.nativeThread, 5_000) }
            if (!session.threadExited) {
                failures += "Native thread termination is unconfirmed"
                return failures
            }
        }
        if (session.gestures?.isInputQuiescent == false) {
            failures += "Android gesture completion or continued pointer release is unconfirmed"
            return failures
        }
        if (!releaseOcrService()) Timber.w("OCR context cleanup failed after native input stopped")
        runCatching { imageLoader.clearSupportCache() }
            .onFailure { Timber.w(it, "Support cache cleanup failed after native input stopped") }
        if (serviceDestroyed) {
            closeOptionalNativeResource({ screenshotServiceHolder.close() }) {
                Timber.w(it, "Screenshot cleanup failed after native input stopped")
            }
            runCatching { imageLoader.clearImageCache() }
                .onFailure { Timber.w(it, "Image cache cleanup failed after native input stopped") }
        }
        session.recording?.let { recording ->
            runCatching { withContext(Dispatchers.Main) { recording.close() } }
                .onSuccess { session.recording = null }
                .onFailure { Timber.w(it, "Optional recording cleanup failed after native input stopped") }
        }
        uiStateHolder.isRecording = session.recording != null
        return failures
    }

    private fun bindSessionResources(session: RunSession, entryPoint: ScriptEntryPoint) {
        session.gestures = entryPoint.gestures().also { gestures ->
            gestures.observeQuiescence { retryCleanup(session) }
        }
        releaseOcrService() // An old resource cleanup failure is diagnostic, as upstream.
        ocrService = entryPoint.ocrService()
    }

    private fun launchPreparation(session: RunSession, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        session.preparation = job
        job.invokeOnCompletion { cause ->
            if (!session.threadStarted && activeSession === session && session.pendingExit == null) {
                cleanupScope.launch { failPreparation(session, if (serviceDestroyed) "ServiceDestroyed" else "Abort", cause?.message) }
            }
        }
        job.start()
    }

    /** Service teardown requests termination; only the real exit and acknowledged input drain release ownership. */
    fun onServiceDestroyed() {
        serviceDestroyed = true
        requestStop()
        activeSession?.takeIf { !it.threadStarted }?.preparation?.cancel()
        // Covers services destroyed without a run and races where termination cleanup already
        // passed its serviceDestroyed check. Runs under the global gate before another start.
        runGate.afterCurrentRun {
            runCatching { screenshotServiceHolder.close() }
                .onFailure { Timber.w(it, "Idle screenshot service cleanup failed") }
            releaseOcrService()
            runCatching { imageLoader.clearImageCache() }
                .onFailure { Timber.w(it, "Idle image cache cleanup failed") }
        }
    }

    private suspend fun completeAutomation(session: RunSession, exit: NativeAutomationExit) {
        if (!beginCleanup(session)) return
        synchronized(session) {
            session.pendingExit = session.pendingExit ?: exit
        }
        try {
            val failures = releaseSessionResources(session)
            if (failures.isNotEmpty()) {
                reportCleanupFailure(session, failures.joinToString("; "))
                return
            }
            preferences.hidePlayButton = false
            finishSession(session, session.pendingExit)
        } finally {
            endCleanup(session)
        }
    }

    private fun requiresUserConsent(error: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is SecurityException) return true
            if (current is AccessibilityGestureException && current.failure in setOf(
                    GestureFailure.AccessibilityUnavailable, GestureFailure.DispatchRejected
                )) return true
            if (current is KnownException && when (current.reason) {
                KnownException.Reason.NoVirtualDisplay,
                KnownException.Reason.NoScreenshotReceived,
                KnownException.Reason.ScreenCaptureStopped,
                is KnownException.Reason.FailedRootPermission -> true
                else -> false
            }) return true
            current = current.cause
        }
        return false
    }

    /** Uses a recorded config server, explicit setting or positive detection; never a default. */
    fun automationRequest(configId: String, maxRuns: Int, timeoutSeconds: Long,
                          startDeadlineElapsedRealtimeMillis: Long? = null): BattleRunRequest {
        val config = preferences.battleConfigs.firstOrNull { it.id == configId }
            ?: throw BattleRunValidationException("UnknownConfig", "Battle configuration does not exist")
        val rawServer = prefsCore.gameServerRaw.get()
        val server = config.server ?: GameServers.deserialize(rawServer)
            ?: (if (rawServer == PrefsCore.GAME_SERVER_AUTO_DETECT) TapperService.instance?.detectedFgoServer else null)
            ?: throw BattleRunValidationException("UnknownServer", "Set an explicit game server on the configuration or in FGA")
        val request = battleRequests.snapshot(configId, server)
        return request.copy(
            limits = request.limits.copy(shouldLimitRuns = true, limitRuns = maxRuns),
            refills = request.refills.copy(resources = emptySet()),
            waitForApRegen = false,
            timeoutSeconds = timeoutSeconds,
            startDeadlineElapsedRealtimeMillis = startDeadlineElapsedRealtimeMillis
        )
    }

    fun startAutomation(request: BattleRunRequest, listener: NativeAutomationListener): NativeStartResult {
        val receivedAt = SystemClock.elapsedRealtime()
        if (serviceDestroyed) return NativeStartResult.RequiresUserConsent
        val reservation = runGate.reserve(ScriptRunGate.Owner.Automation) ?: return NativeStartResult.Busy
        val session = RunSession(reservation, listener)
        activeSession = session
        scriptState = ScriptState.Starting
        val screenshotService = screenshotServiceHolder.screenshotService
        if (TapperService.instance == null || screenshotService == null) {
            finishSession(session)
            return NativeStartResult.RequiresUserConsent
        }
        val validated = try {
            battleRequests.validate(request, BattleRunPolicy.Automation)
        } catch (e: BattleRunValidationException) {
            finishSession(session)
            return NativeStartResult.Rejected(e.code, e.message.orEmpty())
        } catch (e: Exception) {
            finishSession(session)
            return NativeStartResult.Rejected("PreparationFailed", e.message.orEmpty())
        }
        session.deadlineElapsedRealtimeMillis = request.startDeadlineElapsedRealtimeMillis
            ?: (receivedAt + requireNotNull(validated.request.timeoutSeconds) * 1000)
        session.maxRuns = validated.request.limits.limitRuns
        if (startupDeadlineExpired(session.deadlineElapsedRealtimeMillis, SystemClock.elapsedRealtime())) {
            finishSession(session)
            return NativeStartResult.Rejected("DeadlineExceeded", "Task deadline passed before native preparation")
        }
        uiStateHolder.isPlayButtonEnabled = false
        notifyState(session, NativeAutomationState.Starting)
        launchPreparation(session) prepare@{
            try {
                if (!mayStart(session)) return@prepare
                if (TapperService.instance == null || screenshotServiceHolder.screenshotService !== screenshotService) {
                    failPreparation(session, "RequiresUserConsent")
                    return@prepare
                }
                battleRequests.apply(validated)
                if (!mayStart(session)) return@prepare
                val decisions = if (validated.request.decisionMode == "smart") {
                    AutomationBridge.createDecisionProvider().also { check(it.enabled) { "Smart provider is unavailable" } }
                } else DisabledNativeBattleDecisionProvider
                val component = automationComponentBuilder.screenshotService(screenshotService)
                    .runPreferences(ScriptRunPreferences.automation(preferences, validated, decisions)).build()
                if (!mayStart(session)) return@prepare
                val entryPoint = EntryPoints.get(component, ScriptEntryPoint::class.java)
                bindSessionResources(session, entryPoint)
                if (!mayStart(session)) return@prepare
                runEntryPoint(session, screenshotService) { entryPoint.battle() }
            } catch (e: Exception) {
                Timber.e(e, "Automation preparation failed")
                failPreparation(session, if (requiresUserConsent(e)) "RequiresUserConsent" else "PreparationFailed", e.message)
            }
        }
        return NativeStartResult.Accepted
    }

    fun pauseAutomation(paused: Boolean): Boolean {
        return changePause(if (paused) PauseAction.Pause else PauseAction.Resume, automationOnly = true)
    }

    /** Acceptance means stop was requested. onExit is the authoritative native exit boundary. */
    fun stopAutomation(): Boolean = requestStop(automationOnly = true)

    /**
     * The OCR engine belonging to the current [ScriptComponent]. Held so its native Tesseract
     * context can be freed as soon as the run ends instead of waiting on the finalizer.
     */
    private var ocrService: OcrService? = null

    @Synchronized
    fun releaseOcrService(): Boolean {
        val current = ocrService ?: return true
        ocrService = null
        return runCatching { current.close() }
            .onFailure { Timber.w(it, "Failed to release the OCR service") }
            .isSuccess
    }

    private suspend fun showBattleExit(
        context: Context,
        exception: AutoBattle.ExitException
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            var dialog: DialogInterface? = null

            val composeView = FakedComposeView(context) {
                BattleExit(
                    exception = exception,
                    prefs = preferences,
                    prefsCore = prefsCore,
                    onClose = { dialog?.dismiss() },
                    onCopy = {
                        clipboardManager.set(context, exception)
                    }
                )
            }

            dialog = showOverlayDialog(context) {
                setView(composeView.view)

                setOnDismissListener {
                    composeView.close()
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun onScriptExit(e: Exception, session: RunSession): Job {
        // EntryPoint invokes this listener before its script thread actually returns.
        session.nativeThread = Thread.currentThread()
        session.threadStarted = true
        return cleanupScope.launch { handleScriptExit(e, session) }
    }

    private suspend fun handleScriptExit(e: Exception, session: RunSession) {
        if (activeSession !== session) return
        uiStateHolder.uiState = ScriptRunnerUIState.Idle
        uiStateHolder.isPlayButtonEnabled = false

        if (session.listener != null) {
            val nativeExit = if (requiresUserConsent(e)) {
                val state = (e as? AutoBattle.ExitException)?.state
                NativeAutomationExit("RequiresUserConsent", (e as? AutoBattle.ExitException)?.state?.timesRan
                    ?: completedRuns.completedRuns.value, e.message,
                    nativeBattleOutcome("RequiresUserConsent", state?.timesRan ?: 0, session.maxRuns, state?.withdrawCount ?: 0),
                    state?.withdrawCount ?: 0)
            } else if (e is AutoBattle.ExitException) {
                val reason = e.reason.javaClass.simpleName
                NativeAutomationExit(reason, e.state.timesRan, e.reason.cause?.message,
                    nativeBattleOutcome(reason, e.state.timesRan, session.maxRuns, e.state.withdrawCount), e.state.withdrawCount)
            } else {
                NativeAutomationExit(
                    if (e is ScriptAbortException) "Abort" else "Unexpected",
                    completedRuns.completedRuns.value,
                    e.message
                )
            }
            // Terminal cleanup and callback precede (and for automation replace) UI exit dialogs.
            completeAutomation(session, nativeExit)
            return
        }

        session.pendingInteractiveExit = e
        if (!beginCleanup(session)) return
        try {
            val failures = releaseSessionResources(session)
            if (failures.isNotEmpty()) {
                reportCleanupFailure(session, failures.joinToString("; "))
                return
            }
            if (serviceDestroyed) {
                finishSession(session)
                return
            }

            val scriptExitedString by lazy { context.getString(R.string.script_exited) }

            when (e) {
                is SupportImageMaker.ExitException -> {
                    when (e.reason) {
                        SupportImageMaker.ExitReason.NotFound -> {
                            val msg = context.getString(R.string.support_img_maker_not_found)

                            messages.notify(msg)
                            messageBox.show(scriptExitedString, msg)
                        }

                        SupportImageMaker.ExitReason.Success -> showSupportImageNamer(context, storageProvider)
                    }
                }

                is AutoLottery.ExitException -> {
                    val msg = when (e.reason) {
                        AutoLottery.ExitReason.PresentBoxFull -> context.getString(R.string.present_box_full)
                        AutoLottery.ExitReason.RanOutOfCurrency -> context.getString(R.string.lottery_currency_depleted)
                    }

                    messages.notify(msg)
                    messageBox.show(scriptExitedString, msg)
                }

                is AutoGiftBox.ExitException -> {
                    val msg = when (val reason = e.reason) {
                        is AutoGiftBox.ExitReason.CannotSelectAnyMore -> context.getString(
                            R.string.picked_exp_stacks,
                            reason.pickedStacks,
                            reason.pickedGoldEmbers
                        )

                        AutoGiftBox.ExitReason.NoEmbersFound -> context.getString(R.string.no_embers_found)
                    }

                    messages.notify(msg)
                    messageBox.show(scriptExitedString, msg)
                }

                is AutoFriendGacha.ExitException -> {
                    val msg = when (val reason = e.reason) {
                        AutoFriendGacha.ExitReason.InventoryFull -> context.getString(R.string.inventory_full)
                        is AutoFriendGacha.ExitReason.Limit -> context.getString(R.string.times_rolled, reason.count)
                    }

                    messages.notify(msg)
                    messageBox.show(scriptExitedString, msg)
                }

                is AutoBattle.ExitException -> {
                    preferences.hidePlayButton = false

                    if (e.reason !is AutoBattle.ExitReason.Abort) {
                        messages.notify(scriptExitedString)
                    }

                    showBattleExit(service, e)
                }
                is AutoServantLevel.ExitException -> {
                    val msg = when (val reason = e.reason) {
                        AutoServantLevel.ExitReason.NoServantSelected ->
                            context.getString(R.string.servant_enhancement_none_selected)

                        is AutoServantLevel.ExitReason.Unexpected -> {
                            e.let {
                                "${context.getString(R.string.unexpected_error)}: ${reason.exception.message}"
                            }
                        }

                        AutoServantLevel.ExitReason.MaxLevelAchieved ->
                            context.getString(R.string.servant_enhancement_max_level)

                        AutoServantLevel.ExitReason.NoEmbersOrQPLeft ->
                            context.getString(R.string.servant_enhancement_no_embers_or_qp_left)

                        AutoServantLevel.ExitReason.Abort ->
                            context.getString(R.string.servant_enhancement_aborted)

                        AutoServantLevel.ExitReason.RedirectAscension ->
                            context.getString(R.string.servant_enhancement_redirect_ascension_success)
                        AutoServantLevel.ExitReason.RedirectGrail ->
                            context.getString(R.string.servant_enhancement_redirect_grail_success)

                        AutoServantLevel.ExitReason.UnableToPerformAscension ->
                            context.getString(R.string.servant_enhancement_perform_ascension_failed)
                    }
                    messageBox.show(scriptExitedString, msg)
                }

                is ScriptAbortException -> {
                    // user aborted. do nothing
                }

                is AutoCEBomb.ExitException -> {
                    val msg = when (e.reason) {
                        AutoCEBomb.ExitReason.NoSuitableTargetCEFound -> context.getString(R.string.error_no_suitable_target_ce_found)
                    }

                    messages.notify(msg)
                    messageBox.show(scriptExitedString, msg)
                }

                is KnownException -> {
                    messages.notify(scriptExitedString)

                    messageBox.show(scriptExitedString, e.reason.msg(context))
                }

                else -> {
                    println(e.messageAndStackTrace)

                    val msg = context.getString(R.string.unexpected_error)
                    messages.notify(msg)

                    messageBox.show(msg, e.messageAndStackTrace, e)
                }
            }

            delay(250.milliseconds)
            finishSession(session)
        } finally {
            endCleanup(session)
        }
    }

    private fun getEntryPoint(entryPoint: ScriptEntryPoint): EntryPoint =
        when (preferences.scriptMode) {
            ScriptModeEnum.Battle -> entryPoint.battle()
            ScriptModeEnum.FP -> entryPoint.fp()
            ScriptModeEnum.Lottery -> entryPoint.lottery()
            ScriptModeEnum.PresentBox -> entryPoint.giftBox()
            ScriptModeEnum.SupportImageMaker -> entryPoint.supportImageMaker()
            ScriptModeEnum.CEBomb -> entryPoint.ceBomb()
            ScriptModeEnum.ServantLevel -> entryPoint.servantLevel()
        }

    enum class PauseAction {
        Pause, Resume, Toggle
    }

    fun pause(action: PauseAction): Boolean = changePause(action)

    private fun changePause(action: PauseAction, automationOnly: Boolean = false): Boolean {
        val reservation = runGate.currentReservation(automationOnly) ?: return false
        var changed = false
        var event: NativeAutomationState? = null
        var session: RunSession? = null
        runGate.withReservation(reservation) {
            session = activeSession?.takeIf { it.reservation === reservation }
            if (session == null) return@withReservation
            val state = scriptState
            if (state is ScriptState.Started) {
                if (state.paused && action != PauseAction.Pause) {
                    uiStateHolder.uiState = ScriptRunnerUIState.Running
                    state.entryPoint.exitManager.resume()
                    state.paused = false
                    changed = true
                    event = NativeAutomationState.Running
                } else if (!state.paused && action != PauseAction.Resume) {
                    state.entryPoint.exitManager.pause()
                    uiStateHolder.uiState = ScriptRunnerUIState.Paused(state.entryPoint.pausedStatus())
                    state.paused = true
                    changed = true
                    event = NativeAutomationState.Paused
                }
            }
        }
        session?.let { current -> event?.let { notifyState(current, it) } }
        return changed
    }

    /**
     * Sets [IPreferences.gameServer] from the Game Server setting.
     *
     * @return false if the setting is auto-detect and no FGO app has been detected,
     * in which case the server is left unchanged.
     */
    private fun updateGameServer(): Boolean {
        val server = prefsCore.gameServerRaw.get()

        if (server == PrefsCore.GAME_SERVER_AUTO_DETECT) {
            val detected = TapperService.instance?.detectedFgoServer
                ?: return false

            Timber.d("Using auto-detected Game Server: $detected")
            preferences.gameServer = detected

            return true
        }

        preferences.gameServer =
            try {
                GameServers.deserialize(server)?.also {
                    Timber.d("Using Game Server: $it")
                } ?: GameServers.default
            } catch (e: Exception) {
                Timber.e(e, "Game Server: Falling back to NA")

                GameServers.default
            }

        return true
    }

    fun startScript(
        context: Context,
        screenshotService: ScreenshotService,
        componentBuilder: ScriptComponentBuilder
    ) {
        if (serviceDestroyed) return
        val reservation = runGate.reserve(ScriptRunGate.Owner.Interactive) ?: return
        val session = RunSession(reservation)
        activeSession = session
        scriptState = ScriptState.Starting
        val isGameServerKnown = updateGameServer()

        if (!isGameServerKnown) {
            finishSession(session)
            Timber.w("Game Server: auto-detect found no FGO app, not starting")

            scope.launch {
                messageBox.show(
                    context.getString(R.string.p_game_server),
                    context.getString(
                        R.string.error_game_server_not_detected,
                        context.getString(R.string.p_more_options),
                        context.getString(R.string.p_script_mode_battle),
                        context.getString(R.string.p_game_server)
                    )
                )
            }

            return
        }

        uiStateHolder.isPlayButtonEnabled = false

        launchPreparation(session) prepare@{
            try {
                if (!mayStart(session)) return@prepare
                val scriptComponent = componentBuilder.screenshotService(screenshotService)
                    .runPreferences(ScriptRunPreferences(preferences)).build()
                val hiltEntryPoint = EntryPoints.get(scriptComponent, ScriptEntryPoint::class.java)
                bindSessionResources(session, hiltEntryPoint)
                // Projection startup can fail before delivering its first frame.
                val detectedMode = hiltEntryPoint.autoDetect().get()
                if (!mayStart(session)) return@prepare
                val resp = scriptPicker(context, detectedMode)
                if (!runGate.canPrepare(reservation) || resp is ScriptLauncherResponse.Cancel) {
                    failPreparation(session, "Abort")
                    return@prepare
                }
                launcherResponseHandler.handle(resp)
                delay(500.milliseconds)
                runEntryPoint(session, screenshotService) { getEntryPoint(hiltEntryPoint) }
            } catch (e: Exception) {
                Timber.e(e, "Script preparation failed")
                failPreparation(session, "PreparationFailed", e.message)
                val msg = if (e is KnownException)
                    e.reason.msg(context)
                else "${context.getString(R.string.unexpected_error)}: ${e.message}"
                messageBox.show(context.getString(R.string.unexpected_error), msg)
            }
        }
    }

    fun stopScript() {
        requestStop()
    }

    private fun requestStop(automationOnly: Boolean = false): Boolean {
        val owned = activeSession ?: return false
        val reservation = runGate.requestStop(automationOnly, expected = owned.reservation) ?: return false
        var session: RunSession? = null
        runGate.withReservation(reservation) {
            session = activeSession?.takeIf { it.reservation === reservation }
            if (session == null) return@withReservation
            uiStateHolder.isPlayButtonEnabled = false
            val state = scriptState
            if (state is ScriptState.Started) {
                scriptState = ScriptState.Stopping(state)
                state.entryPoint.stop()
            }
        }
        session?.let { notifyState(it, NativeAutomationState.Stopping) }
        session?.let { retryCleanup(it) }
        return true
    }

    private suspend fun runEntryPoint(
        session: RunSession,
        screenshotService: ScreenshotService,
        entryPointProvider: () -> EntryPoint
    ) {
        if (!mayStart(session)) return

        val recording = try {
            if (preferences.recordScreen) {
                withContext(Dispatchers.Main) {
                    screenshotService.startRecording()
                }
            } else null
        } catch (e: Exception) {
            val msg = context.getString(R.string.cannot_start_recording)
            Timber.e(e, msg)
            withContext(Dispatchers.Main) {
                Toast.makeText(service, "${msg}: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            null
        }

        session.recording = recording
        if (!mayStart(session)) return

        val entryPoint = entryPointProvider().apply {
            scriptExitListener = { onScriptExit(it, session) }
        }
        if (!mayStart(session)) return

        val prepared = runGate.start(session.reservation) {
            scriptState = ScriptState.Started(entryPoint, recording)
            uiStateHolder.uiState = ScriptRunnerUIState.Running
            uiStateHolder.isRecording = recording != null
        }
        var threadStarted = false
        var deadlineExpired = false
        if (prepared) {
            notifyState(session, NativeAutomationState.Running)
            // Listener callbacks never run under the reservation lock; stop can cancel this handoff.
            runGate.start(session.reservation) {
                deadlineExpired = startupDeadlineExpired(session.deadlineElapsedRealtimeMillis, SystemClock.elapsedRealtime())
                if (!deadlineExpired && !serviceDestroyed) {
                    // Upstream run() creates and starts the thread, then returns.
                    // Creation/start failure has no executor; a fast exit callback also marks started.
                    startNativeEntryPoint({ entryPoint.run() }) { session.threadStarted = true }
                    threadStarted = true
                }
            }
        }
        if (!threadStarted) {
            failPreparation(session, if (deadlineExpired) "DeadlineExceeded" else "Abort")
        }
    }

    private suspend fun scriptPicker(
        context: Context,
        detectedMode: ScriptModeEnum
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<ScriptLauncherResponse> { continuation ->

            var dialog: DialogInterface? = null

            val composeView = FakedComposeView(context) {
                ScriptLauncher(
                    scriptMode = detectedMode,
                    onResponse = {
                        continuation.resume(it)
                        dialog?.dismiss()
                    },
                    prefs = preferences,
                    prefsCore = prefsCore
                )
            }

            dialog = showOverlayDialog(context) {
                setView(composeView.view)

                setOnDismissListener {
                    uiStateHolder.isPlayButtonEnabled = true
                    composeView.close()
                    try {
                        continuation.resume(ScriptLauncherResponse.Cancel)
                    } catch (e: IllegalStateException) {
                        // Ignore exception on resuming twice
                    }
                }
            }
        }
    }

    fun showStatus(status: Exception) {
        if (isAutomationBusy) return
        if (status is AutoBattle.ExitException) {
            scope.launch { showBattleExit(service, status) }
        }
    }
}
