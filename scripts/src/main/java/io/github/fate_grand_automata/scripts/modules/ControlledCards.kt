package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.locations.AttackScreenLocations
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.lib_automata.AutomataApi

/** Supplied by an observation provider; monotonic times must use the executor clock. */
data class CardInputEvidence(
    val frameId: String,
    val observedAtMillis: Long,
    val source: String,
    val selecting: Boolean,
    val alreadyPartial: Boolean,
    // Latest availability is resolved by the provider using both NP frames
    // (including blinking readiness and the latest seals), not per-frame brightness.
    val available: Map<CommandCard, Boolean?>,
)

data class CardDispatch(val attempted: Int, val gameEffect: String = "unverified")

open class CardDispatchInterrupted(val attempted: Int, val latestAttemptUncertain: Boolean, cause: Exception) :
    IllegalStateException("Card transaction interrupted; observe again, never replay", cause)

class CardDeliveryUnknown(attempted: Int, cause: Exception) : CardDispatchInterrupted(attempted,true,cause)

/**
 * Exact selection seam for external strategies. Uses FGA locations and AutomataApi;
 * does not invoke Card.clickCommandCards or Caster.use(NP), so there is no re-selection,
 * NP spam or warning-close tap. Used by the job-owned desktop control session.
 *
 * checkControl must reject cancellation, expired job deadlines, lost ownership and
 * geometry changes. Observer/preflight integration remains the caller's responsibility.
 */
class ControlledCards(
    private val api: AutomataApi,
    private val locations: AttackScreenLocations,
    private val nowMillis: () -> Long,
    private val checkControl: () -> Unit,
    private val afterInput: (Int) -> Unit = {},
) {
    private val consumedFrames = mutableSetOf<String>()

    @Synchronized
    fun dispatch(plan: List<CommandCard>, frames: List<CardInputEvidence>, deadlineMillis: Long = Long.MAX_VALUE): CardDispatch {
        require(plan.size == 3 && plan.distinct().size == 3) { "Exactly three distinct cards required" }
        require(frames.isNotEmpty()) { "Observation required" }
        val hasNP = plan.any { it is CommandCard.NP }
        require(frames.size == if (hasNP) 2 else 1) { "NP requires two observations" }
        require(frames.map { it.frameId }.distinct().size == frames.size) { "Distinct observations required" }
        val now = nowMillis()
        frames.forEach { frame ->
            require(frame.frameId.isNotEmpty() && frame.source.isNotEmpty()) { "Evidence provenance required" }
            require(frame.frameId !in consumedFrames) { "Consumed frame; observe again" }
            require(frame.observedAtMillis <= now && now - frame.observedAtMillis <= 30_000) { "Stale frame" }
            require(frame.selecting && !frame.alreadyPartial) { "Not an untouched card-selection screen" }
        }
        if (hasNP) require(frames[1].observedAtMillis - frames[0].observedAtMillis >= 150) {
            "NP evidence interval too short"
        }
        require(plan.all { frames.last().available[it] == true }) { "Disabled or unknown selected card" }
        val points = plan.map { locations.clickLocation(it) }
        consumedFrames.addAll(frames.map { it.frameId })
        var attempted = 0
        fun guard() {
            checkControl()
            check(nowMillis()<deadlineMillis) { "Card transaction deadline" }
            check(nowMillis()-frames.last().observedAtMillis<=30_000) { "Stale frame before input" }
        }
        try {
            points.forEach { point ->
                guard()
                attempted++
                try { api.run { point.click() } }
                catch(failure: Exception) { throw CardDeliveryUnknown(attempted,failure) }
                guard()
                afterInput(attempted)
                guard()
            }
        } catch(failure: CardDispatchInterrupted) { throw failure }
          catch(failure: Exception) { throw CardDispatchInterrupted(attempted,false,failure) }
        return CardDispatch(attempted)
    }
}
