package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Mechanical external card delivery, isolated from upstream priority and script execution. */
@ScriptScope
class ExternalCardExecutor @Inject constructor(
    api: IFgoAutomataApi,
    private val caster: Caster
) : IFgoAutomataApi by api {
    fun handFacts(cards: List<ParsedCard>): List<NativeDecisionCard> = cards.map {
        NativeDecisionCard(it.card.index, it.type.name, it.affinity.name,
            if (it.servant.position in 1..6) it.servant.position.toString() else "unknown",
            it.fieldSlot?.position?.minus(1), it.isStunned)
    }

    /** Explicit slots go straight to original Caster, including its original NP warning close. */
    fun executeOrder(order: List<Int>) {
        deliverNativeCardOrder(order, { id ->
            when (id) {
                in 1..5 -> caster.use(CommandCard.Face.list[id - 1])
                in 6..8 -> caster.use(CommandCard.NP.list[id - 6])
                else -> throw NativeDecisionException("UnsupportedNativeCard")
            }
        }, {
            // Same ordinary post-card pause as Battle.performBattle; then native AutoBattle owns transitions.
            0.5.seconds.wait()
        })
    }
}
