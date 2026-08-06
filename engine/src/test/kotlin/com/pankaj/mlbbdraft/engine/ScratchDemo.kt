package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.PlayerProfile
import com.pankaj.mlbbdraft.engine.model.Side
import org.junit.Test

class ScratchDemo {
    @Test
    fun demo() {
        val engine = DraftEngine(DatasetLoader.fromResources())
        val state = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ENEMY)
            .withBan(Side.ENEMY, 0, "khufra")
            .withPick(Side.ENEMY, 0, Pick("ling", Lane.JUNGLE))
            .withPick(Side.ENEMY, 1, Pick("estes", Lane.ROAM))
            .withPick(Side.ENEMY, 2, Pick("claude", Lane.GOLD))
            .withPick(Side.ALLY, 0, Pick("atlas", Lane.ROAM))
            .withPick(Side.ALLY, 1, Pick("melissa", Lane.GOLD))
            .copy(profile = PlayerProfile(owned = setOf("phoveus", "yu-zhong"), comfort = mapOf("yu-zhong" to 4)))

        println("\n=== ENEMY: Ling(jg) Estes(roam) Claude(gold) | ALLY: Atlas(roam) Melissa(gold) ===")
        println("--- EXP LANE PICKS ---")
        engine.suggestPicks(state, Lane.EXP, limit = 3).forEachIndexed { i, s ->
            println("${i + 1}. ${s.hero.name} [${s.displayScore}]")
            s.reasons.forEach { println("     - $it") }
        }
        println("--- MID LANE PICKS ---")
        engine.suggestPicks(state, Lane.MID, limit = 2).forEachIndexed { i, s ->
            println("${i + 1}. ${s.hero.name} [${s.displayScore}]")
            s.reasons.forEach { println("     - $it") }
        }
        println("--- BANS ---")
        engine.suggestBans(state, limit = 3).forEach { println("  ${it.hero.name}: ${it.reasons}") }
        println("--- ITEMS ---")
        engine.itemAdvice(state).take(4).forEach { println("  P${it.priority} ${it.item} (${it.forWhom}) — ${it.reason}") }
        println("--- THREATS ---")
        val threats = engine.threatReport(state)
        println("  tempo: ${threats.tempo}")
        threats.threats.forEach { println("  ${it.hero.name}: ${it.tip}") }
        threats.tips.forEach { println("  tip: $it") }
        println("--- ALLY COMP ---")
        val report = engine.compReport(state, Side.ALLY)
        println("  damage=${report.damage} frontline=${report.frontlineCount}")
        report.warnings.forEach { println("  warn: $it") }
        report.strengths.forEach { println("  good: $it") }
        println("  needs: ${report.needs.missing.map { it.label }}\n")
    }
}
