# Automatic MLBB Data Refresh

## Purpose

The app publishes and consumes one controlled JSON feed at `data/meta.json`. The Android client refreshes it at most every twelve hours when a network is available and retains both a validated on-device cache and the bundled catalogue. A missing, delayed, malformed, or incomplete remote file therefore cannot prevent drafting, screen reading, build scanning, or counter recommendations.

The scheduled publisher uses the data records from [`Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang`](https://github.com/Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang) as an **input**, not as a client-side dependency. It is triggered daily and can also be run manually from the repository’s Actions page. GitHub documents scheduled workflow events as best-effort, so the app never assumes that a particular refresh time is guaranteed.[1]

| Feed field | Accepted from the source | Used by the app | Explicitly never accepted |
|---|---|---|---|
| Hero data | Stable source ID, display name, role labels | Maps known display-name aliases for OCR | New playable heroes, lanes, attributes, draft traits, tiers, images |
| Equipment data | Stable source ID, display name, gold price | Updates a known non-spell item’s display-name alias and price | New item identities, categories, tags, counter logic, build recipes, images |
| Live meta data | Separately configured win/pick/ban rates | Blends tier data over authored tiers | Deletes heroes or overwrites authored counter notes |

## Fail-closed validation

The publishing script verifies upstream JSON type, unique IDs, printable names, an immutable upstream Git commit marker, and minimum source counts. The Android engine maps those records only to entities already authored in the bundled catalogue. A catalogue-only feed applies only when it resolves at least **100 existing heroes** and **45 existing non-spell equipment records**. Source-only entries remain diagnostics; they cannot create an incomplete hero or item in the user’s draft.

This limit deliberately reflects the current source-versus-app comparison: the source exposes 133 hero records and 109 equipment records; the app has 132 authored heroes and 54 items, including 4 battle spells. Only 46 equipment names overlap exactly, with `Magic Boots` and `Demon Boots` handled as explicit reviewed aliases. All fields that could alter recommendation semantics remain bundled and versioned with the app.

> **Recognition safety rule:** Battle spells, including Flicker, Execute, Purify, Retribution, and any source-only spell-like record, cannot enter the Equipment recognizer or OCR matcher through the remote feed. Visual template identities remain locally bundled and are never downloaded from this source.

## Operation

The `.github/workflows/meta.yml` workflow fetches the source repository, runs `tools/build_catalogue_overlay.mjs`, verifies the transformed feed contains no artwork fields, and commits `data/meta.json` only if its contents changed. If optional live-statistics configuration is disabled or fails, the job preserves its last validated tier data while still refreshing the constrained catalogue snapshot.

The app schedules a unique network-constrained background refresh via WorkManager. It applies only a cached overlay that passes the same engine validation. The setting screen displays the applied patch and catalogue match counts, so a user can distinguish a refreshed feed from the bundled fallback.

## Source and licence note

The selected repository did not declare a licence during integration. The workflow therefore excludes its images and does not redistribute artwork. Before widening the feed beyond the constrained factual identifiers, display names, roles, and prices described above, confirm the source’s permission or licence terms.

## References

[1]: https://docs.github.com/actions/using-workflows/events-that-trigger-workflows "GitHub Docs — Events that trigger workflows"
