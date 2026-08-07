package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.DamageType
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.model.ItemTag
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Role
import com.pankaj.mlbbdraft.engine.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the authored core builds.
 *
 * Every rule here corresponds to a real defect found in published MLBB build lists:
 * physical and magic items mixed on one hero, roam items on a mid laner, tier-2
 * components listed as finished items, and item names that do not exist at all. A build
 * that cannot pass these is not a build, whoever wrote it.
 */
class BuildValidationTest {
    private val db = DatasetLoader.fromResources()
    private val engine = DraftEngine(db)

    @Test
    fun `core builds exist and the dataset validates`() {
        assertTrue("Expected authored builds, got ${db.coreBuilds.size}", db.coreBuilds.size >= 90)
        assertEquals(db.validate(), emptyList<String>())
    }

    @Test
    fun `every authored item exists`() {
        val unknown = db.coreBuilds.flatMap { (hero, ids) ->
            ids.filter { db.item(it) == null }.map { "$hero -> $it" }
        }
        assertEquals("Builds naming items that do not exist: $unknown", emptyList<String>(), unknown)
    }

    @Test
    fun `no build mixes damage types the hero cannot use`() {
        val illegal = db.coreBuilds.flatMap { (heroId, ids) ->
            val hero = db.hero(heroId) ?: return@flatMap emptyList()
            ids.mapNotNull { db.item(it) }
                .filterNot { it.buildableBy(hero) }
                .map { "${hero.name} (${hero.damageType}) -> ${it.name} (${it.category})" }
        }
        assertEquals("Un-buildable items in authored cores: $illegal", emptyList<String>(), illegal)
    }

    @Test
    fun `no build lists boots, spells or duplicates`() {
        val problems = db.coreBuilds.flatMap { (heroId, ids) ->
            buildList {
                ids.mapNotNull { db.item(it) }
                    .filter { it.category == ItemCategory.MOVEMENT || it.category == ItemCategory.SPELL }
                    .forEach { add("$heroId lists ${it.name}, which is chosen separately") }
                ids.groupBy { it }.filterValues { it.size > 1 }.keys
                    .forEach { add("$heroId lists $it twice") }
            }
        }
        assertEquals(problems.joinToString("; "), emptyList<String>(), problems)
    }

    @Test
    fun `every build is a plausible length`() {
        val wrong = db.coreBuilds.filterValues { it.size !in 4..6 }.map { "${it.key}=${it.value.size}" }
        assertEquals("Builds should be 4-6 core items: $wrong", emptyList<String>(), wrong)
    }

    /** A magic-damage hero must not be sent to buy attack items, and vice versa. */
    @Test
    fun `magic heroes get magic cores and physical heroes get physical cores`() {
        val wrong = db.coreBuilds.flatMap { (heroId, ids) ->
            val hero = db.hero(heroId) ?: return@flatMap emptyList()
            val items = ids.mapNotNull { db.item(it) }
            when (hero.damageType) {
                DamageType.MAGIC -> items.filter { it.category == ItemCategory.ATTACK }
                DamageType.PHYSICAL -> items.filter { it.category == ItemCategory.MAGIC }
                else -> emptyList()
            }.map { "${hero.name} -> ${it.name}" }
        }
        assertEquals("Damage-type mismatches: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `every carry core carries damage`() {
        val carries = db.heroes.filter {
            (Role.MARKSMAN in it.roles || Role.MAGE in it.roles || Role.ASSASSIN in it.roles) &&
                db.coreBuilds.containsKey(it.id)
        }
        val toothless = carries.filter { hero ->
            db.coreBuild(hero.id).none {
                it.has(ItemTag.PHYSICAL_ATTACK) || it.has(ItemTag.MAGIC_POWER) ||
                    it.has(ItemTag.CRIT) || it.has(ItemTag.PERCENT_HP_DAMAGE)
            }
        }
        assertEquals("Carries with no damage in their core: ${toothless.map { it.name }}", emptyList<Any>(), toothless)
    }

    /**
     * The bug this catches: with few or no enemy picks there were no counter items to fill
     * the middle slots, so heroes came out with a five-item build and a visible gap.
     */
    @Test
    fun `every hero gets a full six-slot build at every stage of the draft`() {
        val empty = DraftState.forMode(DraftMode.RANKED)
        val onePick = empty.withPick(Side.ENEMY, 0, Pick("estes", Lane.ROAM))
        val fullEnemy = listOf(
            "estes" to Lane.ROAM,
            "hylos" to Lane.EXP,
            "ling" to Lane.JUNGLE,
            "melissa" to Lane.GOLD,
            "kagura" to Lane.MID,
        ).foldIndexed(empty) { i, state, (id, lane) -> state.withPick(Side.ENEMY, i, Pick(id, lane)) }

        listOf("no enemy picks" to empty, "one enemy pick" to onePick, "full enemy team" to fullEnemy)
            .forEach { (label, state) ->
                val short = db.heroes.mapNotNull { hero ->
                    val build = engine.buildFor(hero, state, hero.lanes.first())
                    if (build.isComplete) null else "${hero.id}=${build.order.size}"
                }
                assertEquals("Short builds with $label: $short", emptyList<String>(), short)
            }
    }

    // --- the three fixes ---

    @Test
    fun `a hero-specific core is preferred over the archetype default`() {
        val state = DraftState.forMode(DraftMode.RANKED)
        val karrie = engine.buildFor(db.require("karrie"), state, Lane.GOLD)
        val ids = karrie.order.map { it.item.id }
        assertTrue(
            "Karrie should get her authored percent-HP core, got $ids",
            "demon-hunter-sword" in ids && "corrosion-scythe" in ids,
        )
    }

    @Test
    fun `the marksman default core carries attack speed`() {
        // Popol has no authored core, so he exercises the archetype default.
        val state = DraftState.forMode(DraftMode.RANKED)
        val fallback = db.heroes.first { Role.MARKSMAN in it.roles && it.id !in db.coreBuilds }
        val build = engine.buildFor(fallback, state, Lane.GOLD)
        assertTrue(
            "${fallback.name}'s default core needs attack speed: ${build.order.map { it.item.id }}",
            build.order.any { it.item.has(ItemTag.ATTACK_SPEED) },
        )
    }

    @Test
    fun `mana-hungry casters are offered Demon Shoes`() {
        val state = DraftState.forMode(DraftMode.RANKED)
        // Cecilion is the archetypal weak-early, mana-hungry mage.
        val build = engine.buildFor(db.require("cecilion"), state, Lane.MID)
        assertEquals("demon-shoes", build.boots?.item?.id)
    }

    @Test
    fun `aggressive burst casters are offered Arcane Boots`() {
        val state = DraftState.forMode(DraftMode.RANKED)
        // Odette: burst 10 and a respectable early game, so penetration over mana.
        val build = engine.buildFor(db.require("odette"), state, Lane.MID)
        assertEquals("arcane-boots", build.boots?.item?.id)
    }

    @Test
    fun `enemy crowd control still overrides the boots choice`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ENEMY, 0, Pick("franco", Lane.ROAM))
            .withPick(Side.ENEMY, 1, Pick("atlas", Lane.EXP))
            .withPick(Side.ENEMY, 2, Pick("kaja", Lane.MID))
        val build = engine.buildFor(db.require("odette"), state, Lane.MID)
        assertEquals("tough-boots", build.boots?.item?.id)
    }

    @Test
    fun `every boots item in the catalog can actually be recommended`() {
        val state = DraftState.forMode(DraftMode.RANKED)
        val recommended = db.heroes.mapNotNull { hero ->
            engine.buildFor(hero, state, hero.lanes.first()).boots?.item?.id
        }.toSet()
        val dead = db.items
            .filter { it.category == ItemCategory.MOVEMENT }
            .map { it.id }
            .filterNot { it in recommended }
        // Tough Boots only appears against a high-CC enemy, which this empty draft has none of.
        assertEquals(
            "Boots no rule can ever suggest: $dead",
            listOf("tough-boots"),
            dead,
        )
    }
}
