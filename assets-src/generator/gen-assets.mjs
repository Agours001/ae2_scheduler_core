/**
 * Entry point for (re)generating every scheduler-core asset.
 *
 *   node assets-src/generator/gen-assets.mjs
 *
 * This used to draw its own placeholder textures, which would silently clobber the AE2-derived artwork.
 * The real generation now lives in `gen-ae2-style-assets.mjs`; this file keeps the job of normalising
 * text resources (no UTF-8 BOM, which Minecraft's JSON reader rejects) and then runs the generator.
 */
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, basename } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = '<OLD-REPO>/src/main/resources';

// ---------- 1. strip UTF-8 BOM from every text resource ----------
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}
let stripped = 0;
for (const f of walk(root)) {
  const buf = readFileSync(f);
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    writeFileSync(f, buf.subarray(3));
    stripped++;
  }
}
console.log(`BOM stripped from ${stripped} file(s)`);

// ---------- 2. regenerate the artwork from AE2's own textures ----------
const gen = join(import.meta.dirname, 'gen-ae2-style-assets.mjs');
const res = spawnSync(process.execPath, [gen], { stdio: 'inherit' });
if (res.status !== 0) process.exit(res.status ?? 1);

// ---------- 3. re-strip, in case the generator wrote a BOM ----------
let again = 0;
for (const f of walk(root)) {
  const buf = readFileSync(f);
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    writeFileSync(f, buf.subarray(3));
    again++;
  }
}
if (again) console.log(`BOM re-stripped from ${again} file(s)`);

// ---------- 4. validate the resource tree ----------
// The artwork is single-frame only: no animation, no `.mcmeta`, every texture exactly 16x16.  These
// checks enforce that - AE2 drives the powered/formed look from code, so animated strips would be both
// wrong and invisible, and a stray `.mcmeta` is the usual way they creep in.
const NS = 'assets/schedulercore';
const problems = [];
const jsonAt = p => { try { return JSON.parse(readFileSync(p, 'utf8')); } catch (e) { problems.push(`${p}: invalid JSON (${e.message})`); return null; } };

// no animation metadata anywhere
const mcmetas = walk(root).filter(f => f.endsWith('.mcmeta'));
for (const f of mcmetas) problems.push(`${f.slice(root.length + 1)}: .mcmeta must not exist (no animations allowed)`);

// every texture is a single 16x16 frame
const pngDims = f => {
  const buf = readFileSync(f);
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) { problems.push(`${f}: not a PNG`); return null; }
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
};
for (const f of walk(join(root, NS, 'textures')).filter(f => f.endsWith('.png'))) {
  const d = pngDims(f);
  if (!d) continue;
  if (d.w !== 16 || d.h !== 16) problems.push(`${f.slice(root.length + 1)}: ${d.w}x${d.h}, expected a single 16x16 frame`);
}

// models: parent + textures.  `ae2:` references are AE2's own textures (see the generator header);
// they are checked against the AE2 jar by the generator itself.
const modelFiles = walk(join(root, NS, 'models')).filter(f => f.endsWith('.json'));
for (const f of modelFiles) {
  const m = jsonAt(f);
  if (!m) continue;
  const parent = m.parent ?? '';
  if (parent.startsWith('schedulercore:')) {
    const p = join(root, NS, 'models', parent.slice('schedulercore:'.length) + '.json');
    if (!existsSync(p)) problems.push(`${f}: missing parent ${parent}`);
  }
  for (const [slot, tex] of Object.entries(m.textures ?? {})) {
    if (!tex.startsWith('schedulercore:')) continue;
    const rel = tex.slice('schedulercore:'.length);
    const p = join(root, NS, 'textures', rel + '.png');
    if (!existsSync(p)) problems.push(`${f}: texture ${slot}=${tex} -> missing ${p}`);
  }
}
// blockstates -> models, and every formed/powered combination must be covered
for (const f of walk(join(root, NS, 'blockstates')).filter(f => f.endsWith('.json'))) {
  const b = jsonAt(f);
  if (!b) continue;
  for (const [variant, body] of Object.entries(b.variants ?? {})) {
    for (const entry of Array.isArray(body) ? body : [body]) {
      if (!entry.model?.startsWith('schedulercore:')) continue;
      const p = join(root, NS, 'models', entry.model.slice('schedulercore:'.length) + '.json');
      if (!existsSync(p)) problems.push(`${f}: variant "${variant}" -> missing model ${entry.model}`);
    }
  }
  if (basename(f) === 'scheduler_core_block.json') {
    for (const formed of ['false', 'true']) {
      for (const powered of ['false', 'true']) {
        const key = `formed=${formed},powered=${powered}`;
        if (!(key in (b.variants ?? {}))) problems.push(`${f}: variant "${key}" is not mapped`);
      }
    }
  }
}
if (problems.length) {
  console.error('\nRESOURCE VALIDATION FAILED:');
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log(`resource validation: no .mcmeta, all ${walk(join(root, NS, 'textures')).filter(f => f.endsWith('.png')).length} texture(s) are 16x16, every model/blockstate reference resolves`);
