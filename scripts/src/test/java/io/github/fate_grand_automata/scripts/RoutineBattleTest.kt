package io.github.fate_grand_automata.scripts
import io.github.fate_grand_automata.scripts.modules.RoutineBattle
import kotlin.test.*

class RoutineBattleTest {
    @Test fun explicitVictoryOnlyAndNoDoubleCount() {
        val r=RoutineBattle("battle",1,false)
        assertEquals("turn",r.advance("turn").kind)
        assertEquals(0,r.advance("unknown").wins)
        val won=r.advance("result")
        assertTrue(won.newVictory);assertEquals(1,won.wins)
        assertEquals("complete",r.finishBoundary().kind)
        assertEquals(1,r.advance("result").wins)
    }
    @Test fun initialResultAndDefeatAreNotThisBattle() {
        assertEquals(0,RoutineBattle("battle",1,false).advance("result").wins)
        assertEquals("unsupported_start_screen",RoutineBattle("battle",1,false).advance("defeated").reason)
    }
    @Test fun lostNeverClaimsRetreat() {
        val r=RoutineBattle("battle",1,false);r.advance("turn")
        assertEquals("lost",r.advance("defeated").reason)
    }
    @Test fun apRequiresExplicitSceneAndUntilMode() {
        assertEquals("complete",RoutineBattle("farm",null,true).advance("ap_empty").kind)
        assertEquals("handoff",RoutineBattle("farm",1,false).advance("ap_empty").kind)
        assertEquals("pre_boundary",RoutineBattle("farm",null,true).advance("unknown").kind)
    }
    @Test fun firstQuestAndFormationUseExplicitFlow() {
        val r=RoutineBattle("farm",2,false)
        assertEquals("quest_first",r.advance("quest").action)
        assertEquals("support_select",r.advance("support").action)
        assertEquals("formation_start",r.advance("formation").action)
        assertEquals(1,r.advance("turn").turn)
        r.advance("result")
        assertEquals(1,r.finishBoundary().wins)
        assertEquals("unverified_dialog",r.advance("repeat").reason)
    }
    @Test fun repeatedTransitionIsNotRetriedForever() {
        val r=RoutineBattle("farm",1,false)
        r.advance("quest")
        assertEquals("observe",r.advance("quest").kind)
    }
    @Test fun nextBattleResetsTurnAfterPostBoundaryLanding() {
        val r=RoutineBattle("farm",2,false)
        r.advance("turn");r.advance("turn");r.advance("result")
        val next=r.boundaryLanding("turn")
        assertEquals(1,next.wins);assertEquals(1,next.turn)
        assertEquals("turn",next.kind)
    }

    @Test fun reviewVerifiedRepeatContinuesUntilExplicitAp() {
        val r=RoutineBattle("farm",null,true)
        assertEquals("repeat_continue",r.advance("repeat_verified").action)
        r.advance("support");r.advance("formation");r.advance("turn")
        r.advance("result")
        assertEquals("repeat_continue",r.advance("repeat_verified").action)
        r.advance("support");r.advance("formation");r.advance("turn")
        assertEquals(2,r.advance("result").wins)
        assertEquals("repeat_continue",r.advance("repeat_verified").action)
        val done=r.advance("ap_empty")
        assertEquals("complete",done.kind);assertEquals(2,done.wins)
    }
    @Test fun reviewFiniteTargetDeclinesRepeat() {
        val r=RoutineBattle("farm",2,false)
        repeat(2) {
            r.advance("turn");r.advance("result")
            assertEquals(if(it==0) "repeat_continue" else "repeat_decline",r.advance("repeat_verified").action)
        }
        assertEquals("complete",r.advance("quest").kind)
    }
    @Test fun reviewSlowQuestWaitsWithoutSecondInput() {
        val r=RoutineBattle("farm",1,false)
        assertEquals("quest_first",r.advance("quest").action)
        repeat(5) { assertEquals("observe",r.advance("quest").kind) }
        assertEquals("support_select",r.advance("support").action)
    }

    @Test fun reviewStalledTransitionHasBoundedDeadline() {
        var now=0L;val r=RoutineBattle("farm",2,false,{now},1000)
        r.advance("quest");now=999;assertEquals("observe",r.advance("quest").kind)
        now=1000;assertEquals("transition_timeout",r.advance("quest").reason)
    }
    @Test fun reviewResultPagesAreSemanticAndNeverRepeated() {
        val r=RoutineBattle("farm",2,false)
        r.advance("turn");r.advance("result")
        assertEquals("result_next",r.advance("result").action)
        assertEquals("observe",r.advance("result").kind)
        assertEquals("result_next",r.advance("result_master_exp").action)
        assertEquals("observe",r.advance("result_master_exp").kind)
        assertEquals("friend_decline",r.advance("friend_request").action)
        assertEquals(1,r.advance("repeat_verified").wins)
    }

    @Test fun reviewSlowFormationConfirmationIsNotRetried() {
        val r=RoutineBattle("farm",1,false)
        r.advance("quest");r.advance("formation")
        assertEquals("formation_confirm",r.advance("formation_confirm").action)
        assertEquals("observe",r.advance("formation_confirm").kind)
        assertEquals("turn",r.advance("turn").kind)
    }

    @Test fun reviewBattleWaitsForMaterialPageBeforeCompletion() {
        val r=RoutineBattle("battle",1,false)
        r.advance("turn");r.advance("result_bond")
        assertEquals("observe",r.finishBoundary().kind)
        r.advance("result")
        assertEquals("complete",r.finishBoundary().kind)
    }

}
