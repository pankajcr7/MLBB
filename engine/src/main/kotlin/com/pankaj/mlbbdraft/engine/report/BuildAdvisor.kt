package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.DamageType
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.ItemTag
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Role
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.Trait

enum class BuildSlotKind { BOOTS, CORE, SITUATIONAL, SPELL }

data class BuildItem(
    val item: Item,
    /** Why this item, for this hero, against this enemy team. */
    val reason: String,
    val kind: BuildSlotKind,
)

/**
 * Emblem advice is given as an attribute priority rather than a named talent.
 * Talent trees get reshuffled most major patches; "prioritise magic penetration"
 * survives that, a wrong talent name does not.
 */
data class EmblemAdvice(
    val emblem: String,
    val priority: String,
    val reason: String,
)

data class HeroBuild(
    val hero: Hero,
    val boots: BuildItem?,
    val core: List<BuildItem>,
    val situational: List<BuildItem>,
    val spells: List<BuildItem>,
    val emblem: EmblemAdvice,
    val notes: List<String>,
) {
    /**
     * Purchase order: boots, two core, then the answers to their draft, then the rest
     * of the core. Counter items go in early — an anti-heal bought at 15 minutes is an
     * anti-heal bought too late.
     *
     * Always six slots when the catalog can supply them. The trailing core doubles as
     * padding, so a draft with no counter items yet still shows a complete build instead
     * of four items and a gap.
     */
    val order: List<BuildItem>
        get() = buildList {
            boots?.let { add(it) }
            addAll(core.take(2))
            addAll(situational)
            addAll(core.drop(2))
        }.distinctBy { it.item.id }.take(SLOTS)

    /** True when all six slots are filled. */
    val isComplete: Boolean get() = order.size == SLOTS

    val totalCost: Int get() = order.sumOf { it.item.cost }

    companion object {
        /** Boots plus five items. */
        const val SLOTS = 6
    }
}

/**
 * Turns "I am playing Odette into this enemy team" into a concrete build.
 *
 * Core items come from the hero's archetype; situational items come from what the
 * enemy actually drafted. The situational half is the part that wins games you would
 * otherwise lose, and it is the half most players skip.
 */
class BuildAdvisor(private val db: HeroDatabase) {

    fun build(
        hero: Hero,
        lane: Lane?,
        enemies: List<Hero>,
        allies: List<Hero> = emptyList(),
        confirmedBuildSignals: Set<EnemyBuildSignal> = emptySet(),
    ): HeroBuild {
        val enemyReport = CompReportBuilder.build(Side.ENEMY, enemies)
        val situational = situationalFor(hero, enemies, enemyReport, confirmedBuildSignals)
        val chosenIds = situational.map { it.item.id }.toMutableSet()

        val boots = bootsFor(hero, enemies)
        boots?.let { chosenIds += it.item.id }

        // Hero-specific core first, then the archetype default as padding. Appending the
        // archetype matters: an authored core of four items plus no counter items would
        // otherwise leave the build a slot short of six.
        val authored = db.coreBuild(hero.id)
        val candidates = (authored + coreIds(hero).mapNotNull { db.item(it) })
            .distinctBy { it.id }

        // Not truncated here. [HeroBuild.order] interleaves and trims to six, so keeping
        // every candidate is what guarantees a full build however many counter items the
        // enemy draft called for.
        val core = candidates
            .filter { it.buildableBy(hero) && it.id !in chosenIds }
            .map { BuildItem(it, coreReason(hero, it), BuildSlotKind.CORE) }

        return HeroBuild(
            hero = hero,
            boots = boots,
            core = core,
            situational = situational,
            spells = spellsFor(hero, lane, enemies),
            emblem = emblemFor(hero, enemies),
            notes = notesFor(hero, enemies, enemyReport),
        )
    }

    // --- core ---

    private fun coreIds(hero: Hero): List<String> {
        val magic = hero.damageType == DamageType.MAGIC
        val roles = hero.roles
        return when {
            Role.MARKSMAN in roles && !magic && hero.has(Trait.PERCENT_HP_DAMAGE) -> listOf(
                "demon-hunter-sword", "golden-staff", "corrosion-scythe", "windtalker", "blade-of-despair",
            )

            // Corrosion Scythe rather than Endless Battle: a crit carry with only Windtalker
            // for attack speed does not actually kill anything.
            Role.MARKSMAN in roles && !magic -> listOf(
                "berserkers-fury", "windtalker", "corrosion-scythe", "blade-of-despair", "rose-gold-meteor",
            )

            Role.ASSASSIN in roles && magic -> listOf(
                "starlium-scythe", "genius-wand", "holy-crystal", "concentrated-energy", "divine-glaive",
            )

            Role.ASSASSIN in roles -> listOf(
                "blade-of-the-heptaseas", "hunter-strike", "endless-battle", "blade-of-despair", "queens-wings",
            )

            Role.FIGHTER in roles && magic -> listOf(
                "starlium-scythe", "glowing-wand", "concentrated-energy", "holy-crystal", "divine-glaive",
            )

            Role.FIGHTER in roles -> listOf(
                "haas-claws", "war-axe", "endless-battle", "blade-of-despair", "queens-wings",
            )

            // Bruiser mages (Esmeralda-shaped): they want to survive in the fight, not burst from range.
            Role.MAGE in roles && Role.TANK in roles -> listOf(
                "clock-of-destiny", "oracle", "cursed-helmet", "athenas-shield", "concentrated-energy",
            )

            Role.MAGE in roles && hero.attrs.burst >= 8 -> listOf(
                "clock-of-destiny", "lightning-truncheon", "holy-crystal", "divine-glaive", "blood-wings",
            )

            Role.MAGE in roles -> listOf(
                "enchanted-talisman", "lightning-truncheon", "holy-crystal", "concentrated-energy", "blood-wings",
            )

            Role.TANK in roles -> listOf(
                "dominance-ice", "athenas-shield", "antique-cuirass", "cursed-helmet", "immortality",
            )

            Role.SUPPORT in roles -> listOf(
                "oracle", "fleeting-time", "enchanted-talisman", "athenas-shield", "immortality",
            )

            else -> listOf("endless-battle", "blade-of-despair", "queens-wings")
        }
    }

    private fun coreReason(hero: Hero, item: Item): String = when {
        item.has(ItemTag.CRIT) -> "Core damage for ${hero.name} — crit is where a marksman's scaling comes from."
        item.has(ItemTag.COOLDOWN) && hero.attrs.burst >= 8 ->
            "Core: ${hero.name} lives on rotations, so cooldown reduction is damage."
        item.has(ItemTag.SPELL_VAMP) -> "Core: keeps ${hero.name} in the fight instead of trading and backing off."
        item.has(ItemTag.REVIVE) -> "Core: ${hero.name} will be focused, and a second life is a whole fight."
        else -> item.summary
    }

    // --- boots ---

    private fun bootsFor(hero: Hero, enemies: List<Hero>): BuildItem? {
        val lockdown = enemies.filter {
            it.has(Trait.SUPPRESSION) || it.has(Trait.HOOK) || it.attrs.crowdControl >= 9
        }
        val ccTotal = enemies.sumOf { it.attrs.crowdControl }

        val casterish = hero.damageType == DamageType.MAGIC ||
            Role.MAGE in hero.roles ||
            Role.ASSASSIN in hero.roles

        // Ordered preferences. Several boots are role-restricted, so the first one this
        // hero can actually buy wins — a magic-damage tank must not be sent to Demon Shoes.
        val preferences = buildList {
            if (lockdown.isNotEmpty() || ccTotal >= 32) {
                add(
                    "tough-boots" to
                        "Crowd-control reduction against ${lockdown.joinToString(", ") { it.name }.ifEmpty { "their high-CC draft" }}.",
                )
            }
            if (Role.MARKSMAN in hero.roles && hero.damageType != DamageType.MAGIC) {
                add("swift-boots" to "Attack speed: they have little hard CC, so you can afford damage boots.")
            }
            if (casterish) {
                // An outright burst threat wants penetration; that converts straight into
                // kills and matters more than the mana a weak early game implies.
                if (hero.attrs.burst >= 9) {
                    add("arcane-boots" to "Flat magic penetration — more of ${hero.name}'s burst actually lands.")
                }
                if (hero.attrs.curve.early <= 5) {
                    add("demon-shoes" to "Mana regeneration — ${hero.name} cannot hold a rotation early without it.")
                }
                add("magic-shoes" to "Cooldown reduction — more rotations is more damage for ${hero.name}.")
            }
            if (Role.TANK in hero.roles || Role.SUPPORT in hero.roles) {
                add("rapid-boots" to "Raw movement speed so you can actually be where the fight is.")
            }
            add("warrior-boots" to "Physical defence for the lane you are about to stand in.")
        }

        return preferences.firstNotNullOfOrNull { (id, reason) ->
            db.item(id)?.takeIf { it.buildableBy(hero) }?.let {
                BuildItem(it, reason, BuildSlotKind.BOOTS)
            }
        }
    }

    // --- situational, driven entirely by the enemy draft ---

    private fun situationalFor(
        hero: Hero,
        enemies: List<Hero>,
        enemyReport: CompReport,
        confirmedBuildSignals: Set<EnemyBuildSignal>,
    ): List<BuildItem> {
        if (enemies.isEmpty()) return emptyList()

        val magic = hero.damageType == DamageType.MAGIC
        val squishy = hero.attrs.durability <= 5
        val candidates = mutableListOf<Triple<String, String, Int>>()
        addConfirmedBuildCounters(hero, confirmedBuildSignals, candidates)

        val healers = enemies.filter { it.has(Trait.HEAVY_HEAL) || it.attrs.sustain >= 8 }
        if (healers.isNotEmpty()) {
            val names = healers.joinToString(", ") { it.name }
            val id = when {
                magic -> "necklace-of-durance"
                Role.TANK in hero.roles || Role.SUPPORT in hero.roles -> "dominance-ice"
                else -> "sea-halberd"
            }
            candidates += Triple(id, "$names will out-heal your damage without this. Buy it early, not last.", 5)
        }

        val tanks = enemies.filter { it.attrs.durability >= 8 }
        if (tanks.size >= 2) {
            val names = tanks.joinToString(", ") { it.name }
            val id = when {
                magic -> "divine-glaive"
                Role.MARKSMAN in hero.roles -> "demon-hunter-sword"
                else -> "malefic-roar"
            }
            candidates += Triple(id, "$names are too durable for flat damage — this cuts through them.", 4)
        }

        if (enemyReport.damage.magic >= 0.55) {
            val id = when {
                hero.attrs.durability >= 8 -> "athenas-shield"
                !magic && squishy -> "rose-gold-meteor"
                else -> "radiant-armor"
            }
            candidates += Triple(
                id,
                "${percent(enemyReport.damage.magic)} of their damage is magic.",
                4,
            )
        }

        if (enemyReport.damage.physical >= 0.55) {
            val carry = enemies.firstOrNull {
                (Role.MARKSMAN in it.roles || Role.ASSASSIN in it.roles) && it.attrs.sustainedDamage >= 8
            }
            val id = if (carry != null && hero.attrs.durability >= 7) "blade-armor" else "antique-cuirass"
            val why = carry?.let { "${it.name} is their main physical damage." }
                ?: "${percent(enemyReport.damage.physical)} of their damage is physical."
            candidates += Triple(id, why, 4)
        }

        val bursters = enemies.filter { it.attrs.burst >= 9 }
        if (bursters.isNotEmpty()) {
            val names = bursters.joinToString(", ") { it.name }
            val id = when {
                squishy -> "winter-crown"
                !magic -> "queens-wings"
                else -> "athenas-shield"
            }
            candidates += Triple(id, "$names can delete you in one rotation.", 3)
        }

        val pickers = enemies.filter { it.attrs.pickPotential >= 9 }
        if (pickers.isNotEmpty() && squishy) {
            candidates += Triple(
                "immortality",
                "${pickers.joinToString(", ") { it.name }} will catch you at least once a game.",
                3,
            )
        }

        val enemyMarksmen = enemies.filter { Role.MARKSMAN in it.roles }
        if (enemyMarksmen.isNotEmpty() && hero.attrs.range <= 3 && !magic) {
            candidates += Triple(
                "wind-of-nature",
                "Lets you dive ${enemyMarksmen.joinToString(", ") { it.name }} and survive being focused.",
                3,
            )
        }

        if (enemies.any { it.has(Trait.SHIELD_HEAVY) } && (Role.TANK in hero.roles || Role.SUPPORT in hero.roles)) {
            candidates += Triple("dominance-ice", "Weakens the shields their comp is built on.", 3)
        }

        return candidates
            .sortedByDescending { it.third }
            .mapNotNull { (id, reason, _) -> db.item(id)?.let { it to reason } }
            .filter { (item, _) -> item.buildableBy(hero) }
            .distinctBy { (item, _) -> item.id }
            .take(3)
            .map { (item, reason) -> BuildItem(item, reason, BuildSlotKind.SITUATIONAL) }
    }

    /**
     * A scanned item is stronger evidence than a composition stereotype. These choices
     * deliberately remain role-legal and use stable item IDs; they never infer a build
     * signal from ambiguous icon artwork.
     */
    private fun addConfirmedBuildCounters(
        hero: Hero,
        signals: Set<EnemyBuildSignal>,
        candidates: MutableList<Triple<String, String, Int>>,
    ) {
        if (signals.isEmpty()) return

        fun add(itemId: String, reason: String) {
            candidates += Triple(itemId, "Confirmed enemy build: $reason", 5)
        }

        val magic = hero.damageType == DamageType.MAGIC
        val tankOrSupport = Role.TANK in hero.roles || Role.SUPPORT in hero.roles
        val squishy = hero.attrs.durability <= 5

        if (EnemyBuildSignal.HEALING in signals) {
            when {
                magic -> add("necklace-of-durance", "healing / lifesteal needs an early magic-side anti-heal.")
                tankOrSupport -> add("dominance-ice", "your frontline slot should cut their healing and lifesteal.")
                else -> add("sea-halberd", "their healing will outlast physical damage without anti-heal.")
            }
        }
        if (EnemyBuildSignal.SHIELDS in signals && tankOrSupport) {
            add("dominance-ice", "this frontline answer weakens their confirmed shield investment.")
        }
        if (EnemyBuildSignal.ATTACK_SPEED in signals || EnemyBuildSignal.CRITICAL_DAMAGE in signals) {
            when {
                hero.attrs.durability >= 7 -> add("blade-armor", "it punishes their confirmed repeated basic attacks.")
                !magic -> add("wind-of-nature", "it creates a physical-immunity window against their carry build.")
                else -> add("winter-crown", "use invulnerability to survive the confirmed basic-attack focus window.")
            }
        }
        if (EnemyBuildSignal.PHYSICAL_PENETRATION in signals) {
            add("antique-cuirass", "reduce the ability damage behind their confirmed physical-penetration build.")
        }
        if (EnemyBuildSignal.MAGIC_BURST in signals || EnemyBuildSignal.MAGIC_PENETRATION in signals) {
            when {
                hero.attrs.durability >= 7 -> add("athenas-shield", "its shield covers the confirmed magic burst window.")
                squishy -> add("winter-crown", "it buys time through the confirmed magic burst rotation.")
                else -> add("radiant-armor", "it scales into their confirmed sustained magic damage.")
            }
        }
        if (EnemyBuildSignal.ARMOR in signals) {
            if (magic) add("divine-glaive", "percentage magic penetration answers their confirmed armour / defence stack.")
            else add("malefic-roar", "percentage physical penetration answers their confirmed armour stack.")
        }
        if (EnemyBuildSignal.MAGIC_RESIST in signals && magic) {
            add("divine-glaive", "percentage magic penetration answers their confirmed magic-resistance stack.")
        }
        if (EnemyBuildSignal.HIGH_HEALTH in signals) {
            when {
                Role.MARKSMAN in hero.roles -> add("demon-hunter-sword", "percent-HP damage scales with their confirmed high-health build.")
                magic -> add("glowing-wand", "its percentage-health burn stays valuable into their confirmed high-health build.")
            }
        }
    }

    // --- spells and emblem ---

    private fun spellsFor(hero: Hero, lane: Lane?, enemies: List<Hero>): List<BuildItem> = buildList {
        if (lane == Lane.JUNGLE) {
            db.item("retribution")?.let {
                add(BuildItem(it, "Mandatory in the jungle — it is also how you steal Lord.", BuildSlotKind.SPELL))
            }
        }

        val lockdown = enemies.filter { it.has(Trait.SUPPRESSION) || it.has(Trait.HOOK) }
        when {
            lockdown.isNotEmpty() && hero.attrs.mobility <= 6 -> db.item("purify")?.let {
                add(
                    BuildItem(
                        it,
                        "${lockdown.joinToString(", ") { e -> e.name }} will lock you down and you cannot escape it otherwise.",
                        BuildSlotKind.SPELL,
                    ),
                )
            }

            hero.attrs.mobility <= 4 -> db.item("flicker")?.let {
                add(BuildItem(it, "${hero.name} has no escape in their kit.", BuildSlotKind.SPELL))
            }

            hero.attrs.burst >= 9 -> db.item("execute")?.let {
                add(BuildItem(it, "Converts your near-kills into kills.", BuildSlotKind.SPELL))
            }

            else -> db.item("flicker")?.let {
                add(BuildItem(it, "The safe default for repositioning in and out of fights.", BuildSlotKind.SPELL))
            }
        }
    }.take(2)

    private fun emblemFor(hero: Hero, enemies: List<Hero>): EmblemAdvice {
        val tanky = enemies.count { it.attrs.durability >= 8 } >= 2
        val heavyCc = enemies.sumOf { it.attrs.crowdControl } >= 32
        val magic = hero.damageType == DamageType.MAGIC

        val emblem = when {
            Role.MARKSMAN in hero.roles && !magic -> "Marksman"
            Role.ASSASSIN in hero.roles -> "Assassin"
            Role.MAGE in hero.roles -> "Mage"
            Role.FIGHTER in hero.roles -> "Fighter"
            Role.TANK in hero.roles -> "Tank"
            else -> "Support"
        }

        val (priority, reason) = when {
            tanky && magic -> "magic penetration" to
                "They have two heroes you cannot burst through without penetration."
            tanky -> "physical penetration" to
                "Flat attack is wasted on their frontline; penetration is not."
            heavyCc -> "crowd-control reduction and HP" to
                "Their draft wins by holding you still — shorten that."
            hero.attrs.durability <= 4 -> "HP and defence" to
                "${hero.name} dies to one rotation; survivability converts directly into damage dealt."
            else -> "raw damage" to "Nothing in their draft demands a specific answer from your emblem."
        }
        return EmblemAdvice("$emblem emblem", priority, reason)
    }

    private fun notesFor(hero: Hero, enemies: List<Hero>, enemyReport: CompReport): List<String> = buildList {
        if (enemies.isEmpty()) {
            add("Add the enemy picks to turn this into a real counter-build.")
            return@buildList
        }
        if (enemies.any { it.has(Trait.HEAVY_HEAL) || it.attrs.sustain >= 8 }) {
            add("Anti-heal is the single highest-value purchase in this game. Do not delay it.")
        }
        if (enemyReport.damage.isBalanced) {
            add("Their damage is split between physical and magic, so one resist item will not cover you.")
        }
        if (hero.attrs.durability <= 4 && enemies.any { it.has(Trait.BACKLINE_ACCESS) }) {
            add("They have backline access — position behind your frontline, not beside it.")
        }
        if (enemies.count { it.attrs.range >= 8 } >= 2) {
            add("They out-range you: use terrain and fog, do not walk down the lane into poke.")
        }
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"
}
