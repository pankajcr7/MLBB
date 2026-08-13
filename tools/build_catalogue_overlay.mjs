#!/usr/bin/env node
/**
 * Builds the constrained `catalogue` part of data/meta.json from
 * Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang.
 *
 * The app consumes only source ids, display names, role labels, and equipment gold prices.
 * Image paths, build recipes, item categories, counter tags, spell rules, and unrecognised
 * source fields are deliberately excluded.  This script never executes upstream code.
 *
 * Usage:
 *   node tools/build_catalogue_overlay.mjs \
 *     --base data/meta.json --heroes /tmp/upstream/hero.json \
 *     --equipment /tmp/upstream/equipment.json --upstream-commit <git-sha> --out data/meta.json
 */

import fs from 'node:fs';
import path from 'node:path';

const MIN_HEROES = 100;
const MIN_EQUIPMENT = 45;
const SHA = /^[0-9a-f]{7,128}$/i;

function fail(message) {
  process.stderr.write(`error: ${message}\n`);
  process.exit(2);
}

function readJson(file) {
  try {
    const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
    return JSON.parse(text);
  } catch (error) {
    fail(`could not read JSON ${file}: ${error.message}`);
  }
}

function argument(name) {
  const index = process.argv.indexOf(name);
  if (index === -1 || !process.argv[index + 1]) fail(`missing ${name}`);
  return process.argv[index + 1];
}

function cleanText(value, field) {
  if (typeof value !== 'string') fail(`${field} must be a string`);
  const cleaned = value.trim();
  if (cleaned.length < 2 || cleaned.length > 100 || /[\u0000-\u001F\u007F]/.test(cleaned)) {
    fail(`${field} is not a safe display label`);
  }
  return cleaned;
}

function sourceId(value, field) {
  if (typeof value !== 'string' && typeof value !== 'number') fail(`${field} must be a string or number`);
  const result = String(value).trim();
  if (!result || result.length > 80) fail(`${field} is invalid`);
  return result;
}

function unique(records, idField, kind) {
  const seen = new Set();
  records.forEach((record) => {
    if (seen.has(record[idField])) fail(`duplicate ${kind} source id ${record[idField]}`);
    seen.add(record[idField]);
  });
  return records;
}

function buildHeroes(value) {
  if (!Array.isArray(value)) fail('hero source root must be an array');
  const records = value.map((record, index) => {
    if (!record || typeof record !== 'object') fail(`hero record ${index} is not an object`);
    const roles = Array.isArray(record.role)
      ? record.role.filter((role) => typeof role === 'string' && role.trim()).map((role) => role.trim().toLowerCase()).slice(0, 4)
      : [];
    return {
      sourceId: sourceId(record.id_hero, `hero[${index}].id_hero`),
      name: cleanText(record.name_hero, `hero[${index}].name_hero`),
      roles,
    };
  });
  if (records.length < MIN_HEROES) fail(`only ${records.length} heroes; need at least ${MIN_HEROES}`);
  return unique(records, 'sourceId', 'hero').sort((a, b) => Number(a.sourceId) - Number(b.sourceId));
}

function buildEquipment(value) {
  if (!Array.isArray(value)) fail('equipment source root must be an array');
  const records = value.map((record, index) => {
    if (!record || typeof record !== 'object') fail(`equipment record ${index} is not an object`);
    const rawPrice = record['prize-gold'];
    const priceGold = Number.isInteger(rawPrice) && rawPrice >= 0 && rawPrice <= 20_000 ? rawPrice : null;
    return {
      sourceId: sourceId(record.id_equip, `equipment[${index}].id_equip`),
      name: cleanText(record['name-equipment'], `equipment[${index}].name-equipment`),
      ...(priceGold === null ? {} : { priceGold }),
    };
  });
  if (records.length < MIN_EQUIPMENT) fail(`only ${records.length} equipment records; need at least ${MIN_EQUIPMENT}`);
  return unique(records, 'sourceId', 'equipment').sort((a, b) => Number(a.sourceId) - Number(b.sourceId));
}

const baseFile = argument('--base');
const heroesFile = argument('--heroes');
const equipmentFile = argument('--equipment');
const commit = argument('--upstream-commit');
const outFile = argument('--out');
if (!SHA.test(commit)) fail('upstream commit must be a git SHA');

const base = readJson(baseFile);
if (!base || typeof base !== 'object' || !Array.isArray(base.heroes) || base.heroes.length < 20) {
  fail('base feed must already contain at least 20 validated live hero records');
}
const heroes = buildHeroes(readJson(heroesFile));
const equipment = buildEquipment(readJson(equipmentFile));

const baseSource = String(base.source || 'unknown')
  .replace(/\s*\|\s*catalogue:\s*Ceplin03\/database-mlbb\.Mobile-Legends-Bang-Bang@[0-9a-f]+/gi, '')
  .trim() || 'unknown';
const overlay = {
  ...base,
  source: `${baseSource} | catalogue: Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang@${commit.slice(0, 12)}`,
  catalogue: {
    upstreamCommit: commit,
    heroes,
    equipment,
  },
};
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, `${JSON.stringify(overlay, null, 2)}\n`, 'utf8');
process.stdout.write(`wrote ${outFile}: ${heroes.length} heroes, ${equipment.length} equipment, source ${commit.slice(0, 12)}\n`);
