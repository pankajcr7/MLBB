# MLBB Draft Helper

An AI draft assistant for Mobile Legends: Bang Bang. It tells you which hero to pick in
each lane against the enemy's picks, what to ban, where your composition is broken, and
what to build against the draft you ended up facing.

**Status: Phase 0 complete.** Manual input, native Android, fully offline.
The screen-reading overlay is Phase 1 — see [`docs/PHASE1_OVERLAY.md`](docs/PHASE1_OVERLAY.md).

---

## Why manual input first

The value of this app is the draft engine and the matchup data, not the screenshot
pipeline. If the suggestions are wrong, automatic detection is worthless — so Phase 0
ships a usable, testable brain and Phase 1 bolts detection onto the exact same engine.

## What works today

| Feature | Detail |
|---|---|
| **Pick suggestions** | Per lane or across all open lanes, ranked, with plain-English reasons that name the enemy hero being countered |
| **Ban suggestions** | Targets meta heroes that specifically beat the heroes *you* play |
| **Draft-order awareness** | Ranked (3 bans, 1-2-2-2-2-1 snake), Tournament (MPL two-phase), Classic. First pick gets penalised for counter-pick exposure; last pick doesn't |
| **Comp health** | Damage split, frontline count, CC / engage / peel / waveclear / sustain meters, early-mid-late power curve, concrete warnings |
| **Item advice** | Anti-heal, armour/magic resist, percent-HP penetration, anti-CC — derived from the enemy comp, with priorities |
| **Threat report** | Top 3 enemy threats with counterplay, plus a tempo read ("force objectives before 10 minutes") |
| **Hero mastery** | Rate heroes 0–5; suggestions weight your comfort and can be restricted to heroes you own |
| **Score breakdown** | Tap any suggestion to see exactly which axis produced the score |

## Build and run

Requires JDK 17 and Android SDK 36. `local.properties` is machine-specific and not committed.

```bash
./gradlew :engine:test          # 26 tests, pure JVM, no emulator needed
./gradlew :app:assembleDebug    # APK at app/build/outputs/apk/debug/app-debug.apk
```

Install on your phone (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
:engine   pure Kotlin/JVM — no Android dependencies
          model/    Hero, HeroAttrs, Trait, DraftState, draft formats
          data/     dataset loading + indexed lookups + integrity validation
          scoring/  counter, synergy, comp-need, exposure scorers; reason builder
          report/   comp report, item advisor, threat report
:app      Android + Jetpack Compose — board, picker, suggestion cards, panels
```

`:engine` is a JVM module on purpose:

- Unit tests run in milliseconds with no emulator, so the draft logic is actually tested.
- The Phase 1 overlay service reuses it unchanged — the overlay is a new *input method*,
  not a new brain.
- It forces the logic to stay free of Android APIs, which keeps it portable.

Everything is deterministic and offline. A 25-second draft timer cannot wait on a
network call, and advice you can't reproduce is advice you can't trust.

## How a suggestion is scored

Seven axes, each normalised to roughly −1..1, weighted and summed
(see `engine/.../scoring/Weights.kt`):

| Axis | What it measures |
|---|---|
| `COUNTER` | Net matchup value against every enemy pick. Mean, leaning 40% toward the single best matchup — hard-countering one key hero beats being mildly fine against five |
| `SYNERGY` | Pairing value with your own picks |
| `COMP_NEED` | How much of what your team is *missing* this hero supplies |
| `META` | Patch tier in that specific lane |
| `MASTERY` | Your comfort rating. With no profile set, falls back to a mild preference for lower-difficulty heroes |
| `EXPOSURE` | **Negative.** The best still-available counter to this hero, scaled by how many picks the enemy has left after yours |
| `LANE_FIT` | Whether the hero is actually played in that lane |

Counter values come from two sources, blended:

1. **Authored edges** — hand-written matchup facts with a note the user sees verbatim.
2. **Trait heuristics** — rules over kit traits and attributes (`PUNISH_DASH` vs
   `DASH_HEAVY`, `PERCENT_HP_DAMAGE` vs high durability, `ANTI_HEAL_KIT` vs
   `HEAVY_HEAL`, …). These cover the ~99% of hero pairs nobody has authored yet, and
   they keep working for heroes released after the edges were written.

Only one direction needs authoring. The engine reads `hero → vs` *and* `vs → hero` and
takes the difference, so "Khufra counters Fanny +0.85" automatically makes Fanny a bad
pick into Khufra.

## The dataset

`engine/src/main/resources/data/` — **75 heroes, 193 counter edges, 65 synergy edges.**

These are expert-authored estimates, not scraped statistics. That is a deliberate
starting point, not a finished dataset: there is no official MLBB API, so the choice is
between hand-authored data you can reason about and scraped win rates you can't
explain. Attributes encode *what a hero does for a team*, which is what drafting turns
on.

### Adding a hero

Append to the right file in `data/heroes/` (split by lane purely so edits stay small):

```json
{
  "id": "kebab-case-slug",
  "name": "Display Name",
  "roles": ["FIGHTER"],
  "lanes": ["EXP", "ROAM"],
  "damageType": "PHYSICAL",
  "traits": ["DASH_HEAVY", "KNOCK_UP"],
  "difficulty": 4,
  "tier": { "EXP": 7.0, "ROAM": 8.0 },
  "attrs": {
    "durability": 7, "burst": 7, "sustainedDamage": 5, "crowdControl": 8,
    "mobility": 8, "waveclear": 4, "engage": 8, "peel": 7, "sustain": 2,
    "objectiveDamage": 4, "pickPotential": 8, "teamfight": 8, "range": 2,
    "curve": { "early": 7, "mid": 7, "late": 6 }
  },
  "notes": "Shown to the user as-is. Write advice, not trivia."
}
```

Rules that matter:

- **`id` is permanent** — matchup edges reference it.
- All `attrs` are 0..10. Treat 5 as average *for that role*: a tank with `burst: 5`
  still does far less damage than a mage with `burst: 5`.
- Every `tier` key must also appear in `lanes` (the validator enforces this).
- Add traits sparingly. A trait should describe something that changes how the hero
  interacts with an *opponent*, not just that they are strong.
- JSON only — no comments or trailing commas.

### Adding a matchup

```json
{ "hero": "khufra", "vs": "fanny", "weight": 0.85,
  "note": "Ball form blocks her cables outright — she cannot fly through him." }
```

`weight` is −1..1. Write the note as advice the user can verify; it is shown verbatim
and it is the reason they will trust or ignore the app.

### Validation

`DatasetIntegrityTest` fails the build on duplicate ids, edges pointing at heroes that
don't exist, self-edges, out-of-range values and tiers for lanes a hero doesn't play. A
typo in a hero id becomes a red test rather than a hero that silently has no counters.

## Roadmap

**Phase 1 — overlay + detection.** Foreground service, `MediaProjection` screen capture
at 1 fps, perceptual-hash matching of portrait crops against a local hero-hash table
(no OCR, no bundled artwork). Full design in
[`docs/PHASE1_OVERLAY.md`](docs/PHASE1_OVERLAY.md).

**Phase 2 — depth.**
- Text-to-speech for the top pick (you cannot read a panel under a draft timer)
- Emblem and battle-spell advice alongside items
- Draft simulator / trainer mode for practising drafts offline
- Result logging, then per-user weight tuning from outcomes
- Shareable draft-analysis cards
- Pre-generated LLM explanation text cached into the dataset at build time — never
  called at draft time, so the app stays instant, offline and free to run

## A note on Moonton's terms

A passive overlay that only reads pixels is not a mod and does not touch the game
client, but Moonton's terms restrict third-party software interacting with the game and
there is precedent for action against overlay tools. Phase 1 should ship with an in-app
disclaimer.

This is also a design argument, not just a legal one: the app is built to stay fully
useful *outside* the game — manual input, comp analysis, build advice, draft trainer. If
detection ever breaks or becomes unwise to ship, the product still works.

No MLBB artwork or game assets are bundled. Hero identification in Phase 1 uses
perceptual hashes, not stored images.
