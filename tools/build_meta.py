#!/usr/bin/env python3
"""Build `data/meta.json` from a live MLBB statistics source.

Why this exists
---------------
There is no official public MLBB stats API, and every community API I could find is
either undocumented or has already gone offline once. So the app does not talk to any of
them directly. Instead:

    stats source  ->  this script (in CI, on a schedule)  ->  data/meta.json in your repo
                                                                      |
                                                        app fetches one stable raw URL

That way a source going down breaks a scheduled job you can fix at your leisure, instead
of breaking the app in the middle of a draft.

The mapping is data, not code
-----------------------------
Sources disagree about field names (`win_rate` vs `winRate` vs `wr`) and about where the
list lives in the response. `tools/meta_sources.json` describes that shape, so pointing
at a new source is a config edit, not a code change:

    {
      "name": "example",
      "url": "https://example.com/api/hero-rank?days=7",
      "records_path": "data.records",        # dotted path to the list ([] = top level)
      "fields": {
        "name": "hero.data.name",            # dotted paths, relative to each record
        "winRate": "main_hero_win_rate",
        "pickRate": "main_hero_appearance_rate",
        "banRate": "main_hero_ban_rate"
      }
    }

Usage
-----
    python tools/build_meta.py --config tools/meta_sources.json --out data/meta.json
    python tools/build_meta.py --config tools/meta_sources.json --out data/meta.json \\
        --input fixture.json          # offline: map a saved response, no network

Exit codes: 0 wrote a file, 2 the source was unusable (CI should not commit).
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any

USER_AGENT = "MlbbDraftHelper-meta-builder/1.0"

# The app rejects a feed that resolves fewer heroes than this, so there is no point
# publishing one. Keep in sync with MetaApplyReport.MIN_MATCHED.
MIN_HEROES = 20


def dig(value: Any, path: str) -> Any:
    """Follow a dotted path. Empty path returns the value unchanged."""
    if not path:
        return value
    for part in path.split("."):
        if isinstance(value, list):
            if not part.isdigit():
                return None
            index = int(part)
            value = value[index] if index < len(value) else None
        elif isinstance(value, dict):
            value = value.get(part)
        else:
            return None
        if value is None:
            return None
    return value


def as_number(value: Any) -> float | None:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        cleaned = value.strip().rstrip("%")
        try:
            return float(cleaned)
        except ValueError:
            return None
    return None


def fetch(url: str, timeout: int = 30) -> Any:
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def build(payload: Any, config: dict) -> list[dict]:
    records = dig(payload, config.get("records_path", ""))
    if not isinstance(records, list):
        raise ValueError(
            f"records_path {config.get('records_path')!r} did not point at a list "
            f"(got {type(records).__name__})"
        )

    fields = config["fields"]
    heroes: list[dict] = []
    for record in records:
        name = dig(record, fields["name"])
        if not isinstance(name, str) or not name.strip():
            continue
        entry: dict[str, Any] = {"name": name.strip()}
        for key in ("winRate", "pickRate", "banRate", "tier"):
            path = fields.get(key)
            if not path:
                continue
            number = as_number(dig(record, path))
            if number is not None:
                entry[key] = round(number, 4)
        # A record with no numbers tells the app nothing; drop it rather than ship it.
        if len(entry) > 1:
            heroes.append(entry)
    return heroes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, help="Source mapping JSON.")
    parser.add_argument("--out", required=True, help="Where to write meta.json.")
    parser.add_argument("--input", help="Read a saved response instead of fetching.")
    parser.add_argument("--patch", help="Patch label. Defaults to today's date.")
    args = parser.parse_args()

    with open(args.config, encoding="utf-8") as handle:
        config = json.load(handle)

    if args.input:
        with open(args.input, encoding="utf-8") as handle:
            payload = json.load(handle)
    else:
        try:
            payload = fetch(config["url"])
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as error:
            print(f"error: could not reach {config['url']}: {error}", file=sys.stderr)
            return 2
        except json.JSONDecodeError as error:
            print(f"error: {config['url']} did not return JSON: {error}", file=sys.stderr)
            return 2

    try:
        heroes = build(payload, config)
    except (KeyError, ValueError) as error:
        print(f"error: mapping does not fit the response: {error}", file=sys.stderr)
        return 2

    if len(heroes) < MIN_HEROES:
        print(
            f"error: only mapped {len(heroes)} heroes, need at least {MIN_HEROES}. "
            "The source shape probably changed — fix the mapping before committing.",
            file=sys.stderr,
        )
        return 2

    now = datetime.now(timezone.utc)
    overlay = {
        "patch": args.patch or now.strftime("%Y.%m.%d"),
        "updatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": config.get("name", config.get("url", "unknown")),
        "heroes": heroes,
    }

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(overlay, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    print(f"wrote {args.out}: {len(heroes)} heroes, patch {overlay['patch']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
