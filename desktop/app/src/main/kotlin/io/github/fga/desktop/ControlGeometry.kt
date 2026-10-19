package io.github.fga.desktop

import io.github.fate_grand_automata.scripts.FgoGameAreaManager
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.*
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.*
import java.lang.reflect.Proxy
import kotlin.time.Duration

/** Actual upstream scale, transform and location graph. No legacy keymap. */
class ControlGeometry(size: Size, region: String) {
    val area = FgoGameAreaManager(size) { Location() }
    val scale = RealScale(area)
    val transform = RealTransformer(area, scale)
    private val server = when(region) {
        "JP" -> GameServer.Jp.Original
        "CN" -> GameServer.Cn
        "NA" -> GameServer.En.Original
        "TW" -> GameServer.Tw
        else -> error("Unsupported region")
    }
    // Read-only preference subset used by ScriptAreaTransforms. Unknown properties
    // fail, rather than selecting a default refill/command-spell/boost policy.
    private val prefs = Proxy.newProxyInstance(IPreferences::class.java.classLoader,
        arrayOf(IPreferences::class.java)) { _, method, _ ->
        when(method.name) { "getGameServer" -> server; else -> error("Unconfigured preference: ${method.name}") }
    } as IPreferences
    val platform = object : PlatformImpl {
        override val windowRegion = Region(Location(), size)
        override val canLongSwipe = false
        override val prefs = object : PlatformPrefs {
            override val debugMode=false
            override val minSimilarity=.8
            override val waitMultiplier=1.0
            override val swipeMultiplier=1.0
        }
        override fun getResizableBlankPattern(): Pattern = error("Image recognition is provided by the isolated read-only bridge")
        override fun highlight(region: Region, duration: Duration, color: HighlightColor) = Unit
    }
    val transforms = ScriptAreaTransforms(prefs, scale, area, platform)
    val attack = AttackScreenLocations(transforms)
    val battle = BattleScreenLocations(transforms, javax.inject.Provider {
        error("Use observed master anchor; no implicit master-position fallback")
    })
    val support = SupportScreenLocations(transforms)
    val locations = Locations(transforms, FPLocations(transforms), LotteryLocations(transforms),
        support, attack, battle, ServantLevelLocations(transforms))

    // Preserve the published detector's centered 16:9 crop inside FGA's area.
    val imageOffsetX get() = ((area.gameArea.width*720.0/area.gameArea.height-1280)/2).toInt().coerceAtLeast(0)
    fun fromNormalized(x:Int,y:Int)=transform.fromImage(Region(x+imageOffsetX,y,1,1)).location

    fun card(code: Int): CommandCard {
        require(code in 1..8)
        return if(code<=5) CommandCard.Face.list[code-1] else CommandCard.NP.list[code-6]
    }

    fun locate(action: String, index: Int=0, slot: Int=0, count: Int=0,
               anchor: Location?=null): Location = when(action) {
        "attack" -> battle.attackClick
        "servant_skill" -> { require(slot in 0..2 && index in 0..2); battle.locate(Skill.Servant.list[slot*3+index]) }
        "enemy_target" -> { if(count==6) battle.sixEnemyTarget(index) else {require(index in 0..2); battle.locate(EnemyTarget.list[index])} }
        "skill_target" -> {
            require(index in 0..3 && count in 1..3)
            val target = when(count) {
                1 -> ServantTarget.B
                2 -> if(index<=1) ServantTarget.Left else ServantTarget.Right
                else -> listOf(ServantTarget.B,ServantTarget.A,ServantTarget.B,ServantTarget.C)[index]
            }
            battle.locate(target) ?: error("No target location")
        }
        "master_open" -> MasterAnchoredLocations.open(requireNotNull(anchor))
        "master_skill" -> { require(index in 0..2); MasterAnchoredLocations.locate(Skill.Master.list[index], requireNotNull(anchor)) }
        "order_member" -> { require(index in 0..5); battle.locate((OrderChangeMember.Starting.list + OrderChangeMember.Sub.list)[index]) }
        "order_confirm" -> battle.orderChangeOkClick
        "skill_confirm" -> battle.skillOkClick
        "skill_close" -> battle.extraInfoWindowCloseClick
        "card_back" -> attack.backClick
        "quest_first" -> locations.menuSelectQuestClick
        "support_first" -> support.firstSupportClick
        "formation_start" -> locations.menuStartQuestClick
        "boost_skip" -> locations.locate(BoostItem.Enabled.Skip)
        "support_refresh" -> support.updateClick
        "support_refresh_confirm" -> support.updateYesClick
        "result_next" -> locations.resultClick
        "friend_decline" -> locations.resultFriendRequestRejectClick
        else -> error("Unsupported or resource-consuming action: $action")
    }
}
