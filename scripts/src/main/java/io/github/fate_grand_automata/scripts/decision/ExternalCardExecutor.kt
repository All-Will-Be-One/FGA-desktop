package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.toFieldSlot
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Mechanical external card delivery, isolated from upstream priority and script execution. */
@ScriptScope
class ExternalCardExecutor @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val caster: Caster,
    private val card: Card
) : IFgoAutomataApi by api {
    fun readHand(): List<NativeDecisionCard> = card.readCommandCards().map {
        NativeDecisionCard(it.card.index, it.type.name, it.affinity.name,
            if (it.servant.position in 1..6) it.servant.position.toString() else "unknown",
            it.fieldSlot?.position?.minus(1), it.isStunned)
    }.also(::validateNativeHand)

    private fun nativePane(expected: List<NativeDecisionCard>): NativeCardPane {
        val current = card.readCommandCards().map {
            NativeDecisionCard(it.card.index, it.type.name, it.affinity.name,
                if (it.servant.position in 1..6) it.servant.position.toString() else "unknown",
                it.fieldSlot?.position?.minus(1), it.isStunned)
        }
        val recognized = current.count { it.type != "Unknown" || it.stunned }
        return when {
            recognized == 0 -> NativeCardPane.Absent
            recognized == 5 && current == expected -> NativeCardPane.Visible
            else -> NativeCardPane.Unsupported
        }
    }

    fun verifyHand(expected: List<NativeDecisionCard>) {
        if (nativePane(expected) != NativeCardPane.Visible) throw NativeDecisionException("UnexpectedNativeCardScene")
    }

    /** Execute the external order without invoking priority, NP spam, or automatic fallback. */
    fun executeOrder(order: List<Int>, expected: List<NativeDecisionCard>) {
        validateNativeHand(expected)
        order.filter { it >= 6 }.forEach {
            require(servantTracker.deployed[CommandCard.NP.list[it - 6].toFieldSlot()]?.let { slot -> slot != TeamSlot.Unknown } == true) {
                "UnavailableNativeNpSlot"
            }
        }
        deliverNativeCardOrder(order, { nativePane(expected) }, { id, finalChoice ->
            when (id) {
                in 1..5 -> caster.use(CommandCard.Face.list[id - 1])
                in 6..8 -> caster.use(CommandCard.NP.list[id - 6], closeWarning = !finalChoice)
                else -> throw NativeDecisionException("UnsupportedNativeCard")
            }
            if (!finalChoice) 0.5.seconds.wait()
        }, {
            // Match Battle.performBattle's ordinary post-card pause, then return
            // to AutoBattle. Its existing loop owns NP/wave animations, the next
            // actionable turn and results. Delivery is not evidence of any of them.
            0.5.seconds.wait()
        })
    }

}
