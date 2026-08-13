package com.pankaj.mlbbdraft.engine.vision

import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.model.ItemTag
import java.util.Locale

/**
 * Matches only explicit item names supplied by on-device OCR. It deliberately has no icon-name
 * guessing: a visual scoreboard with unlabeled icons is useful evidence for the player, but not
 * sufficiently reliable evidence for automatic counter-item advice across patches and skins.
 */
data class EnemyBuildTextScan(
    val itemNames: List<String>,
    val signals: Set<EnemyBuildSignal>,
)

class EnemyBuildTextMatcher(items: List<Item>) {
    private val knownItems = items
        // Battle spells such as Flicker are HUD controls, never Equipment-screen inventory.
        .filter { it.category != ItemCategory.SPELL }
        .flatMap { item ->
            (setOf(item.name) + item.aliases)
                .map(::normalise)
                .filter { it.length >= MIN_ITEM_NAME_LENGTH }
                .map { NormalisedItem(item, it) }
        }
        // A shared alias is ambiguous evidence; omit it rather than binding it to one item.
        .groupBy { it.normalisedName }
        .mapNotNull { (_, candidates) ->
            candidates.map { it.item.id }.distinct().singleOrNull()?.let { candidates.first() }
        }

    fun scanItemNames(names: List<String>): EnemyBuildTextScan =
        scan(names.mapIndexed { index, name -> ScreenText(name, index, 0) })

    fun scan(lines: List<ScreenText>): EnemyBuildTextScan {
        val matches = linkedMapOf<String, Item>()
        lines.forEach { line ->
            val text = normalise(line.text)
            if (text.length < MIN_ITEM_NAME_LENGTH) return@forEach
            knownItems.firstOrNull { candidate -> text.contains(candidate.normalisedName) }
                ?.item
                ?.let { matches.putIfAbsent(it.id, it) }
        }

        val signals = matches.values.flatMapTo(linkedSetOf()) { item -> item.signals() }
        return EnemyBuildTextScan(
            itemNames = matches.values.map { it.name },
            signals = signals,
        )
    }

    private fun Item.signals(): Set<EnemyBuildSignal> = buildSet {
        if (has(ItemTag.LIFESTEAL) || has(ItemTag.SPELL_VAMP) || has(ItemTag.HEAL)) {
            add(EnemyBuildSignal.HEALING)
        }
        if (has(ItemTag.SHIELD) || has(ItemTag.ANTI_SHIELD)) add(EnemyBuildSignal.SHIELDS)
        if (has(ItemTag.ATTACK_SPEED) || has(ItemTag.ANTI_ATTACK_SPEED)) add(EnemyBuildSignal.ATTACK_SPEED)
        if (has(ItemTag.CRIT) || has(ItemTag.ANTI_CRIT)) add(EnemyBuildSignal.CRITICAL_DAMAGE)
        if (has(ItemTag.ARMOR_PEN)) add(EnemyBuildSignal.PHYSICAL_PENETRATION)
        if (has(ItemTag.MAGIC_POWER)) add(EnemyBuildSignal.MAGIC_BURST)
        if (has(ItemTag.MAGIC_PEN)) add(EnemyBuildSignal.MAGIC_PENETRATION)
        if (has(ItemTag.PHYSICAL_DEFENSE)) add(EnemyBuildSignal.ARMOR)
        if (has(ItemTag.MAGIC_DEFENSE)) add(EnemyBuildSignal.MAGIC_RESIST)
        if (has(ItemTag.HP)) add(EnemyBuildSignal.HIGH_HEALTH)
    }

    private data class NormalisedItem(
        val item: Item,
        val normalisedName: String,
    )

    private companion object {
        const val MIN_ITEM_NAME_LENGTH = 5

        fun normalise(value: String): String =
            value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    }
}
