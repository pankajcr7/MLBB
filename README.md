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
| **Per-hero counter-builds** | Pick Odette on your side and the Build tab becomes *Odette's* build against *their* draft: boots, core, and the situational items their picks force, in purchase order, with real item icons |
| **Win probability** | An explainable draft-advantage estimate with the factors behind it, updating live as picks come in |
| **Live patch data** | Hero tiers refresh from a published stats feed, layered over the bundled dataset; fully usable offline |
| **Threat report** | Top 3 enemy threats with counterplay, plus a tempo read ("force objectives before 10 minutes") |
| **Hero mastery** | Rate heroes 0–5; suggestions weight your comfort and can be restricted to heroes you own |
| **Score breakdown** | Tap any suggestion to see exactly which axis produced the score |

## Build and run

Requires JDK 17 and Android SDK 36. `local.properties` is machine-specific and not committed.

```bash
./gradlew :engine:test          # 58 tests, pure JVM, no emulator needed
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk    (~17 MB)
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk (~11 MB)
```

Install on your phone (Android 8.0+, USB debugging on):

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

**If Android says "package appears to be invalid":** you installed an *unsigned* APK.
`gradlew build` used to emit `app-release-unsigned.apk`, which Android refuses. Release
signing is now configured via `keystore.properties` + `release.keystore` (both
gitignored), so `assembleRelease` produces a signed `app-release.apk`. Verify any APK
before installing:

```bash
$ANDROID_HOME/build-tools/36.1.0/apksigner verify --verbose app-release.apk
# must print: Verified using v2 scheme (APK Signature Scheme v2): true
```

Copying the APK through a chat app can also corrupt it — prefer `adb install`, or check
the SHA-256 matches on both ends.

## Architecture

```
:engine   pure Kotlin/JVM — no Android dependencies
          model/    Hero, HeroAttrs, Trait, Item, DraftState, draft formats
          data/     dataset loading + indexed lookups + integrity validation
          scoring/  counter, synergy, comp-need, exposure scorers; reason builder
          report/   comp report, build advisor, threat report, win probability
          meta/     live overlay: fetch, parse, tier derivation, safe merge
:app      Android + Jetpack Compose — board, picker, suggestions, builds, panels
          data/     profile + meta sync repositories (disk cache)
tools/    build_meta.py — maps any JSON stats source into the overlay schema
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

## Builds and win probability

### Per-hero counter-builds

`BuildAdvisor` splits a build in two:

- **Core** comes from the hero's archetype — role plus damage type plus a couple of
  traits. A percent-HP marksman gets a different spine to a crit marksman; a bruiser
  mage gets a different spine to a burst mage.
- **Situational** comes entirely from what the enemy drafted, and is inserted *early* in
  the purchase order. An anti-heal bought sixth is an anti-heal bought too late.

Every situational pick names the enemy hero that caused it, so you can disagree with it
on the spot. Items are filtered by `Item.buildableBy(hero)`, so a mage is never told to
buy Berserker's Fury — there is a test asserting that across all 76 heroes.

Emblem advice is deliberately given as an **attribute priority** ("prioritise magic
penetration"), not a named talent. Talent trees get reshuffled most major patches;
attribute priorities survive that, and a confidently wrong talent name is worse than no
talent name.

### Win probability

Four weighted factors — matchups (1.0), team composition (0.9), patch strength (0.5),
your mastery (0.4) — averaged into a −1..1 advantage, then pushed through a logistic
curve and **clamped to 22–78%**.

The clamp is the honest part. This measures who ten hero picks favour, which is a real
signal but a small one next to mechanics and rotations. A tool that says 95% is lying,
so this one structurally cannot. Every reading ships with its contributing factors, a
confidence level based on how many picks are known, and that caveat in the UI.

## Live patch data

Hero tiers go stale every patch, so they are not permanently baked in. The app layers a
downloaded **meta overlay** over the bundled dataset.

### Why it does not call a stats API directly

There is no official public MLBB stats API, and every community API I checked is either
undocumented or has already gone offline once — `openmlbb.fastapicloud.dev` currently
returns empty 404s on every path. Depending on one directly means the app breaks in the
middle of a draft when someone else's free tier expires.

So the data flow is:

```
stats source ──▶ tools/build_meta.py (GitHub Actions, daily) ──▶ data/meta.json in your repo
                                                                          │
                                            app fetches one raw URL you control
```

A source outage now breaks a scheduled job you can fix whenever, instead of the app.

### What the overlay may and may not do

It is deliberately **additive**:

| | |
|---|---|
| Can | move meta tiers, add matchup edges for pairs you have not authored |
| Cannot | delete a hero, delete an item, overwrite an authored counter note |

Consequences: a bad feed can make the advice *worse*, but it can never make the app
broken or empty. And a feed that resolves fewer than 20 known heroes is **rejected
outright** — the wrong field name, an HTML error page or a truncated download leaves
yesterday's cache in charge rather than half-applying garbage.

Derived tiers are **blended 65/35 with the seed**, not substituted for it. Public win
rates are noisy and rank-dependent: 51% on a hero nobody picks says very little. Blending
keeps hand-authored knowledge in the loop and stops one bad scrape rewriting the whole
tier list. Win rate carries the signal, ban rate is the best available proxy for "the
playerbase thinks this hero is a problem" (exactly what a draft tool cares about), and
pick rate is capped as weak popularity noise.

Hero names are matched by stripping everything except letters and digits, so a source can
say `Yi Sun-shin`, `X.Borg` or `Luo Yi` and it resolves without anyone editing a mapping.

### Configuring it

`INTERNET` is the app's only permission, and everything works with it denied. Set the
feed URL in the app under the person icon → *Live meta data*, or edit
`MetaRepository.DEFAULT_FEED_URL`. The title bar shows freshness (`Live: 2026.08.06 ·
3h ago`, or `Bundled data only`) and the sync icon forces a refresh. Syncs are
`ETag`-conditional, so a no-change check costs one 304.

To wire up a source, edit `tools/meta_sources.json` — the mapping is data, not code:

```json
{
  "url": "https://example.com/api/hero-rank?days=7",
  "records_path": "data.records",
  "fields": { "name": "hero.data.name", "winRate": "main_hero_win_rate" }
}
```

Then test it offline before letting CI near it:

```bash
curl -s '<url>' > raw.json
python tools/build_meta.py --config tools/meta_sources.json --input raw.json --out data/meta.json
```

**The shipped mapping is a placeholder with a deliberately invalid URL.** I could not
reach any live MLBB stats host from the machine this was built on, so nothing about a
specific source's response shape is verified. Candidates, best first:

1. `www.mobilelegends.com/rank` — official, updated daily. Find the XHR the page makes.
2. [`ridwaanhall/api-mobilelegends`](https://github.com/ridwaanhall/api-mobilelegends) —
   open source. **Self-host it** and the dead-endpoint problem never recurs.
3. `mlbbhub.com/statistics`, `mlbb.io/en/hero-statistics` — third-party trackers.

`MetaOverlayTest` verifies the engine against a fixture that is verbatim
`build_meta.py` output, so the script and the schema cannot drift apart silently.

## The dataset

`engine/src/main/resources/data/` — **124 of 132 heroes, 315 counter edges, 121 synergy
edges, 58 items.**

The eight absent heroes (Kalea, Lukas, Marcel, Obsidia, Sora, Suyou, Zetian, Zhuxin) are
missing on purpose: I could not describe their kits accurately enough to author
attributes, and invented attributes are worse than a missing hero — the engine would
recommend them confidently for the wrong reasons. `DatasetIntegrityTest` tracks that list
and fails if it goes stale.

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

### Adding an item

`data/items.json`, plus a matching icon at `app/src/main/assets/items/<id>.webp`:

```json
{ "id": "sea-halberd", "name": "Sea Halberd", "category": "ATTACK", "cost": 2020,
  "tags": ["PHYSICAL_ATTACK", "ATTACK_SPEED", "ANTI_HEAL"],
  "summary": "Cuts the healing on whoever you hit. The physical side's anti-heal." }
```

Items carry **tags, not stat numbers**. The recommendation "buy anti-heal against Estes"
depends on the item cutting healing, not on whether it gives 60 or 65 attack — and stat
numbers change every patch while `ANTI_HEAL` does not. Costs are indicative for build
order, not patch-exact.

Icons were fetched from the MLBB community wiki via its MediaWiki API and downscaled to
96px WebP (~9 KB each, 484 KB total). A test asserts every item id has a bundled icon,
and the UI falls back to a monogram tile rather than a blank square if one is ever
missing.

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

**On the bundled item icons:** the app ships 58 item icons sourced from the community
wiki. They are Moonton's artwork. That is normal for a fan companion app and they are
purely informational here, but it is worth knowing before any commercial release or
store submission — check first, or replace them with your own drawings.

No hero portraits are bundled. Hero identification in Phase 1 uses perceptual hashes,
not stored images.
