# MLBB Equipment Recognition Research

## Findings

The user-supplied Equipment scoreboard has a fixed red-side enemy layout: five player rows, with up to six item slots per row. Item names do not appear beside the grid, so general full-screen OCR can read unrelated HUD labels such as the battle spell `Flicker`. A recognizer must therefore use only the constrained right-side item-grid regions; spell/HUD text must never be considered item evidence.

The app must retain its evidence rule: a name may be claimed only when it is produced by a dedicated item-grid recognizer at or above its calibrated confidence threshold. Otherwise the UI should state that the screen was detected but item identities require manual confirmation.

## External references

| Source | Useful evidence | URL |
| --- | --- | --- |
| MLBB-API | MIT-licensed structured public item dataset; repository documents `v1/item-meta-final.json` and notes the data is maintained to match in-game items. | https://github.com/p3hndrx/MLBB-API |
| MLBB Public Data API | Public API/repository with visual assets and MLBB references; BSD-3-Clause license and attribution requirement stated in README. | https://github.com/ridwaanhall/api-mobilelegends |
| MLBB.io Items | Current-name cross-check for major items used by the assistant, including Antique Cuirass, Athena's Shield, Dominance Ice, Divine Glaive, Malefic Roar, Sea Halberd, and others. | https://mlbb.io/en/items |
| Mobile Legends Wiki Equipment | Documents that a hero can have up to six equipment pieces and lists current item names by class. | https://mobile-legends.fandom.com/wiki/Equipment |

## Implementation direction

Use a local, deterministic red-side grid detector and a bundled item-template catalogue. For every candidate slot, compare only the cropped icon against known item templates after resizing and color/brightness normalization. Require a clear winner margin in addition to an absolute match threshold. Aggregate stable winners across multiple live frames before accepting them. The same `EquipmentItemGridRecognizer` should be invoked for user-picked screenshots and MediaProjection frames; full-frame OCR remains available only for draft heroes and screen/header detection, never item names.

The user-provided screenshot is the first regression fixture. Its red-side rows are centered around y ≈ 439, 596, 752, 908, and 1064 in a 2800 × 1260 frame; the six right-side item columns are centered around x ≈ 1463, 1548, 1633, 1718, 1803, and 1888, with icon squares of approximately 68 pixels. These measurements must be expressed as normalized proportions for screen-share frames with different resolutions.

## Live icon-reference check

The live `https://mlbb.io/en/items` page renders current item thumbnails through paths in the form `https://mlbb.io/_next/image?url=%2Fimages%2Fitems%2F<Item%20Name>.png&w=128&q=75`. The item-card content paired each thumbnail with a display name, for example `Antique Cuirass`, `Arcane Boots`, `Berserker's Fury`, `Blade of Despair`, `Dominance Ice`, `Divine Glaive`, and `Malefic Roar`. This makes the site a usable web reference to refresh the local template bank. The production app will not depend on this website at scan time; it will bundle only local, versioned equipment templates.

The public `p3hndrx/MLBB-API` dataset additionally exposes `item_name`, `id`, `icon`, `item_tier`, and `item_category` fields. Its item category data provides a separate safety check for excluding battle spells such as `Flicker` from any equipment result.

## DOM verification

A live DOM inspection found 92 images on the item-list page. Current item cards expose a direct, named image URL and a matching `alt` item name; for example, the `Antique Cuirass` card uses `https://mlbb.io/_next/image?url=%2Fimages%2Fitems%2FAntique%20Cuirass.png&w=128&q=75`. The markup has a 60×60 thumbnail and `rounded-xl` styling, confirming the underlying referenced PNG can be collected independently of the site card UI.

## Current template export

The live DOM export successfully returned the name-to-image mapping for the current items (for example Antique Cuirass, Arcane Boots, Athena's Shield, Berserker's Fury, Blade Armor, Blade of Despair, Dominance Ice, Demon Hunter Sword, Divine Glaive, and many more). This confirms a current, item-name-labelled visual template bank can be produced. The implementation will map only template names that are present in the app’s own `items.json` catalogue; it will exclude battle spells by category and will never call the web source during a user scan.

## Catalogue coverage check (2026-08-12)

The live item page exposes a broader named icon catalogue than the app’s original 49-item list. It includes full equipment as well as lower-tier components such as Ares Belt, Azure Blade, Dagger, and Dreadnaught Armor. The recognizer must therefore match only the versioned, bundled full-equipment catalogue and reject components until their counter-signal meaning is explicitly authored. Offline calibration also showed that the compact MobileNet embedder alone is not sufficiently discriminative for exact item identity; it may be used only as one signal behind a stricter version-aligned template and winner-margin gate.

## Canonical wiki artwork and real-grid evidence

The MLBB Wiki exposes named current item art through its public static CDN. The War Axe page identifies its current asset as `https://static.wikia.nocookie.net/mobile-legends/images/7/70/War_Axe.png/revision/latest?cb=20260422060516` and documents a separate 2021–2024 historical icon. The Magic Shoes page identifies its current asset as `https://static.wikia.nocookie.net/mobile-legends/images/5/5a/Magic_Shoes.png/revision/latest?cb=20240110072432` and also records a legacy artwork variant. Source pages: [War Axe](https://mobile-legends.fandom.com/wiki/War_Axe) and [Magic Shoes](https://mobile-legends.fandom.com/wiki/Magic_Shoes).

The public wiki CDN uses predictable MD5-derived paths for file names, which allowed the calibration tool to retrieve canonical images for all 54 locally authored items. Against the supplied Equipment screenshot, the canonical references produced a clear, well-separated result for Ruby row 1, slot 1 (`Magic Shoes`: combined visual/feature score 0.4676 versus runner-up 0.2754) and Ruby row 1, slot 2 (`War Axe`: 0.2372 versus 0.2152). The broad score range confirms the recognizer must keep conservative evidence thresholds and use stability checks in live screen sharing rather than turn all icon crops into automatic names.

The live `mlbb.io` item page exposes 89 named images, including components, but current generic web thumbnails are not sufficiently version-aligned for unqualified acceptance. Canonical MLBB wiki artwork should therefore be retained as a supplementary reference bank, while unrelated HUD text and battle spells remain categorically excluded from Equipment evidence.

Sources were consulted on 2026-08-12. Media copyright/licensing remains owned by its respective holders; downloaded images are retained only for internal calibration until a production asset decision is made.

## Independent-signal consensus on the supplied screenshot

Using the exact Android MobileNet embedder against 54 canonical MLBB wiki templates and a separate color/feature comparator, only Ruby slots 1 and 2 produced cross-method agreement: `Magic Shoes` (embedder 0.692, margin 0.077; visual 0.468, margin 0.192) and `War Axe` (0.739, margin 0.059; visual 0.237, margin 0.022). Most remaining slots disagreed across methods, demonstrating that a model-score-only policy would create false positives. The app should report only independently confirmed items, mark the remaining occupied slots as unconfirmed, and never use spell/HUD text as an item fallback. This finding supersedes any plan to reduce the acceptance threshold merely to increase the scan count.
