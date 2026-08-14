# Current MLBB recommendation snapshot — 14 August 2026

This release packages a **dated current-meta signal** retrieved on 14 August 2026. It is a bounded ranking input, not a promise that a hero will win every draft. The pick scorer continues to prioritize lane eligibility, confirmed enemy counters, allied composition, and player comfort.

| Input | App treatment | Safety boundary |
|---|---|---|
| Moonton ranked hero data, refreshed on 13 August 2026 | Blended win, pick, and ban-rate signal for recognised existing heroes | Cannot create an unreviewed hero, role, lane, counter edge, or item category. |
| Current editorial and video guides | Corroborates composition rules such as anti-dash, anti-engage, shield, and sustain responses | No source is used as a stand-alone universal tier list. |
| Ceplin03 structured MLBB catalogue snapshot | Compatible hero/item aliases, source identifiers, and item prices | No artwork, source code, new equipment identity, counter tag, or battle spell is imported. |

The original source snapshot identifies several high-win-rate heroes, including Rafaela, Masha, Melissa, Gloo, Khufra, Lolita, and Atlas. The app uses this only as an additive, time-stamped signal after resolving the names to its authored hero IDs. It leaves an unknown hero out of scoring until that hero has an explicit validated catalogue entry and authored gameplay model.[1]

> **Recommendation rule:** confirmed enemy composition and confirmed enemy equipment are stronger evidence than a generic global tier. A scanned healing, armour, magic-burst, penetration, attack-speed, or high-health build changes the relevant recommended hero build and states the reason in the purchase card.

The timed snapshot is intentionally conservative. It can recommend established anti-dash, anti-engage, shield, sustain, and resistance answers only when existing authored hero and item semantics support them. This avoids the failure mode where a fast-changing web tier list silently assigns an incorrect counter or turns a battle spell into equipment.[1] [2] [3]

## References

[1]: https://www.mobilelegends.com/rank "Mobile Legends: Bang Bang ranked hero data"
[2]: https://esports.gg/guides/mobile-legends-bang-bang/mobile-legends-hero-tier-list/ "esports.gg — Mobile Legends hero tier list, August 2026"
[3]: https://www.youtube.com/watch?v=o6gicTkzv3c "YouTube — August 2026 MLBB tier guide"
[4]: https://github.com/Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang "Ceplin03 structured MLBB catalogue"

The referenced catalogue was reviewed on 14 August 2026 at commit `29f98d037a70a0ce03908562c5be9cab333eedff`. It has no declared licence; this project uses only the allow-listed structured metadata described above and does not redistribute the repository’s artwork.[4]
