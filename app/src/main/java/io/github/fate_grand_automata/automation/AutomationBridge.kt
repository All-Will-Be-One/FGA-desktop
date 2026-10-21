package io.github.fate_grand_automata.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.runner.*
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.models.BoostItem
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fga.automation.*
import kotlinx.serialization.json.*
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/** Process-local adapter to the real, UI-shared ScriptManager. No game loop. */
object AutomationBridge {
    private val main=Handler(Looper.getMainLooper())
    private val instanceId=UUID.randomUUID().toString()
    private var manager=WeakReference<ScriptManager>(null)
    @Volatile private var owner:String?=null
    @Volatile private var activeRunner:ScriptManager?=null
    @Volatile private var activeRequest:BattleRunRequest?=null
    @Volatile private var activeScript:String?=null
    private var preferences:IPreferences?=null
    private var controller:AutomationController?=null
    private var dispatcher:RpcDispatcher?=null
    private var initializationFailure:String?=null

    @Synchronized fun attach(value:ScriptManager) { manager=WeakReference(value) }
    @Synchronized fun detach(value:ScriptManager) {
        if(manager.get()===value) manager.clear()
        if(activeRunner===value) owner?.let { controller?.uncertain(it,"ServiceDestroyed") }
    }

    @Synchronized fun initialize(context:Context,prefs:IPreferences) {
        if(controller!=null || initializationFailure!=null) return
        try {
            preferences=prefs
            val backend=object:NativeBattleBackend {
                override fun start(run:RunSnapshot,callback:NativeBattleCallback) {
                    val deadline=SystemClock.elapsedRealtime()+maxOf(0,run.createdAtMillis+run.limits.timeoutSeconds*1000-System.currentTimeMillis())
                    main.post {
                        // Stop may have arrived before this queued dispatch.
                        val current=controller?.status(runId=run.runId)
                        val remaining=deadline-SystemClock.elapsedRealtime()
                        if(remaining<=0) {
                            controller?.expire(run.runId)
                            callback.exit("DeadlineExceeded",0);return@post
                        }
                        if(current?.state==RunState.Stopping) {
                            callback.exit("CancelledBeforeStart",0);return@post
                        }
                        val runner=manager.get()
                        if(runner==null) {
                            callback.rejected("RequiresUserConsent","Open FGA Automation and prepare accessibility and screen capture in the normal UI")
                            return@post
                        }
                        val request=try {
                            runner.automationRequest(run.configId,run.limits.maxRuns,run.limits.timeoutSeconds,
                                SystemClock.elapsedRealtime()+remaining)
                        } catch(e:Exception) {
                            callback.rejected((e as? BattleRunValidationException)?.code ?: "InvalidConfiguration",e.message ?: "Invalid native battle configuration")
                            return@post
                        }
                        val commandSnapshot=prefs.battleConfigs.firstOrNull {it.id==run.configId}?.skillCommand
                        if(commandSnapshot==null) {
                            callback.rejected("UnknownConfig","Selected configuration disappeared before native dispatch")
                            return@post
                        }
                        try {
                            val result=runner.startAutomation(request,object:NativeAutomationListener {
                                override fun onStateChanged(state:NativeAutomationState) {
                                    if(state==NativeAutomationState.Starting) {
                                        // Only the successfully reserved API runner emits Starting.
                                        // A Busy result must never borrow the manual runner's input guard.
                                        activeRequest=request
                                        activeScript=commandSnapshot
                                        activeRunner=runner
                                        owner=run.runId
                                    }
                                    callback.state(RunState.valueOf(state.name))
                                }
                                override fun onExit(exit:NativeAutomationExit) {
                                    callback.exit(exit.reason,exit.completedRuns,exit.error,exit.outcome,exit.withdrawCount)
                                    if(owner==run.runId) { owner=null;activeRunner=null;activeRequest=null;activeScript=null }
                                }
                                override fun onCleanupFailure(error:String) {
                                    controller?.uncertain(run.runId,"NativeCleanupUnconfirmed",error)
                                }
                            })
                            when(result) {
                                NativeStartResult.Accepted -> {
                                    main.postDelayed({controller?.expire(run.runId)},maxOf(0,deadline-SystemClock.elapsedRealtime()))
                                }
                                NativeStartResult.Busy -> {
                                    callback.rejected("Busy","Native UI or another task owns the runner")
                                    // Observe the foreign runner's real drain; never control it.
                                    runner.onProcessRunnerIdle { callback.exit("Busy",0) }
                                }
                                NativeStartResult.RequiresUserConsent -> { owner=null;activeRunner=null;activeRequest=null;activeScript=null;callback.rejected("RequiresUserConsent","Accessibility or screen capture must be prepared by the user") }
                                is NativeStartResult.Rejected -> { owner=null;activeRunner=null;activeRequest=null;activeScript=null;callback.rejected(result.code,result.message) }
                            }
                        } catch(e:Exception) {
                            // A native runner may already own a thread. Never label
                            // an exception after dispatch as a safe rejection.
                            controller?.uncertain(run.runId,"NativeDispatchUnconfirmed",e.message)
                        }
                    }
                }
                override fun control(runId:String,op:String) {
                    main.post {
                        if(controller?.permitControl(runId,op)!=true) return@post
                        val runner=activeRunner
                        if(owner!=runId || runner==null) {
                            // A queued start handles Stopping without dispatch.
                            if(controller?.status(runId=runId)?.state!=RunState.Stopping) controller?.uncertain(runId,"RunnerUnavailable")
                            return@post
                        }
                        val accepted=when(op) {
                            "stop" -> runner.stopAutomation()
                            "pause" -> runner.pauseAutomation(true)
                            "resume" -> runner.pauseAutomation(false)
                            else -> false
                        }
                        if(!accepted) controller?.uncertain(runId,"NativeControlUnconfirmed")
                    }
                }
            }
            controller=AutomationController(FileJournalStore(File(context.filesDir,"automation/runs-v1.json")) { directory ->
                val fd=Os.open(directory.path,OsConstants.O_RDONLY,0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            },backend)
            dispatcher=RpcDispatcher(requireNotNull(controller),{
                buildJsonObject {
                    put("backend","fga-android-native");put("applicationId",BuildConfig.APPLICATION_ID)
                    put("protocolVersion",1);put("versionName",BuildConfig.VERSION_NAME)
                    put("upstreamRevision","cfb0593c64aaaceeab3ba56040fa34ab8e32f7ba")
                    put("candidateRevision",BuildConfig.AUTOMATION_SOURCE_REVISION)
                    put("instanceId",instanceId);put("prepared",manager.get()?.isAutomationPrepared==true)
                    put("executorBusy",manager.get()?.isExecutorBusy==true)
                    put("capabilities",buildJsonObject {
                        listOf("battle","farm","configs","start","status","pause","resume","stop","events").forEach { put(it,true) }
                        put("perStepInput",false);put("smartStrategy",false);put("liveVerified",false)
                    })
                    put("regions",JsonArray(listOf("CN","JP","NA","TW","KR").map(::JsonPrimitive)))
                    put("transport","adb-shell-content-provider");put("callerPolicy","own_uid_or_shell_2000")
                    put("resources","none");put("resumeAfterProcessDeath",false)
                }
            },{
                buildJsonObject {
                    putJsonArray("configs") {
                        prefs.battleConfigs.forEach { config ->
                            val explicit=config.server ?: runCatching {manager.get()?.automationRequest(config.id,1,300)?.server}.getOrNull()
                            add(buildJsonObject {
                                put("id",config.id);put("name",config.name)
                                put("server",explicit?.let { JsonPrimitive(region(it)) } ?: JsonNull)
                                put("scriptSha256",MessageDigest.getInstance("SHA-256").digest(config.skillCommand.toByteArray()).joinToString("") { "%02x".format(it) })
                            })
                        }
                    }
                }
            })
        } catch(e:Exception) { initializationFailure=e.message ?: "Journal unavailable" }
    }
    @Synchronized fun request(text:String):String = dispatcher?.request(text) ?: buildJsonObject {
        put("ok",false);put("protocolVersion",1)
        putJsonObject("error") { put("code","JournalUnavailable");put("message",initializationFailure ?: "Provider not initialized") }
    }.toString()
    fun observe(type:String,detail:String) {
        val id=owner ?: return
        // Observation is best effort; it must never alter a native battle decision.
        runCatching { controller?.nativeEvent(id,type,detail) }
    }
    /** Recheck live settings at the existing platform input boundary. UI settings
     * can change while a run is active; they must not enable refills or a new script. */
    fun guardInput() {
        if(owner==null) return
        val request=activeRequest ?: throw IllegalStateException("Automation request snapshot is unavailable")
        val prefs=preferences ?: throw IllegalStateException("Automation preferences are unavailable")
        val config=prefs.selectedBattleConfig
        val limits=prefs.selectedServerConfigPref
        if(config.id!=request.configId || config.skillCommand!=activeScript || prefs.gameServer.simple!=request.server.simple ||
            prefs.scriptMode!=ScriptModeEnum.Battle || limits.resources.isNotEmpty() || limits.waitForAPRegen ||
            BoostItem.of(prefs.boostItemSelectionMode) !is BoostItem.Disabled || !limits.shouldLimitRuns ||
            limits.limitRuns !in 1..request.limits.limitRuns ||
            limits.shouldLimitMats!=request.limits.shouldLimitMaterials || limits.limitMats!=request.limits.limitMaterials ||
            limits.shouldLimitCEs!=request.limits.shouldLimitCEs || limits.limitCEs!=request.limits.limitCEs) {
            throw IllegalStateException("Automation configuration or resource limits changed during the run")
        }
    }
    private fun region(server:GameServer):String = when(server) {
        is GameServer.Jp -> "JP"
        is GameServer.En -> "NA"
        GameServer.Cn -> "CN"
        GameServer.Tw -> "TW"
        GameServer.Kr -> "KR"
    }
}
