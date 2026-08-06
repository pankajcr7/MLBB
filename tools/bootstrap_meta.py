#!/usr/bin/env python3
"""Generate a bootstrap `data/meta.json` from the bundled dataset's own tiers.

Why you'd want this
-------------------
Before a real stats source is wired up, the feed URL 404s and you cannot tell the
difference between "nothing published yet" and "my URL is wrong / phone is offline".
This produces a valid feed containing the tiers already in the app, so:

  * the URL resolves, and the app's status line proves the network path works;
  * you get a correctly-shaped file to diff against once real data flows.

It deliberately carries no new information. The patch label says so, so the app never
claims to have live data when it does not. Once `tools/build_meta.py` runs against a
real source it overwrites this file and the label changes.

Usage:
    python tools/bootstrap_meta.py            # writes data/meta.json
"""

from __future__ import annotations

import glob
import json
import os
from datetime import datetime, timezone

HEROES_GLOB = "engine/src/main/resources/data/heroes/*.json"
OUT = "data/meta.json"


def main() -> int:
    files = sorted(glob.glob(HEROES_GLOB))
    if not files:
        raise SystemExit(f"No hero files matched {HEROES_GLOB} — run this from the repo root.")

    entries: list[dict] = []
    for path in files:
        with open(path, encoding="utf-8") as handle:
            for hero in json.load(handle)["heroes"]:
                # One entry per lane, because a tier is only meaningful per lane.
                for lane, tier in hero.get("tier", {}).items():
                    entries.append({"name": hero["name"], "lane": lane, "tier": tier})

    now = datetime.now(timezone.utc)
    overlay = {
        "patch": "bootstrap-no-live-source",
        "updatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "tools/bootstrap_meta.py (bundled tiers, not live statistics)",
        "heroes": entries,
    }

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(overlay, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    heroes = len({e["name"] for e in entries})
    print(f"wrote {OUT}: {heroes} heroes, {len(entries)} lane entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
