#!/usr/bin/env node
/**
 * Adds the dated first-party MLBB ranked signal retrieved on 2026-08-14 to both
 * the app-bundled and controlled remote feeds. Existing authored lane tiers stay
 * in place; the appended records are applied last, so their live win/pick/ban
 * rates become a bounded, explainable current-meta overlay.
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(new URL('..', import.meta.url).pathname);
const SNAPSHOT = 'moonton-rank-2026-08-13';
const PATCH = '2026-08-14-ranked-snapshot';
const UPDATED_AT = '2026-08-14T00:00:00Z';
const SOURCE = 'Moonton MLBB ranked hero page (retrieved 2026-08-14; page updated 2026-08-13 14:25 UTC) | catalogue: Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang@29f98d037a70';

// Only first-party values observed on the dated ranked page. These rates are
// metadata signals, not direct counter claims or universal pick guarantees.
const heroes = [
  ['Marcel', 58.66, 0.27, 31.38],
  ['Rafaela', 57.51, 0.91, 8.99],
  ['Masha', 57.46, 0.11, 0.34],
  ['Melissa', 55.59, 1.33, 6.50],
  ['Gloo', 55.10, 0.62, 50.33],
  ['Khufra', 54.73, 0.34, 2.45],
  ['Lolita', 54.57, 0.09, 0.37],
  ['Argus', 54.57, 0.33, 0.85],
  ['Minotaur', 54.46, 0.80, 2.45],
  ['Floryn', 53.98, 1.06, 20.20],
  ['Hanzo', 53.94, 0.54, 5.75],
  ['Atlas', 53.53, 1.08, 23.17],
  ['Sun', 53.26, 1.57, 42.98],
  ['Miya', 53.21, 3.47, 16.68],
  ['Belerick', 53.15, 1.56, 58.31],
  ['Hanabi', 53.13, 3.40, 11.12],
  ['Diggie', 52.99, 0.22, 5.46],
  ['Lukas', 52.87, 0.51, 3.59],
  ['Ling', 52.83, 1.00, 4.88],
  ['Barats', 52.80, 0.55, 2.04],
].map(([name, winRate, pickRate, banRate]) => ({ name, winRate, pickRate, banRate, sourceSnapshot: SNAPSHOT }));

const targets = [
  'data/meta.json',
  'app/src/main/assets/meta/verified-catalogue.json',
];

for (const relative of targets) {
  const target = path.join(root, relative);
  const feed = JSON.parse(fs.readFileSync(target, 'utf8'));
  feed.patch = PATCH;
  feed.updatedAt = UPDATED_AT;
  feed.source = SOURCE;
  feed.heroes = (feed.heroes ?? []).filter((hero) => hero.sourceSnapshot !== SNAPSHOT);
  feed.heroes.push(...heroes);
  fs.writeFileSync(target, `${JSON.stringify(feed, null, 2)}\n`);
  console.log(`${relative}: ${heroes.length} official ranked records embedded; ${feed.heroes.length} total meta records`);
}
