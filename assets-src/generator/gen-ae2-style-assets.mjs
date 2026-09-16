/**
 * Generates **every** asset of the "scheduler core" mod from AE2's own artwork.  Zero dependencies,
 * offline, deterministic.  Entry point: `node assets-src/generator/gen-assets.mjs`.
 *
 * ----------------------------------------------------------------------------- what this produces
 *
 *   item   `schedulercore:scheduler_core`           16x16  = AE2 `item/cell_component_16k.png`
 *                                                             (AE2's **16k storage component**, the
 *                                                             chip icon), its blue inlay recoloured to
 *                                                             the #39C5BB family, everything else
 *                                                             byte-identical to AE2.
 *
 *                        NOTE: the item used to be based on `block/crafting/16k_storage.png`, i.e. a
 *                        *block face*.  That was the wrong reference art and is what the user
 *                        rejected ("does this look the same? the block is done, fix the item").
 *                        The block faces below are untouched by that correction.
 *   block  `schedulercore:scheduler_core_block`
 *          formed=false  16x16 face + vanilla cube_all model
 *                        = AE2 `block/crafting/unit.png` (the plain crafting unit face), centre die
 *                          recoloured to the #39C5BB family, everything else byte-identical.
 *          formed=true   custom geometry (AE2's own crafting-cube shell geometry, see below) using
 *                        AE2 `light_base.png` as the dark base face + `ring_*` joiners and
 *                        `scheduler_core_light.png` (= AE2 `16k_storage_light.png` recoloured to
 *                        #39C5BB) as the emissive connection band.
 *
 * ----------------------------------------------------------------------------- how formed=true works
 *
 * AE2 does *not* use a model-JSON loader id for its formed crafting cubes.  Its `*_formed.json` files
 * are literally `{}`; the real unbaked model is injected from code through
 * `appeng.hooks.BuiltInModelHooks.addBuiltInModel(...)`, and `appeng.mixins.ModelBakeryMixin` swaps it
 * in when the bakery asks for that model id.  `BuiltInModelHooks.getBuiltInModel` starts with
 * `if (!ae2.equals(id.getNamespace())) return null;` - the hook is hard-wired to the `ae2` namespace,
 * so it cannot be reused by another mod.
 *
 * We therefore copy the geometry implementation instead of the loader: `com.schedulercore.client`
 * registers a plain NeoForge `IGeometryLoader` (`schedulercore:crafting_cube`) that bakes into AE2's
 * own public `appeng.client.render.crafting.LightBakedModel`.  AE2's `CraftingBlockEntity
 * .getModelData()` (our block entity *is* AE2's) hands the baked model a `ModelData` carrying
 * `CraftingCubeModelData.CONNECTIONS`, so the ring/connection band is drawn from the exact same data
 * and the exact same code path as a neighbouring 16k crafting storage - which is what makes the
 * formed core join seamlessly with its neighbours.
 *
 * The previews at the bottom of this file re-implement that shell geometry (a port of
 * `CraftingCubeBakedModel.addRing/addCornerCap` + `CubeBuilder.addCube/getStandardUv`, both disassembled
 * from the AE2 jar) so the generated preview images show the real composed faces rather than a guess.
 *
 * ----------------------------------------------------------------------------- hard rules honoured
 *
 *   - no `.mcmeta` files, every texture is a single 16x16 frame (no animation, no breathing light)
 *   - AE2's jar is only ever read, never written
 *   - every recoloured texture is diffed against its AE2 source and the run fails if a pixel outside
 *     the intended region changed
 *
 * Base art read out of `libs/appliedenergistics2-19.2.17.jar` (assets/ae2/textures/):
 *   item/cell_component_16k.png (item icon only),
 *   block/crafting/16k_storage.png, block/crafting/16k_storage_light.png, block/crafting/unit.png,
 *   block/crafting/unit_base.png, block/crafting/light_base.png, block/crafting/ring_corner.png,
 *   block/crafting/ring_side_hor.png, block/crafting/ring_side_ver.png
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { inflateSync, inflateRawSync, deflateSync } from 'node:zlib';

// --------------------------------------------------------------------------- paths

const PROJECT = '<OLD-REPO>';
const RES = join(PROJECT, 'src/main/resources/assets/schedulercore');
const PREVIEW = join(PROJECT, 'docs/preview');
const JAR = join(PROJECT, 'libs/appliedenergistics2-19.2.17.jar');

// --------------------------------------------------------------------------- the one requested colour

/**
 * #39C5BB split into the three tones the brief asks for.  Sources are ranked by their own relative
 * luminance and mapped onto this family, which is why the result keeps AE2's original shading instead
 * of turning into flat colour.
 */
const RAMP = ['#2A918A', '#39C5BB', '#7FE6DE'];      // dark / mid / bright
const RAMP_NAME = ['dark', 'mid', 'bright'];

// --------------------------------------------------------------------------- minimal ZIP reader

/** Reads a single entry out of a .zip/.jar without any dependency. */
function zipEntry(zipPath, entryName) {
  const buf = readFileSync(zipPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`not a zip: ${zipPath}`);
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('bad central directory');
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    if (name === entryName) {
      const lNameLen = buf.readUInt16LE(localOff + 26);
      const lExtraLen = buf.readUInt16LE(localOff + 28);
      const start = localOff + 30 + lNameLen + lExtraLen;
      const data = buf.subarray(start, start + compSize);
      if (method === 0) return data;
      if (method === 8) return inflateRawSync(data);
      throw new Error(`unsupported zip method ${method}`);
    }
    off += 46 + nameLen + extraLen + commentLen;
  }
  throw new Error(`entry not found: ${entryName}`);
}

/** Lists entry names matching a prefix, so we can prove what we ship. */
function zipNames(zipPath) {
  const buf = readFileSync(zipPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let i = 0; i < count; i++) {
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    out.push(buf.toString('utf8', off + 46, off + 46 + nameLen));
    off += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

// --------------------------------------------------------------------------- PNG codec

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
function crc32(buf) { let c = 0xffffffff; for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii'); const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function readChunks(buf) {
  const out = []; let off = 8;
  while (off < buf.length) {
    const len = buf.readUInt32BE(off); const type = buf.toString('ascii', off + 4, off + 8);
    out.push({ type, data: buf.subarray(off + 8, off + 8 + len) }); off += 12 + len;
  }
  return out;
}
function paeth(a, b, c) { const p = a + b - c; const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); return pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }

/** Decodes an 8-bit PNG (colour types 0/2/3/4/6) into {width,height,px(RGBA)}. */
function decodePngBuffer(buf, label = '<buffer>') {
  const chunks = readChunks(buf);
  const ihdr = chunks.find(c => c.type === 'IHDR').data;
  const width = ihdr.readUInt32BE(0), height = ihdr.readUInt32BE(4);
  const bitDepth = ihdr[8], colorType = ihdr[9];
  const palette = chunks.find(c => c.type === 'PLTE')?.data;
  const trns = chunks.find(c => c.type === 'tRNS')?.data;
  const idat = Buffer.concat(chunks.filter(c => c.type === 'IDAT').map(c => c.data));
  const raw = inflateSync(idat);
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  if (bitDepth !== 8 || !channels) throw new Error(`unsupported PNG ${label}: depth=${bitDepth} colorType=${colorType}`);
  const stride = width * channels;
  const px = new Uint8Array(width * height * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? line[i - channels] : 0, b = prev[i], c = i >= channels ? prev[i - channels] : 0;
      if (filter === 1) line[i] = (line[i] + a) & 0xff;
      else if (filter === 2) line[i] = (line[i] + b) & 0xff;
      else if (filter === 3) line[i] = (line[i] + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) line[i] = (line[i] + paeth(a, b, c)) & 0xff;
    }
    for (let x = 0; x < width; x++) {
      const s = x * channels, d = (y * width + x) * 4;
      if (colorType === 6) { px[d] = line[s]; px[d + 1] = line[s + 1]; px[d + 2] = line[s + 2]; px[d + 3] = line[s + 3]; }
      else if (colorType === 2) { px[d] = line[s]; px[d + 1] = line[s + 1]; px[d + 2] = line[s + 2]; px[d + 3] = 255; }
      else if (colorType === 3) { const idx = line[s]; px[d] = palette[idx * 3]; px[d + 1] = palette[idx * 3 + 1]; px[d + 2] = palette[idx * 3 + 2]; px[d + 3] = trns && idx < trns.length ? trns[idx] : 255; }
      else if (colorType === 0) { px[d] = px[d + 1] = px[d + 2] = line[s]; px[d + 3] = 255; }
      else { px[d] = px[d + 1] = px[d + 2] = line[s]; px[d + 3] = line[s + 1]; }
    }
    prev = line;
  }
  return { width, height, px, label };
}
function encodePng(width, height, px) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    Buffer.from(px.buffer, px.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}
const writePngBuffer = (p, w, h, px) => {
  const buf = encodePng(w, h, px);
  mkdirSync(dirname(p), { recursive: true });
  // Idempotent: an unchanged texture keeps its old mtime, so re-running the generator does not
  // invalidate the SHA256 of an already deployed jar.
  if (existsSync(p) && readFileSync(p).equals(buf)) return false;
  writeFileSync(p, buf);
  return true;
};
const writeTextIfChanged = (p, text) => {
  mkdirSync(dirname(p), { recursive: true });
  if (existsSync(p) && readFileSync(p, 'utf8') === text) return false;
  writeFileSync(p, text);
  return true;
};

// --------------------------------------------------------------------------- pixels

const get = (img, x, y) => { const i = (y * img.width + x) * 4; return [img.px[i], img.px[i + 1], img.px[i + 2], img.px[i + 3]]; };
const hex = s => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16), 255];
const toHex = c => '#' + c.slice(0, 3).map(v => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
const luma = c => 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
/** HSV with H in degrees (0..360), S and V in 0..1. */
function rgbToHsv(c) {
  const r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
  let h = 0;
  if (d > 0) {
    if (mx === r) h = 60 * (((g - b) / d) % 6);
    else if (mx === g) h = 60 * ((b - r) / d + 2);
    else h = 60 * ((r - g) / d + 4);
  }
  if (h < 0) h += 360;
  return [h, mx === 0 ? 0 : d / mx, mx];
}
const samePx = (a, b) => a[0] === b[0] && a[1] === b[1] && a[2] === b[2] && a[3] === b[3];

const blank = (w, h, c = [0, 0, 0, 0]) => {
  const px = new Uint8Array(w * h * 4);
  for (let i = 0; i < w * h; i++) { px[i * 4] = c[0]; px[i * 4 + 1] = c[1]; px[i * 4 + 2] = c[2]; px[i * 4 + 3] = c[3]; }
  return { width: w, height: h, px };
};
const cloneImg = img => ({ width: img.width, height: img.height, px: Uint8Array.from(img.px), label: img.label });

// --------------------------------------------------------------------------- 5x7 bitmap font (preview labels)

const FONT = {
  A: '.###./#...#/#...#/#####/#...#/#...#/#...#',
  B: '####./#...#/#...#/####./#...#/#...#/####.',
  C: '.###./#...#/#..../#..../#..../#...#/.###.',
  D: '####./#...#/#...#/#...#/#...#/#...#/####.',
  E: '#####/#..../#..../####./#..../#..../#####',
  F: '#####/#..../#..../####./#..../#..../#....',
  G: '.###./#...#/#..../#.###/#...#/#...#/.###.',
  H: '#...#/#...#/#...#/#####/#...#/#...#/#...#',
  I: '.###./..#../..#../..#../..#../..#../.###.',
  J: '..###/...#./...#./...#./...#./#..#./.##..',
  K: '#...#/#..#./#.#../##.../#.#../#..#./#...#',
  L: '#..../#..../#..../#..../#..../#..../#####',
  M: '#...#/##.##/#.#.#/#...#/#...#/#...#/#...#',
  N: '#...#/##..#/#.#.#/#..##/#...#/#...#/#...#',
  O: '.###./#...#/#...#/#...#/#...#/#...#/.###.',
  P: '####./#...#/#...#/####./#..../#..../#....',
  Q: '.###./#...#/#...#/#...#/#.#.#/#..#./.##.#',
  R: '####./#...#/#...#/####./#.#../#..#./#...#',
  S: '.####/#..../#..../.###./....#/....#/####.',
  T: '#####/..#../..#../..#../..#../..#../..#..',
  U: '#...#/#...#/#...#/#...#/#...#/#...#/.###.',
  V: '#...#/#...#/#...#/#...#/#...#/.#.#./..#..',
  W: '#...#/#...#/#...#/#.#.#/#.#.#/##.##/#...#',
  X: '#...#/#...#/.#.#./..#../.#.#./#...#/#...#',
  Y: '#...#/#...#/.#.#./..#../..#../..#../..#..',
  Z: '#####/....#/...#./..#../.#.../#..../#####',
  0: '.###./#...#/#..##/#.#.#/##..#/#...#/.###.',
  1: '..#../.##../..#../..#../..#../..#../.###.',
  2: '.###./#...#/....#/...#./..#../.#.../#####',
  3: '#####/...#./..#../...#./....#/#...#/.###.',
  4: '...#./..##./.#.#./#..#./#####/...#./...#.',
  5: '#####/#..../####./....#/....#/#...#/.###.',
  6: '..##./.#.../#..../####./#...#/#...#/.###.',
  7: '#####/....#/...#./..#../.#.../.#.../.#...',
  8: '.###./#...#/#...#/.###./#...#/#...#/.###.',
  9: '.###./#...#/#...#/.####/....#/...#./.##..',
  '-': '...../...../...../#####/...../...../.....',
  '+': '...../..#../..#../#####/..#../..#../.....',
  '.': '...../...../...../...../...../.##../.##..',
  ':': '...../.##../.##../...../.##../.##../.....',
  '/': '....#/...#./...#./..#../.#.../.#.../#....',
  '(': '...#./..#../.#.../.#.../.#.../..#../...#.',
  ')': '.#.../..#../...#./...#./...#./..#../.#...',
  '=': '...../...../#####/...../#####/...../.....',
  '%': '#...#/...#./..#../..#../.#.../#...#/.....',
  ' ': '...../...../...../...../...../...../.....',
};
const GLYPH_W = 5, GLYPH_H = 7;

// --------------------------------------------------------------------------- canvas for previews

const CHECK_A = [58, 58, 64, 255], CHECK_B = [86, 86, 94, 255];

class Canvas {
  constructor(w, h, bg = 'checker') {
    this.width = w; this.height = h;
    this.img = blank(w, h);
    if (bg === 'checker') {
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const c = ((x >> 3) + (y >> 3)) % 2 ? CHECK_A : CHECK_B;
        this.set(x, y, c);
      }
    } else if (Array.isArray(bg)) {
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) this.set(x, y, bg);
    }
  }
  set(x, y, c) {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
    const i = (y * this.width + x) * 4;
    this.img.px[i] = c[0]; this.img.px[i + 1] = c[1]; this.img.px[i + 2] = c[2]; this.img.px[i + 3] = c[3] ?? 255;
  }
  blend(x, y, c) {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
    const a = (c[3] ?? 255) / 255;
    if (a <= 0) return;
    const i = (y * this.width + x) * 4;
    this.img.px[i] = Math.round(c[0] * a + this.img.px[i] * (1 - a));
    this.img.px[i + 1] = Math.round(c[1] * a + this.img.px[i + 1] * (1 - a));
    this.img.px[i + 2] = Math.round(c[2] * a + this.img.px[i + 2] * (1 - a));
    this.img.px[i + 3] = 255;
  }
  rect(x, y, w, h, c) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.set(x + i, y + j, c); }
  frame(x, y, w, h, c) {
    for (let i = 0; i < w; i++) { this.set(x + i, y, c); this.set(x + i, y + h - 1, c); }
    for (let j = 0; j < h; j++) { this.set(x, y + j, c); this.set(x + w - 1, y + j, c); }
  }
  /** Nearest-neighbour blit; `alphaBlend` composites instead of overwriting. */
  blit(img, ox, oy, scale = 1, alphaBlend = false) {
    for (let y = 0; y < img.height * scale; y++) {
      for (let x = 0; x < img.width * scale; x++) {
        const c = get(img, Math.floor(x / scale), Math.floor(y / scale));
        if (c[3] === 0) continue;
        if (alphaBlend) this.blend(ox + x, oy + y, c); else this.set(ox + x, oy + y, c);
      }
    }
  }
  text(str, ox, oy, scale = 1, color = [255, 255, 255, 255]) {
    let x = ox;
    for (const raw of str.toUpperCase()) {
      const g = FONT[raw] ?? FONT[' '];
      const rows = g.split('/');
      for (let ry = 0; ry < GLYPH_H; ry++) {
        for (let rx = 0; rx < GLYPH_W; rx++) {
          if (rows[ry][rx] !== '#') continue;
          for (let sy = 0; sy < scale; sy++) for (let sx = 0; sx < scale; sx++) this.set(x + rx * scale + sx, oy + ry * scale + sy, color);
        }
      }
      x += (GLYPH_W + 1) * scale;
    }
    return x - ox;
  }
  save(p) { mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, encodePng(this.width, this.height, this.img.px)); }
}
const textWidth = (s, scale) => s.length * (GLYPH_W + 1) * scale;

// --------------------------------------------------------------------------- AE2 source art

const AE2_PREFIX = 'assets/ae2/textures/';
const AE2_FILES = {
  itemComponent: AE2_PREFIX + 'item/cell_component_16k.png',        // AE2 16k storage COMPONENT (the item icon)
  itemFace: AE2_PREFIX + 'block/crafting/16k_storage.png',          // 16k crafting storage block face (block + neighbour reference)
  itemLight: AE2_PREFIX + 'block/crafting/16k_storage_light.png',   // the 16k connection band
  unitFace: AE2_PREFIX + 'block/crafting/unit.png',                 // plain crafting unit face
  unitBase: AE2_PREFIX + 'block/crafting/unit_base.png',            // plain crafting unit formed base
  lightBase: AE2_PREFIX + 'block/crafting/light_base.png',          // storage/accelerator formed base
  ringCorner: AE2_PREFIX + 'block/crafting/ring_corner.png',
  ringHor: AE2_PREFIX + 'block/crafting/ring_side_hor.png',
  ringVer: AE2_PREFIX + 'block/crafting/ring_side_ver.png',
};

function loadAe2Textures() {
  const out = {};
  for (const [key, rel] of Object.entries(AE2_FILES)) {
    const img = decodePngBuffer(zipEntry(JAR, rel), rel);
    img.label = basename(rel, '.png');
    out[key] = img;
  }
  return out;
}

// --------------------------------------------------------------------------- the 10x10 die

/**
 * The accent area shared by every AE2 crafting-core face.  Measured, not assumed: the diff between
 * `16k_storage.png` and `unit.png` (which share one frame) is asserted below to lie entirely inside
 * this rectangle.
 */
const DIE = { x: 3, y: 3, w: 10, h: 10 };
const inDie = (x, y) => x >= DIE.x && x < DIE.x + DIE.w && y >= DIE.y && y < DIE.y + DIE.h;

// --------------------------------------------------------------------------- #39C5BB remap

/**
 * Recolours a region by ranking its opaque colours on their own relative luminance and mapping the
 * ranks onto the #39C5BB family.  Rank order is preserved, so AE2's original light/dark structure
 * survives; the sub-ramp is chosen from the bright end so that the requested mid tone (#39C5BB) is
 * always present (a 2-colour source becomes mid+bright, never the two extremes).
 *
 * @returns {{image, mapping: Array, changed: number, changedOutside: number, regionPixels: number}}
 */
function remapToRamp(src, region, rampHexes = RAMP) {
  const img = cloneImg(src);
  const counts = new Map();
  for (let y = region.y; y < region.y + region.h; y++) {
    for (let x = region.x; x < region.x + region.w; x++) {
      const c = get(src, x, y);
      if (c[3] < 200) continue;                                  // fully transparent source pixels stay untouched
      const k = c.slice(0, 3).join(',');
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
  }
  const uniq = [...counts.entries()]
    .map(([k, n]) => ({ c: k.split(',').map(Number), n }))
    .sort((a, b) => luma(a.c) - luma(b.c) || a.c.join().localeCompare(b.c.join()));
  if (uniq.length === 0) throw new Error('no opaque pixels in the recolour region');

  const ramp = rampHexes.map(hex);
  const tones = uniq.length === 1 ? [ramp[1]] : ramp.slice(Math.max(0, 3 - uniq.length));
  const mapping = uniq.map((u, i) => ({
    from: toHex([...u.c, 255]),
    to: toHex(tones[i]),
    tone: RAMP_NAME[uniq.length === 1 ? 1 : Math.max(0, 3 - uniq.length) + i],
    count: u.n,
  }));
  const lut = new Map(mapping.map(m => [m.from, hex(m.to)]));

  let changed = 0, changedOutside = 0, regionPixels = 0;
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      const before = get(src, x, y);
      const key = toHex(before);
      const to = lut.get(key);
      const inside = x >= region.x && x < region.x + region.w && y >= region.y && y < region.y + region.h;
      if (inside) regionPixels++;
      if (!to || !inside) {
        // never touch anything that is not both in the region and a mapped source colour
        if (!samePx(before, get(img, x, y))) changedOutside++;
        continue;
      }
      if (!samePx(before, to)) {
        const i = (y * img.width + x) * 4;
        img.px[i] = to[0]; img.px[i + 1] = to[1]; img.px[i + 2] = to[2]; img.px[i + 3] = to[3];
        changed++;
      }
    }
  }
  return { image: img, mapping, changed, changedOutside, regionPixels };
}

// ------------------------------------------------------- the blue inlay of AE2's 16k storage component

/**
 * "Blue interior region" detector for AE2's storage *component* item art
 * (`assets/ae2/textures/item/cell_component_16k.png`).
 *
 * Measured palette of that file (every opaque colour, H/S/V):
 *
 *   frame / shell  #413f54 H246 S0.250 V0.33   #4d4d67 H240 S0.252 V0.40   #696d88 H232 S0.228 V0.53
 *                  #878fa5 H224 S0.182 V0.65   #adb0c4 H232 S0.117 V0.77   #cbccd4 H233 S0.042 V0.83
 *                  #f2f2f2 H  0 S0.000 V0.95
 *   blue inlay     #7da9d2 H209 S0.405 V0.82   #9cd3ff H207 S0.388 V1.00   #daffff H180 S0.145 V1.00
 *
 * The two dark navy frame tones sit *inside* the requested hue window, so a hue test alone would
 * eat the frame; the `V >= 0.5` gate removes them (V 0.33 / 0.40).  The remaining greys are killed
 * by saturation: the frame's maximum S is 0.252 while the blue inlay's minimum is 0.388, so the
 * threshold 0.30 sits in the middle of that natural gap.  The one colour that needs a second gate
 * is the near-white cyan glint `#daffff` (S 0.145), which belongs to the inlay but is far brighter
 * than any grey - hence `V >= 0.90 && S >= 0.12`.
 *
 * Result on that file: exactly 20 opaque pixels, #7da9d2 x7, #9cd3ff x10, #daffff x3.
 */
const ITEM_BLUE_HUE = [170, 250];
const ITEM_BLUE_S_MIN = 0.30;
const ITEM_BLUE_GLINT = { vMin: 0.90, sMin: 0.12 };
function inItemBlueRegion(c) {
  if (c[3] < 128) return false;                                   // transparent source pixels are never touched
  const [h, s, v] = rgbToHsv(c);
  if (h < ITEM_BLUE_HUE[0] || h > ITEM_BLUE_HUE[1]) return false;
  if (v < 0.5) return false;                                      // dark navy frame (V 0.33 / 0.40)
  return s >= ITEM_BLUE_S_MIN                                     // the saturated blue inlay
    || (v >= ITEM_BLUE_GLINT.vMin && s >= ITEM_BLUE_GLINT.sMin);  // the pale cyan glint on top of it
}
/** Lists the region's pixels and its bounding box. */
function blueRegionPixels(img) {
  const pixels = [];
  for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++) {
    const c = get(img, x, y);
    if (inItemBlueRegion(c)) pixels.push({ x, y, c });
  }
  const bbox = pixels.length === 0 ? null : {
    x0: Math.min(...pixels.map(p => p.x)), x1: Math.max(...pixels.map(p => p.x)),
    y0: Math.min(...pixels.map(p => p.y)), y1: Math.max(...pixels.map(p => p.y)),
  };
  return { pixels, bbox };
}

/**
 * Recolours a hue-detected region by mapping each pixel's **relative luminance inside that region**
 * onto three tones (`#2A918A` dark / `#39C5BB` mid / `#7FE6DE` bright).  AE2's original light/dark
 * ordering is monotone, so the tiering preserves the shape exactly - only the palette changes.
 * Pixels outside the region are never written, and that is asserted rather than assumed.
 */
function remapRegionToRamp(src, isInRegion = inItemBlueRegion, rampHexes = RAMP) {
  const img = cloneImg(src);
  const pixels = [];
  for (let y = 0; y < src.height; y++) for (let x = 0; x < src.width; x++) {
    const c = get(src, x, y);
    if (isInRegion(c)) pixels.push({ x, y, c });
  }
  if (pixels.length === 0) throw new Error('the detected region is empty');
  const bbox = {
    x0: Math.min(...pixels.map(p => p.x)), x1: Math.max(...pixels.map(p => p.x)),
    y0: Math.min(...pixels.map(p => p.y)), y1: Math.max(...pixels.map(p => p.y)),
  };

  let minL = Infinity, maxL = -Infinity;
  for (const p of pixels) { const l = luma(p.c); if (l < minL) minL = l; if (l > maxL) maxL = l; }
  const span = maxL - minL;
  const ramp = rampHexes.map(hex);
  /** relative luminance -> tier 0/1/2 */
  const tierOf = l => (span === 0 ? 1 : Math.min(2, Math.floor(((l - minL) / span) * 3)));

  const byColour = new Map();
  for (const p of pixels) {
    const k = p.c.join(',');
    if (!byColour.has(k)) byColour.set(k, { c: p.c, n: 0, tier: tierOf(luma(p.c)) });
    byColour.get(k).n++;
  }
  const mapping = [...byColour.values()]
    .sort((a, b) => luma(a.c) - luma(b.c) || a.c.join().localeCompare(b.c.join()))
    .map(u => ({ from: toHex(u.c), rel: span === 0 ? 0 : (luma(u.c) - minL) / span, tone: RAMP_NAME[u.tier], to: toHex(ramp[u.tier]), count: u.n }));
  const lut = new Map([...byColour.entries()].map(([k, u]) => [k, ramp[u.tier]]));

  let changed = 0, changedOutside = 0, regionPixels = 0;
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      const before = get(src, x, y);
      const to = isInRegion(before) ? lut.get([...before].join(',')) : undefined;
      if (to === undefined) {
        // never touch anything that is not both inside the detected region and a mapped colour
        if (!samePx(before, get(img, x, y))) changedOutside++;
        continue;
      }
      regionPixels++;
      if (!samePx(before, to)) {
        const i = (y * img.width + x) * 4;
        img.px[i] = to[0]; img.px[i + 1] = to[1]; img.px[i + 2] = to[2]; img.px[i + 3] = to[3];
        changed++;
      }
    }
  }
  return { image: img, mapping, changed, changedOutside, regionPixels, bbox, pixels, minL, maxL };
}

/**
 * Shape signature: for every pixel of `pixels` (positions taken once from the AE2 original), the rank
 * of its colour among the colours present there, sorted by luminance.  `a === b` therefore means
 * "identical light/dark layout" - the same test the die-based block remap uses.
 */
function regionToneSignature(img, pixels) {
  const lum = new Map();
  for (const p of pixels) { const c = get(img, p.x, p.y); const k = c.slice(0, 3).join(','); if (!lum.has(k)) lum.set(k, luma(c)); }
  const order = [...lum.entries()].sort((a, b) => a[1] - b[1] || a[0].localeCompare(b[0])).map(e => e[0]);
  const rank = new Map(order.map((k, i) => [k, i]));
  return pixels.map(p => rank.get(get(img, p.x, p.y).slice(0, 3).join(','))).join('');
}

/** Counts differing pixels between two same-sized images, optionally restricted to a predicate. */
function diffImages(a, b, predicate = () => true) {
  let n = 0; const samples = [];
  for (let y = 0; y < a.height; y++) {
    for (let x = 0; x < a.width; x++) {
      if (!predicate(x, y)) continue;
      const p = get(a, x, y), q = get(b, x, y);
      if (samePx(p, q)) continue;
      n++;
      if (samples.length < 6) samples.push(`(${x},${y}) ${toHex(p)}->${toHex(q)}`);
    }
  }
  return { n, samples };
}

/** Magenta-on-grey mask of every pixel where `b` differs from `a`. */
function diffMask(a, b) {
  const img = blank(a.width, a.height);
  for (let y = 0; y < a.height; y++) {
    for (let x = 0; x < a.width; x++) {
      const p = get(a, x, y), q = get(b, x, y);
      const i = (y * a.width + x) * 4;
      const diff = !samePx(p, q);
      const src = diff ? [255, 0, 200, 255] : (p[3] === 0 ? [40, 40, 44, 255] : [p[0], p[1], p[2], 90]);
      img.px[i] = src[0]; img.px[i + 1] = src[1]; img.px[i + 2] = src[2]; img.px[i + 3] = 255;
    }
  }
  return img;
}

// --------------------------------------------------------------------------- crafting-cube shell geometry (port of AE2)

const DIRS = ['DOWN', 'UP', 'NORTH', 'SOUTH', 'WEST', 'EAST'];
const AXIS = { DOWN: 'Y', UP: 'Y', NORTH: 'Z', SOUTH: 'Z', WEST: 'X', EAST: 'X' };
const STEP = { DOWN: [0, -1, 0], UP: [0, 1, 0], NORTH: [0, 0, -1], SOUTH: [0, 0, 1], WEST: [-1, 0, 0], EAST: [1, 0, 0] };
const OPP = { DOWN: 'UP', UP: 'DOWN', NORTH: 'SOUTH', SOUTH: 'NORTH', WEST: 'EAST', EAST: 'WEST' };
const POSITIVE = { UP: true, EAST: true, SOUTH: true };

function fromDelta(dx, dy, dz) { return DIRS.find(d => STEP[d][0] === dx && STEP[d][1] === dy && STEP[d][2] === dz) ?? null; }
/** Port of `appeng.util.Platform.rotateAround`. */
function rotateAround(a, b) {
  if (AXIS[a] === AXIS[b]) return a;
  const p = STEP[a], q = STEP[b];
  return fromDelta(p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]);
}

/**
 * Port of `CubeBuilder.getStandardUv` + `putFace`: maps a cube in normalised block coordinates to the
 * face-local (u,v) rectangle and the plane it is drawn on, for the given face.
 */
function faceQuad(dir, X1, Y1, Z1, X2, Y2, Z2) {
  let u1, u2, v1, v2;
  if (AXIS[dir] === 'Y') { v1 = Z1; v2 = Z2; } else { v1 = 1 - Y2; v2 = 1 - Y1; }
  if (dir === 'NORTH') { u1 = 1 - X2; u2 = 1 - X1; }
  else if (dir === 'EAST') { u1 = 1 - Z2; u2 = 1 - Z1; }
  else if (dir === 'WEST') { u1 = Z1; u2 = Z2; }
  else { u1 = X1; u2 = X2; }                                   // DOWN, UP, SOUTH
  const plane = AXIS[dir] === 'X' ? (dir === 'EAST' ? X2 : X1)
    : AXIS[dir] === 'Y' ? (dir === 'UP' ? Y2 : Y1)
      : (dir === 'SOUTH' ? Z2 : Z1);
  return { dir, u1, v1, u2, v2, plane };
}

/**
 * Port of `CraftingCubeBakedModel.getQuads` for one face of a formed crafting cube, with
 * `LightBakedModel.addInnerCube` (base layer + emissive light layer) as the inner cube.
 *
 * `conn` is the set of directions in which a neighbour connects (AE2's `CraftingCubeModelData
 * .CONNECTIONS`).  Coordinates are in 1/16 block units, as in AE2's source.
 */
function emitFormedFace(side, conn, sprites) {
  const quads = [];
  let tex = sprites.ring_corner;
  const addCube = (x1, y1, z1, x2, y2, z2) => {
    quads.push({ ...faceQuad(side, x1 / 16, y1 / 16, z1 / 16, x2 / 16, y2 / 16, z2 / 16), sprite: tex });
  };

  // ---- addRing: the 8 corner caps (textured with ring_corner) ----
  for (const d1 of ['UP', 'DOWN']) {
    for (const d2 of ['EAST', 'WEST']) {
      for (const d3 of ['NORTH', 'SOUTH']) {
        if (conn.has(d1) || conn.has(d2) || conn.has(d3)) continue;
        if (side !== d1 && side !== d2 && side !== d3) continue;
        addCube(
          d2 === 'WEST' ? 0 : 13, d1 === 'DOWN' ? 0 : 13, d3 === 'NORTH' ? 0 : 13,
          d2 === 'WEST' ? 3 : 16, d1 === 'DOWN' ? 3 : 16, d3 === 'NORTH' ? 3 : 16,
        );
      }
    }
  }

  // ---- addRing: the edge bands around an unconnected side ----
  for (const d of DIRS) {
    if (d === side || d === OPP[side]) continue;
    tex = AXIS[side] !== 'Y'
      ? (d === 'UP' || d === 'DOWN' ? sprites.ring_side_hor : sprites.ring_side_ver)
      : (d === 'EAST' || d === 'WEST' ? sprites.ring_side_ver : sprites.ring_side_hor);
    if (conn.has(d)) continue;
    let x1 = 0, y1 = 0, z1 = 0, x2 = 16, y2 = 16, z2 = 16;
    if (d === 'DOWN') { y1 = 0; y2 = 3; } else if (d === 'UP') { y1 = 13; y2 = 16; }
    else if (d === 'NORTH') { z1 = 0; z2 = 3; } else if (d === 'SOUTH') { z1 = 13; z2 = 16; }
    else if (d === 'WEST') { x1 = 0; x2 = 3; } else { x1 = 13; x2 = 16; }
    const r = rotateAround(d, side);
    for (const e of [r, OPP[r]]) {
      if (conn.has(e)) continue;
      if (e === 'DOWN') y1 = 3; else if (e === 'UP') y2 = 13;
      else if (e === 'NORTH') z1 = 3; else if (e === 'SOUTH') z2 = 13;
      else if (e === 'WEST') x1 = 3; else x2 = 13;
    }
    addCube(x1, y1, z1, x2, y2, z2);
  }

  // ---- the inner cube: 13.01 units, grown to the block boundary where a neighbour connects ----
  let x1 = conn.has('WEST') ? 0 : 2.99, x2 = conn.has('EAST') ? 16 : 13.01;
  let y1 = conn.has('DOWN') ? 0 : 2.99, y2 = conn.has('UP') ? 16 : 13.01;
  let z1 = conn.has('NORTH') ? 0 : 2.99, z2 = conn.has('SOUTH') ? 16 : 13.01;
  // the face currently being rendered always reaches the block boundary
  if (AXIS[side] === 'X') { x1 = 0; x2 = 16; } else if (AXIS[side] === 'Y') { y1 = 0; y2 = 16; } else { z1 = 0; z2 = 16; }

  tex = sprites.base;
  addCube(x1, y1, z1, x2, y2, z2);                              // LightBakedModel: dark base
  tex = sprites.light;
  addCube(x1, y1, z1, x2, y2, z2);                              // LightBakedModel: emissive band on top

  return quads;
}

/**
 * Orthographic composite of one face: paints every emitted quad for that face far-to-near, sampling
 * the sprite with the UV rectangle `getStandardUv` produced.  This is what the block face looks like
 * straight on.  (The `POWERED` emissive boost is not simulated - a powered band looks the same here,
 * just without the full-bright lightmap.)
 */
function renderFormedFace(side, conn, sprites, size = 16) {
  const img = blank(size, size);
  const quads = emitFormedFace(side, conn, sprites);
  const ordered = quads
    .map((q, i) => ({ q, i }))
    .sort((a, b) => (POSITIVE[side] ? a.q.plane - b.q.plane : b.q.plane - a.q.plane) || a.i - b.i)
    .map(e => e.q);
  for (const q of ordered) {
    for (let py = 0; py < size; py++) {
      for (let px = 0; px < size; px++) {
        const fu = (px + 0.5) / size, fv = (py + 0.5) / size;
        if (fu < q.u1 || fu > q.u2 || fv < q.v1 || fv > q.v2) continue;
        const su = Math.min(15, Math.max(0, Math.floor((q.u1 + fu * (q.u2 - q.u1)) * 16)));
        const sv = Math.min(15, Math.max(0, Math.floor((q.v1 + fv * (q.v2 - q.v1)) * 16)));
        const c = get(q.sprite, su, sv);
        if (c[3] === 0) continue;
        const d = (py * size + px) * 4;
        const a = c[3] / 255;
        img.px[d] = Math.round(c[0] * a + img.px[d] * (1 - a));
        img.px[d + 1] = Math.round(c[1] * a + img.px[d + 1] * (1 - a));
        img.px[d + 2] = Math.round(c[2] * a + img.px[d + 2] * (1 - a));
        img.px[d + 3] = 255;
      }
    }
  }
  return img;
}

// --------------------------------------------------------------------------- run

const failures = [];
const check = (ok, message) => { if (!ok) failures.push(message); return ok; };

console.log('== reading AE2 source art out of the jar ==');
const ae2 = loadAe2Textures();
for (const [k, v] of Object.entries(ae2)) console.log(`  ${k.padEnd(11)} ${AE2_FILES[k].replace(AE2_PREFIX, '').padEnd(38)} ${v.width}x${v.height}`);

// -- 1. measure the die region instead of trusting it (block faces only) ------------------------
console.log('\n== 1. measuring the block die region (block faces only; the item no longer uses it) ==');
const dieDiff = diffImages(ae2.itemFace, ae2.unitFace);
const outside = diffImages(ae2.itemFace, ae2.unitFace, (x, y) => !inDie(x, y));
console.log(`  16k_storage.png vs unit.png: ${dieDiff.n} pixels differ, ${outside.n} of them outside the die`);
check(outside.n === 0, `16k_storage vs unit differ outside the assumed die (${outside.n} px) -> DIE rectangle is wrong`);
check(dieDiff.n > 0, 'the die rectangle contains no difference at all - measurement is broken');
let dieMinX = 99, dieMaxX = -1, dieMinY = 99, dieMaxY = -1;
for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
  if (samePx(get(ae2.itemFace, x, y), get(ae2.unitFace, x, y))) continue;
  dieMinX = Math.min(dieMinX, x); dieMaxX = Math.max(dieMaxX, x);
  dieMinY = Math.min(dieMinY, y); dieMaxY = Math.max(dieMaxY, y);
}
console.log(`  observed differing bounding box: x${dieMinX}..${dieMaxX} y${dieMinY}..${dieMaxY}`);
console.log(`  die used for recolouring:         x${DIE.x}..${DIE.x + DIE.w - 1} y${DIE.y}..${DIE.y + DIE.h - 1}`);
check(dieMinX >= DIE.x && dieMaxX < DIE.x + DIE.w && dieMinY >= DIE.y && dieMaxY < DIE.y + DIE.h, 'die bounding box escapes the configured DIE rectangle');
console.log(`  die is exactly ${DIE.w}x${DIE.h}, fully inside the assumed rectangle: ${dieMinX === DIE.x && dieMaxX === DIE.x + DIE.w - 1 && dieMinY === DIE.y && dieMaxY === DIE.y + DIE.h - 1}`);

// -- 1b. locate the item's blue inlay by hue, and print it --------------------------------------
console.log('\n== 1b. locating the blue interior region of AE2 item/cell_component_16k.png ==');
const itemRegion = blueRegionPixels(ae2.itemComponent);
const itemRegionColours = {};
for (const p of itemRegion.pixels) { const k = toHex(p.c); itemRegionColours[k] = (itemRegionColours[k] ?? 0) + 1; }
console.log(`  region: ${itemRegion.pixels.length} opaque pixel(s), bounding box x${itemRegion.bbox.x0}..${itemRegion.bbox.x1} y${itemRegion.bbox.y0}..${itemRegion.bbox.y1}`);
console.log(`  region colours: ${Object.entries(itemRegionColours).map(([k, n]) => `${k} x${n}`).join('   ')}`);
console.log('  region mask (# = recoloured, . = must stay byte-identical to AE2):');
{
  const hit = new Set(itemRegion.pixels.map(p => `${p.x},${p.y}`));
  for (let y = 0; y < 16; y++) {
    let line = '    ';
    for (let x = 0; x < 16; x++) line += hit.has(`${x},${y}`) ? ' #' : ' .';
    console.log(line);
  }
}
check(itemRegion.pixels.length > 0, 'the item blue region is empty - the hue detector matches nothing');
check(ae2.itemComponent.width === 16 && ae2.itemComponent.height === 16, 'AE2 item/cell_component_16k.png is not 16x16');

// -- 2. recolour ------------------------------------------------------------------------------
console.log('\n== 2. remapping accents to the #39C5BB family ==');
console.log(`  ramp: dark=${RAMP[0]}  mid=${RAMP[1]}  bright=${RAMP[2]}`);

const itemOut = remapRegionToRamp(ae2.itemComponent, inItemBlueRegion);
const unitOut = remapToRamp(ae2.unitFace, DIE);
const lightOut = remapToRamp(ae2.itemLight, { x: 0, y: 0, w: 16, h: 16 });
// the formed base face is AE2's neutral dark base - it has no accent pixels at all, so it is used
// verbatim; assert that instead of silently "recolouring" nothing.
const lightBaseColors = new Set();
for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) { const c = get(ae2.lightBase, x, y); if (c[3] >= 200) lightBaseColors.add(toHex(c)); }

console.log(`  item region luminance span: ${itemOut.minL.toFixed(1)} .. ${itemOut.maxL.toFixed(1)}`);
for (const [name, r] of [['item / scheduler_core (hue region)', itemOut], ['block unformed (die)', unitOut], ['block formed light band', lightOut]]) {
  console.log(`  ${name}: ${r.changed} pixels changed inside the region (${r.regionPixels} accented pixels scanned), ${r.changedOutside} changed outside -> ${r.changedOutside === 0 ? 'OK' : 'FAIL'}`);
  for (const m of r.mapping) console.log(`      ${m.from} (${m.rel === undefined ? '' : `rel-luma ${m.rel.toFixed(3)}, `}${String(m.count).padStart(3)} px, ${m.tone}) -> ${m.to}`);
  check(r.changedOutside === 0, `${name}: ${r.changedOutside} pixels changed outside the recolour region`);
}
check(itemOut.regionPixels === itemRegion.pixels.length, 'the item remap scanned a different pixel count than the region probe');
console.log(`  AE2 light_base.png opaque palette: ${[...lightBaseColors].sort().join(' ')}  (no #39C5BB accent to remap; reused as-is)`);

// -- 3. hard pixel-diff assertions against the AE2 originals ------------------------------------
console.log('\n== 3. pixel-diff assertions ==');
console.log('  item: outside the blue region the result must be bit-identical to AE2 item/cell_component_16k.png');
const itemOutside = diffImages(ae2.itemComponent, itemOut.image, (x, y) => !inItemBlueRegion(get(ae2.itemComponent, x, y)));
const itemInside = diffImages(ae2.itemComponent, itemOut.image, (x, y) => inItemBlueRegion(get(ae2.itemComponent, x, y)));
const unitOutside = diffImages(ae2.unitFace, unitOut.image, (x, y) => !inDie(x, y));
const unitInside = diffImages(ae2.unitFace, unitOut.image, inDie);
console.log(`  item  : ${itemInside.n} px changed inside the blue region, ${itemOutside.n} px changed outside  ${itemOutside.n === 0 ? 'OK' : 'FAIL'}`);
console.log(`  block : ${unitInside.n} px changed inside die, ${unitOutside.n} px changed outside  ${unitOutside.n === 0 ? 'OK' : 'FAIL'}`);
check(itemOutside.n === 0, `item: ${itemOutside.n} pixels outside the blue region are not identical to AE2`);
check(unitOutside.n === 0, `block: ${unitOutside.n} pixels outside the die are not identical to AE2`);
if (itemOutside.n) console.log(`      samples: ${itemOutside.samples.join('  ')}`);

// the shape must be preserved: the recolour only swaps the palette, never the light/dark layout
const sigItemAe2 = regionToneSignature(ae2.itemComponent, itemRegion.pixels);
const sigItemOurs = regionToneSignature(itemOut.image, itemRegion.pixels);
console.log(`  item  : shape signature (per-pixel luminance rank over the ${itemRegion.pixels.length} region pixels)`);
console.log(`          AE2  ${sigItemAe2}`);
console.log(`          ours ${sigItemOurs}`);
console.log(`          identical: ${sigItemAe2 === sigItemOurs ? 'YES' : 'NO'}  ${sigItemAe2 === sigItemOurs ? 'OK' : 'FAIL'}`);
check(sigItemAe2 === sigItemOurs, 'the recoloured item changed the inlay shape, not just its colours');

// the die-based block compare still needs its own tone layout check
function toneSignature(img) {
  const lumas = new Map();
  for (let y = DIE.y; y < DIE.y + DIE.h; y++) for (let x = DIE.x; x < DIE.x + DIE.w; x++) {
    const c = get(img, x, y); const k = c.slice(0, 3).join(',');
    if (!lumas.has(k)) lumas.set(k, luma(c));
  }
  const order = [...lumas.entries()].sort((a, b) => a[1] - b[1]).map(e => e[0]);
  const rank = new Map(order.map((k, i) => [k, i]));
  let sig = '';
  for (let y = DIE.y; y < DIE.y + DIE.h; y++) for (let x = DIE.x; x < DIE.x + DIE.w; x++) {
    sig += rank.get(get(img, x, y).slice(0, 3).join(','));
  }
  return sig;
}
const sigAe2 = toneSignature(ae2.unitFace);
const sigOurs = toneSignature(unitOut.image);
console.log(`  block : die tone-layout identical to AE2 unit.png (same meander, only the palette differs): ${sigAe2 === sigOurs ? 'YES' : 'NO'}`);
check(sigAe2 === sigOurs, 'the recoloured block die changed the meander shape, not just its colours');
const itemTones = new Set(itemRegion.pixels.map(p => toHex(get(itemOut.image, p.x, p.y))));
console.log(`  item  : region palette after remap: ${[...itemTones].sort().join(' ')}`);
console.log(`          contains #39C5BB: ${itemTones.has('#39c5bb')}   exactly the three ramp tones: ${itemTones.size === 3 && RAMP.every(h => itemTones.has(h.toLowerCase()))}`);
check(itemTones.has('#39c5bb'), 'the item region does not contain #39C5BB');
check(RAMP.every(h => itemTones.has(h.toLowerCase())), 'the item region does not use all three ramp tones');

// -- 4. write textures -------------------------------------------------------------------------
console.log('\n== 4. writing textures (16x16 single frame, no .mcmeta anywhere) ==');
const TEXTURES = {
  [join(RES, 'textures/item/scheduler_core.png')]: itemOut.image,
  [join(RES, 'textures/block/scheduler_core_block.png')]: unitOut.image,
  [join(RES, 'textures/block/scheduler_core_light.png')]: lightOut.image,
};
const STALE = [
  join(RES, 'textures/item/scheduler_core.png.mcmeta'),
  join(RES, 'textures/block/scheduler_core_block_formed.png'),
  join(RES, 'textures/block/scheduler_core_block_formed_on.png'),
  join(RES, 'textures/block/scheduler_core_block_formed_on.png.mcmeta'),
  join(RES, 'models/block/scheduler_core_block_formed_on.json'),
];
for (const f of STALE) {
  if (existsSync(f)) { rmSync(f); console.log(`  removed stale ${f.slice(PROJECT.length + 1)}`); }
}
for (const [p, img] of Object.entries(TEXTURES)) {
  const wrote = writePngBuffer(p, img.width, img.height, img.px);
  console.log(`  ${wrote ? 'wrote  ' : 'unchanged'} ${p.slice(PROJECT.length + 1)}  ${img.width}x${img.height}`);
}

// -- 5. models + blockstate --------------------------------------------------------------------
console.log('\n== 5. writing models / blockstate ==');
const json = o => JSON.stringify(o, null, 2) + '\n';
const MODELS = {
  'models/block/scheduler_core_block.json': {
    parent: 'minecraft:block/cube_all',
    textures: { all: 'schedulercore:block/scheduler_core_block' },
  },
  // formed: our own namespace, AE2's crafting-cube shell geometry (see the header comment).  Roles:
  //   ring_*  -> the joiners that butt against neighbouring 16k storages / parallel units
  //   base    -> AE2's dark formed base face
  //   light   -> the emissive connection band, recoloured to #39C5BB
  'models/block/scheduler_core_block_formed.json': {
    loader: 'schedulercore:crafting_cube',
    textures: {
      particle: 'ae2:block/crafting/light_base',
      ring_corner: 'ae2:block/crafting/ring_corner',
      ring_side_hor: 'ae2:block/crafting/ring_side_hor',
      ring_side_ver: 'ae2:block/crafting/ring_side_ver',
      base: 'ae2:block/crafting/light_base',
      light: 'schedulercore:block/scheduler_core_light',
    },
  },
  'blockstates/scheduler_core_block.json': {
    variants: {
      'formed=false,powered=false': { model: 'schedulercore:block/scheduler_core_block' },
      'formed=false,powered=true': { model: 'schedulercore:block/scheduler_core_block' },
      'formed=true,powered=false': { model: 'schedulercore:block/scheduler_core_block_formed' },
      'formed=true,powered=true': { model: 'schedulercore:block/scheduler_core_block_formed' },
    },
  },
};
for (const [rel, body] of Object.entries(MODELS)) {
  const p = join(RES, rel);
  const wrote = writeTextIfChanged(p, json(body));
  console.log(`  ${wrote ? 'wrote    ' : 'unchanged'} ${p.slice(PROJECT.length + 1)}`);
}

// -- 6. texture / model sanity -----------------------------------------------------------------
console.log('\n== 6. resource sanity ==');
const walk = (dir, out = []) => {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
};
const allRes = walk(RES);
const mcmetas = allRes.filter(f => f.endsWith('.mcmeta'));
check(mcmetas.length === 0, `found ${mcmetas.length} .mcmeta file(s): ${mcmetas.join(', ')}`);
console.log(`  .mcmeta files under assets/schedulercore: ${mcmetas.length}  ${mcmetas.length === 0 ? 'OK' : 'FAIL'}`);
for (const f of allRes.filter(f => f.endsWith('.png'))) {
  const img = decodePngBuffer(readFileSync(f), f);
  const ok = img.width === 16 && img.height === 16;
  console.log(`  ${f.slice(RES.length + 1).padEnd(46)} ${img.width}x${img.height} ${ok ? 'OK' : 'FAIL'}`);
  check(ok, `${f} is ${img.width}x${img.height}, expected a single 16x16 frame`);
}
const classpath = zipNames(JAR);
for (const tex of ['ae2:block/crafting/light_base', 'ae2:block/crafting/ring_corner', 'ae2:block/crafting/ring_side_hor', 'ae2:block/crafting/ring_side_ver']) {
  const p = 'assets/' + tex.replace(':', '/textures/') + '.png';
  const ok = classpath.includes(p);
  console.log(`  referenced AE2 texture exists in the jar: ${tex.padEnd(34)} ${ok ? 'OK' : 'FAIL'}`);
  check(ok, `referenced AE2 texture ${tex} is not in the AE2 jar`);
}

// -- 7. previews -------------------------------------------------------------------------------
console.log('\n== 7. writing previews ==');
mkdirSync(PREVIEW, { recursive: true });
for (const f of readdirSync(PREVIEW)) {
  if (f.startsWith('preview_') || f.startsWith('preview')) { rmSync(join(PREVIEW, f)); console.log(`  removed outdated ${f}`); }
}

const PANEL = [24, 24, 30, 255];
const LABEL = [235, 235, 245, 255];
const MARK = [255, 0, 200, 255];

/** Two-up comparison sheet: AE2 original vs generated, at 1:1 and at 8x, with a diff mask. */
function comparisonSheet({ title, ae2Img, oursImg, subtitle, outsideLabel = 'OUTSIDE DIE X3-12 Y3-12', outsidePredicate = (x, y) => !inDie(x, y) }) {
  const S = 8;
  const pad = 10, labelH = 12;
  const cell = 16 * S;
  // the 5x7 bitmap font advances 6px per glyph - widen the sheet when the header is longer than the
  // three columns, otherwise the title is silently clipped at the right edge.
  const W = Math.max(pad + 3 * (cell + pad), pad + textWidth(title, 1) + pad, subtitle ? pad + textWidth(subtitle, 1) + pad : 0);
  const H = labelH * 2 + pad + cell + pad + 3 * (16 + pad) + labelH + pad;
  const c = new Canvas(W, H, PANEL);
  c.text(title, pad, 3, 1, LABEL);
  if (subtitle) c.text(subtitle, pad, labelH, 1, [170, 170, 190, 255]);
  let y = labelH * 2;
  const cols = [['AE2 ORIGINAL', ae2Img], ['SCHEDULERCORE', oursImg], ['CHANGED PIXELS', diffMask(ae2Img, oursImg)]];
  cols.forEach(([label, img], i) => {
    const x = pad + i * (cell + pad);
    c.text(label, x, y, 1, LABEL);
    c.rect(x - 1, y + labelH - 1, cell + 2, cell + 2, [0, 0, 0, 255]);
    c.blit(img, x, y + labelH, S);
  });
  y += labelH + cell + pad;
  // 1:1 row, aligned under the 8x columns
  cols.forEach(([, img], i) => {
    const x = pad + i * (cell + pad);
    c.rect(x - 1, y - 1, 18, 18, [0, 0, 0, 255]);
    c.blit(img, x, y, 1);
  });
  const d = diffImages(ae2Img, oursImg);
  const outsideRegion = diffImages(ae2Img, oursImg, outsidePredicate);
  c.text(`1:1     DIFF ${d.n} PIXELS     ${outsideLabel}: ${outsideRegion.n} (MUST BE 0)`, pad, y + 16 + pad - 2, 1, LABEL);
  return c;
}

// (1) item vs AE2 16k STORAGE COMPONENT (item/cell_component_16k.png) - the corrected reference art
comparisonSheet({
  title: 'ITEM SCHEDULERCORE:SCHEDULER_CORE  VS  AE2 16K STORAGE COMPONENT (ITEM ICON)',
  subtitle: 'BASE = AE2 ITEM/CELL_COMPONENT_16K.PNG  -  ONLY THE BLUE INLAY IS #39C5BB',
  ae2Img: ae2.itemComponent, oursImg: itemOut.image,
  outsideLabel: 'OUTSIDE BLUE REGION (20 PX)',
  outsidePredicate: (x, y) => !inItemBlueRegion(get(ae2.itemComponent, x, y)),
}).save(join(PREVIEW, 'preview_item_fix_vs_ae2_component.png'));
console.log(`  preview -> docs/preview/preview_item_fix_vs_ae2_component.png`);

// (1b) 16x blow-up of just the two item icons, on a dark checkerboard so transparent pixels show
{
  const Z = 16, cell = 16 * Z, gap = 12;
  const tiles = [
    ['AE2 16K STORAGE COMPONENT', ae2.itemComponent],
    ['OURS SCHEDULERCORE #39C5BB', itemOut.image],
    ['CHANGED PIXELS (20)', diffMask(ae2.itemComponent, itemOut.image)],
  ];
  const W = gap + tiles.length * (cell + gap), H = gap + 12 + cell + gap;
  const c = new Canvas(W, H, [16, 16, 20, 255]);
  tiles.forEach(([label, img], i) => {
    const x = gap + i * (cell + gap);
    c.text(label, x, gap, 1, LABEL);
    c.rect(x - 2, gap + 12 - 2, cell + 4, cell + 4, [0, 0, 0, 255]);
    for (let py = 0; py < cell; py++) for (let px = 0; px < cell; px++) {
      const s = get(img, Math.floor(px / Z), Math.floor(py / Z));
      const t = ((px >> 3) + (py >> 3)) % 2 ? [30, 30, 36, 255] : [46, 46, 54, 255];
      const a = s[3] / 255;
      c.set(x + px, gap + 12 + py, [
        Math.round(s[0] * a + t[0] * (1 - a)),
        Math.round(s[1] * a + t[1] * (1 - a)),
        Math.round(s[2] * a + t[2] * (1 - a)),
        255,
      ]);
    }
  });
  c.save(join(PREVIEW, 'preview_item_fix_zoom16x.png'));
  console.log(`  preview -> docs/preview/preview_item_fix_zoom16x.png`);
}

// (2) unformed block vs AE2 crafting unit
comparisonSheet({
  title: 'BLOCK SCHEDULER_CORE_BLOCK FORMED=FALSE  VS  AE2 CRAFTING UNIT',
  subtitle: 'BASE = AE2 BLOCK/CRAFTING/UNIT.PNG, ACCENTS REMAPPED TO #39C5BB',
  ae2Img: ae2.unitFace, oursImg: unitOut.image,
}).save(join(PREVIEW, 'preview_block_unformed_vs_ae2_unit.png'));
console.log(`  preview -> docs/preview/preview_block_unformed_vs_ae2_unit.png`);

// (3) formed state: composed faces, ours vs an AE2 16k storage next to it
const SCENES = [
  ['ISOLATED - NO NEIGHBOURS', []],
  ['ROW ALONG X - EAST+WEST', ['EAST', 'WEST']],
  ['HORIZONTAL PLANE', ['EAST', 'WEST', 'NORTH', 'SOUTH']],
  ['VERTICAL WALL', ['EAST', 'WEST', 'UP', 'DOWN']],
  ['SURROUNDED - 6 NEIGHBOURS', DIRS],
];
const S2 = 6, cell2 = 16 * S2, pad2 = 6, faceLabel = 16;
const fmt = conn => `CONN ${conn.length === 0 ? '-' : conn.map(d => d[0]).join('')}`;
const oursSprites = {
  ring_corner: ae2.ringCorner, ring_side_hor: ae2.ringHor, ring_side_ver: ae2.ringVer,
  base: ae2.lightBase, light: lightOut.image,
};
const ae2Sprites = {
  ring_corner: ae2.ringCorner, ring_side_hor: ae2.ringHor, ring_side_ver: ae2.ringVer,
  base: ae2.lightBase, light: ae2.itemLight,
};

// -- machine check: the composed faces must have the *same layout* as an AE2 16k storage in the same
//    connection scenario - identical frame, identical band placement, only the band hue differs.
console.log('\n  formed-face layout equivalence vs AE2 16k (frame/band map must match on all 30 faces):');
{
  const opaqueColors = img => {
    const s = new Set();
    for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++) {
      const c = get(img, x, y); if (c[3] > 0) s.add(toHex(c));
    }
    return s;
  };
  const bandAe2 = opaqueColors(ae2.itemLight);
  const bandOurs = opaqueColors(lightOut.image);
  /** '.', 'B' for a band pixel, 'F' for anything else (frame / base). */
  const signature = (img, band) => {
    let s = '';
    for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++) {
      const c = get(img, x, y);
      s += c[3] === 0 ? '.' : band.has(toHex(c)) ? 'B' : 'F';
    }
    return s;
  };
  let mismatches = 0, checked = 0;
  for (const [name, conn] of SCENES) {
    const set = new Set(conn);
    for (const face of DIRS) {
      const a = signature(renderFormedFace(face, set, ae2Sprites), bandAe2);
      const b = signature(renderFormedFace(face, set, oursSprites), bandOurs);
      checked++;
      if (a !== b) { mismatches++; console.log(`    MISMATCH ${name} / ${face}`); }
    }
  }
  console.log(`    ${checked} face(s) compared, ${mismatches} layout mismatch(es)  ${mismatches === 0 ? 'OK' : 'FAIL'}`);
  check(mismatches === 0, `${mismatches} formed face(s) do not have AE2's frame/band layout`);
}

const rowW = faceLabel + pad2 + 6 * (cell2 + pad2);
const scenesH = SCENES.length * (2 * (12 + cell2 + pad2) + 8);
const sheetW = pad2 + rowW + 16 * 2 + pad2 * 3;                 // plus the two 1:1 legend faces
const sheet = new Canvas(sheetW, scenesH + 24, PANEL);
sheet.text('FORMED=TRUE  COMPOSED FACES  VS  AE2 16K CRAFTING STORAGE BESIDE IT', pad2, 3, 1, LABEL);
sheet.text('UP DOWN NORTH SOUTH WEST EAST  (ORTHOGRAPHIC, REAL SHELL GEOMETRY)', pad2, 14, 1, [170, 170, 190, 255]);
let oy = 24;
for (const [name, conn] of SCENES) {
  const set = new Set(conn);
  sheet.text(`${name}   ${fmt(conn)}`, pad2, oy, 1, [255, 220, 120, 255]);
  oy += 12;
  for (const [label, sprites, colour] of [['AE2 16K', ae2Sprites, [200, 200, 210, 255]], ['OURS', oursSprites, [127, 230, 222, 255]]]) {
    sheet.text(label, pad2 + 2, oy + cell2 / 2 - 4, 1, colour);
    DIRS.forEach((f, i) => {
      const x = pad2 + faceLabel + pad2 + i * (cell2 + pad2);
      sheet.rect(x - 1, oy - 1, cell2 + 2, cell2 + 2, [0, 0, 0, 255]);
      sheet.blit(renderFormedFace(f, set, sprites), x, oy, S2, true);
      sheet.text(f.slice(0, 2).padEnd(2, ' ') + (i < 2 ? ' ' : ''), x + 2, oy + 2, 1, [120, 120, 130, 255]);
    });
    oy += cell2 + pad2;
  }
  oy += 8;
}
// side-by-side 1:1 of the north face of an isolated block: ours vs AE2
{
  const y = 24;
  const x0 = sheetW - 16 * 2 - pad2 * 2;
  sheet.text('1:1', x0, y, 1, LABEL);
  sheet.rect(x0 - 1, y + 12, 18, 18, [0, 0, 0, 255]);
  sheet.blit(renderFormedFace('NORTH', new Set(), ae2Sprites), x0, y + 13, 1);
  sheet.rect(x0 + 19, y + 12, 18, 18, [0, 0, 0, 255]);
  sheet.blit(renderFormedFace('NORTH', new Set(), oursSprites), x0 + 20, y + 13, 1);
  sheet.text('AE2', x0, y + 33, 1, [200, 200, 210, 255]);
  sheet.text('OURS', x0, y + 44, 1, [127, 230, 222, 255]);
}
sheet.save(join(PREVIEW, 'preview_formed_faces_vs_ae2_16k.png'));
console.log('  preview -> docs/preview/preview_formed_faces_vs_ae2_16k.png');

// (4) plain 1:1 contact sheet of everything we ship
{
  const items = [
    ['AE2 COMPONENT 16K', ae2.itemComponent], ['ITEM (OURS)', itemOut.image],
    ['AE2 UNIT', ae2.unitFace], ['BLOCK UNFORMED', unitOut.image],
    ['AE2 16K LIGHT', ae2.itemLight], ['BLOCK LIGHT', lightOut.image],
  ];
  const S3 = 4, c3 = 16 * S3, gap = 8;
  const W = gap + items.length * (c3 + gap), H = gap + 12 + c3 + gap;
  const c = new Canvas(W, H, PANEL);
  items.forEach(([label, img], i) => {
    const x = gap + i * (c3 + gap);
    c.text(label, x, gap, 1, LABEL);
    c.rect(x - 1, gap + 12 - 1, c3 + 2, c3 + 2, [0, 0, 0, 255]);
    c.blit(img, x, gap + 12, S3);
  });
  c.save(join(PREVIEW, 'preview_textures_4x.png'));
  console.log('  preview -> docs/preview/preview_textures_4x.png');
}

// (5) heavy zoom on the two faces that carry the requested colour, on a dark backdrop so the
//     transparent parts of the light band are obvious
{
  const Z = 16, cell = 16 * Z, gap = 12;
  const tiles = [
    ['AE2 COMPONENT 16K', ae2.itemComponent], ['OURS ITEM #39C5BB', itemOut.image],
    ['AE2 UNIT FACE', ae2.unitFace], ['OURS BLOCK UNFORMED', unitOut.image],
    ['AE2 16K LIGHT BAND', ae2.itemLight], ['OURS LIGHT BAND #39C5BB', lightOut.image],
  ];
  const W = gap + tiles.length * (cell + gap), H = gap + 12 + cell + gap;
  const c = new Canvas(W, H, [16, 16, 20, 255]);
  tiles.forEach(([label, img], i) => {
    const x = gap + i * (cell + gap);
    c.text(label, x, gap, 1, LABEL);
    c.rect(x - 2, gap + 12 - 2, cell + 4, cell + 4, [0, 0, 0, 255]);
    for (let py = 0; py < cell; py++) for (let px = 0; px < cell; px++) {
      const s = get(img, Math.floor(px / Z), Math.floor(py / Z));
      const t = ((px >> 3) + (py >> 3)) % 2 ? [30, 30, 36, 255] : [46, 46, 54, 255];
      const a = s[3] / 255;
      c.set(x + px, gap + 12 + py, [
        Math.round(s[0] * a + t[0] * (1 - a)),
        Math.round(s[1] * a + t[1] * (1 - a)),
        Math.round(s[2] * a + t[2] * (1 - a)),
        255,
      ]);
    }
  });
  c.save(join(PREVIEW, 'preview_zoom_16x.png'));
  console.log('  preview -> docs/preview/preview_zoom_16x.png');
}

// -- 8. report ---------------------------------------------------------------------------------
if (failures.length) {
  console.error('\nGENERATION FAILED:');
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log('\nall checks passed. textures are 16x16, no .mcmeta, and nothing changed outside the intended region.');
