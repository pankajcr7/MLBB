# MLBB Enemy Equipment Grid Recognizer

## Purpose

The previous Build scan was permitted to match any OCR text on the red half of the screen. On the supplied Equipment screen, this admitted the battle-spell label **Flicker** from the HUD even though the actual enemy equipment is rendered only as icons. This replacement treats the red 5×6 Equipment grid as the only source for visual item identity and treats ordinary OCR solely as supporting screen/hero evidence.

## Evidence Pipeline

| Stage | Input | Rule | Result |
|---|---|---|---|
| Equipment gate | Full-frame OCR | Require two stable UI markers such as `Equipment`, `Attributes`, or `Sort by Gold`. | No item match runs on a non-Equipment view. |
| Red-grid extraction | Screenshot or captured frame | Crop only the five red rows and six fixed item slots, using normalized landscape coordinates. | HUD, battle spells, player portraits, and blue-side inventory stay out of the recognizer. |
| Visual embedding match | 30 slot crops against bundled non-spell item templates | Compare local image embeddings; retain only the top non-spell catalogue candidate. | Each potential item has a score and runner-up margin. |
| Confidence gate | Best score, margin, and valid icon presence | Accept only scores at or above `0.86` with a margin of at least `0.06`. Empty/dark slots and ties are rejected. | Ambiguous artwork never becomes an item name. |
| Live stability gate | Consecutive successful frame matches | Require the same item in the same slot in two consecutive frame reads. | Eliminates transient capture/compression errors. |
| Advice update | Accepted item IDs only | Convert accepted catalogue items to build signals and recommendations. | Counter-item advice is driven by confirmed items only. |

## Hard Safety Rules

The recognizer uses the app's closed `items.json` catalogue with `ItemCategory.SPELL` removed before template matching. Therefore **Flicker**, **Execute**, **Purify**, and **Retribution** cannot ever appear as recognised equipment. The residual OCR matcher receives only text that lies inside the item-grid bounds, and it also excludes `SPELL` entries. In the supplied screen, the bottom HUD spell line lies outside that bounded grid.

The status text distinguishes outcomes without overstating certainty. A successful result states the number of confirmed items, for example, `Equipment scan complete — 7 items confirmed.` A visible but inconclusive screen reports, `Equipment detected — 0 items passed confidence; confirm traits manually.` The UI displays an item icon only for an accepted catalogue match.

## Shared Sources

The same `EnemyItemGridRecognizer` processes a decoded gallery bitmap and the MediaProjection frame bitmap. This prevents a screenshot import and a live capture from drifting into different rules. The live path additionally applies the temporal confirmation gate; gallery imports apply the stricter one-image score/margin gate.

The app bundles a compact on-device image-embedding model and uses only local item templates. No gameplay image leaves the device during scanning. The model is used for visual similarity only; it does not create item names beyond the closed non-spell catalogue.

## Validation

The supplied 2800×1260 screenshot is used as a regression fixture for the red-side grid. Tests verify that the grid coordinate mapper targets all 30 red slots, that `Flicker` is categorically excluded, that weak/tied visual candidates are rejected, and that live scans do not commit an item until its slot remains stable across two frames.

## References

The Android architecture follows Google’s Image Embedder guidance: it converts images into feature vectors, supports image comparison using cosine similarity, and supports local model assets in Android apps. [1] [2]

[1]: https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder/android "Google AI Edge: Image embedding guide for Android"
[2]: https://developers.google.com/edge/litert/libraries/task_library/image_embedder "Google AI Edge: Integrate image embedders"
