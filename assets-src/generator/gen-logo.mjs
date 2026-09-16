// One-off: build src/main/resources/schedulercore_logo.png (128x128) from the mod's own block
// texture by nearest-neighbour upscaling. Zero dependencies (Node's built-in zlib only).
//
//   node assets-src/generator/gen-logo.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { deflateSync, inflateSync } from 'node:zlib';

const SRC = 'assets-src/textures/block/scheduler_core_block.png';
const OUT = 'src/main/resources/schedulercore_logo.png';
const SIZE = 128;

// ---- minimal PNG reader (8-bit RGB / RGBA, non-interlaced) ----
function readPng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG');
  let off = 8, w = 0, h = 0, depth = 0, color = 0, idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; color = data[9];
      if (data[12] !== 0) throw new Error('interlaced PNG not supported');
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (depth !== 8) throw new Error('only 8-bit PNG supported');
  const channels = color === 6 ? 4 : color === 2 ? 3 : null;
  if (!channels) throw new Error('only RGB/RGBA PNG supported');

  const raw = inflateSync(Buffer.concat(idat));
  const stride = w * channels;
  const px = Buffer.alloc(h * stride);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[p++];
    const line = raw.subarray(p, p + stride); p += stride;
    const cur = px.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? px.subarray((y - 1) * stride, y * stride) : null;
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0;
      const b = prev ? prev[i] : 0;
      const c = (prev && i >= channels) ? prev[i - channels] : 0;
      let v = line[i];
      switch (filter) {
        case 0: break;
        case 1: v += a; break;
        case 2: v += b; break;
        case 3: v += (a + b) >> 1; break;
        case 4: { const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
                  v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); break; }
        default: throw new Error('bad filter ' + filter);
      }
      cur[i] = v & 0xff;
    }
  }
  return { w, h, channels, px };
}

// ---- minimal PNG writer (8-bit RGBA) ----
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = c ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}
function writePng(w, h, rgba) {
  const stride = w * 4;
  const raw = Buffer.alloc(h * (stride + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---- upscale with hard edges (near-neighbour) ----
const src = readPng(readFileSync(SRC));
if (src.w !== SIZE / 8 || src.h !== SIZE / 8) {
  throw new Error(`expected a ${SIZE / 8}x${SIZE / 8} source texture, got ${src.w}x${src.h}`);
}
const out = Buffer.alloc(SIZE * SIZE * 4);
const scale = SIZE / src.w;
for (let y = 0; y < SIZE; y++) {
  const sy = Math.floor(y / scale);
  for (let x = 0; x < SIZE; x++) {
    const sx = Math.floor(x / scale);
    const s = (sy * src.w + sx) * src.channels;
    const d = (y * SIZE + x) * 4;
    out[d] = src.px[s];
    out[d + 1] = src.px[s + 1];
    out[d + 2] = src.px[s + 2];
    out[d + 3] = src.channels === 4 ? src.px[s + 3] : 255;
  }
}
writeFileSync(OUT, writePng(SIZE, SIZE, out));
console.log(`wrote ${OUT} (${SIZE}x${SIZE})`);
