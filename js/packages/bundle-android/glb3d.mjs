#!/usr/bin/env node
// glb3d — 手机上的 GLB 工具：渲染多视角预览 / 测量比例 / 按高度带改比例，纯 node，无 Blender。
//   node glb.mjs render FILE [OUTDIR] [--yaw 0] [--eight] [--size 640x928]
//   node glb.mjs info   FILE
//   node glb.mjs sil    FILE
//   node glb.mjs fix    IN OUT --scale 0:0.16:1.19:xyz --scale 0.55:0.65:1.27
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const MAGIC = 0x46546c67;
function readGlb(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32LE(0) !== MAGIC) throw new Error('不是 GLB');
  let off = 12, json = null, bin = null;
  const total = buf.readUInt32LE(8);
  while (off < total) {
    const len = buf.readUInt32LE(off), type = buf.toString('latin1', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'JSON') json = JSON.parse(data.toString('utf8'));
    else if (type.startsWith('BIN')) bin = data;
    off += 8 + len + ((4 - (len % 4)) % 4);
  }
  return { json, bin, bytes: buf.length };
}
function writeGlb(file, json, bin) {
  const jb = Buffer.from(JSON.stringify(json), 'utf8');
  const jp = Buffer.alloc((4 - (jb.length % 4)) % 4, 0x20);
  const bp = bin ? Buffer.alloc((4 - (bin.length % 4)) % 4, 0) : null;
  const total = 12 + 8 + jb.length + jp.length + (bin ? 8 + bin.length + bp.length : 0);
  const h = Buffer.alloc(12); h.writeUInt32LE(MAGIC, 0); h.writeUInt32LE(2, 4); h.writeUInt32LE(total, 8);
  const jh = Buffer.alloc(8); jh.writeUInt32LE(jb.length + jp.length, 0); jh.writeUInt32LE(0x4e4f534a, 4);
  const parts = [h, jh, jb, jp];
  if (bin) { const bh = Buffer.alloc(8); bh.writeUInt32LE(bin.length + bp.length, 0); bh.writeUInt32LE(0x004e4942, 4); parts.push(bh, bin, bp); }
  fs.writeFileSync(file, Buffer.concat(parts));
}
const posAccessors = (json) => {
  const s = new Set();
  for (const m of json.meshes || []) for (const p of m.primitives || []) {
    const i = p.attributes && p.attributes.POSITION;
    if (i !== undefined) s.add(i);
  }
  return [...s];
};
const view = (json, idx) => {
  const a = json.accessors[idx], v = json.bufferViews[a.bufferView];
  return { a, base: (v.byteOffset || 0) + (a.byteOffset || 0), stride: v.byteStride || 12 };
};
function bounds(json, bin) {
  const b = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
  for (const idx of posAccessors(json)) {
    const { a, base, stride } = view(json, idx);
    for (let i = 0; i < a.count; i++) {
      const p = base + i * stride;
      for (let k = 0; k < 3; k++) {
        const v = bin.readFloatLE(p + k * 4);
        if (v < b.min[k]) b.min[k] = v;
        if (v > b.max[k]) b.max[k] = v;
      }
    }
  }
  return b;
}
function cmdInfo(file) {
  const { json, bin, bytes } = readGlb(file);
  const b = bounds(json, bin);
  let tris = 0;
  for (const m of json.meshes || []) for (const p of m.primitives || []) if (p.indices !== undefined) tris += json.accessors[p.indices].count / 3;
  console.log(JSON.stringify({
    file: path.basename(file), bytes, generator: json.asset && json.asset.generator,
    meshes: (json.meshes || []).length, nodes: (json.nodes || []).length, triangles: Math.round(tris),
    materials: (json.materials || []).length, textures: (json.textures || []).length,
    skins: (json.skins || []).length, animations: (json.animations || []).length,
    bbox: { min: b.min.map((v) => +v.toFixed(4)), max: b.max.map((v) => +v.toFixed(4)), size: b.max.map((v, k) => +(v - b.min[k]).toFixed(4)) },
  }, null, 1));
}
function cmdSil(file) {
  const { json, bin } = readGlb(file);
  const b = bounds(json, bin), H = b.max[1] - b.min[1], N = 200;
  const mn = new Float64Array(N).fill(Infinity), mx = new Float64Array(N).fill(-Infinity);
  for (const idx of posAccessors(json)) {
    const { a, base, stride } = view(json, idx);
    for (let i = 0; i < a.count; i++) {
      const p = base + i * stride;
      const x = bin.readFloatLE(p), y = bin.readFloatLE(p + 4);
      const t = Math.min(N - 1, Math.max(0, Math.floor(((y - b.min[1]) / H) * N)));
      if (x < mn[t]) mn[t] = x;
      if (x > mx[t]) mx[t] = x;
    }
  }
  const w = (t) => (mx[t] >= mn[t] ? mx[t] - mn[t] : 0);
  const fromTop = (f) => Math.min(N - 1, Math.max(0, Math.round((1 - f) * (N - 1))));
  let shoulder = 0, shoulderAt = 0;
  for (let t = fromTop(0.30); t < N; t++) if (w(t) > shoulder) { shoulder = w(t); shoulderAt = 1 - t / (N - 1); }
  let waist = Infinity, waistAt = 0;
  for (let t = fromTop(0.58); t <= fromTop(0.33); t++) if (w(t) > 0 && w(t) < waist) { waist = w(t); waistAt = 1 - t / (N - 1); }
  let neck = 0.16, minW = Infinity;
  for (let t = fromTop(0.06); t >= fromTop(0.18); t--) if (w(t) > 0 && w(t) < minW) { minW = w(t); neck = 1 - t / (N - 1); }
  const out = {
    file: path.basename(file), height: +H.toFixed(4),
    headHeight: +(neck * H).toFixed(4), headToBody: +(1 / neck).toFixed(2),
    shoulderWidth: +(shoulder / H).toFixed(3), shoulderAtFrac: +shoulderAt.toFixed(3),
    waistWidth: +(waist / H).toFixed(3), waistAtFrac: +waistAt.toFixed(3),
    widthProfile: Object.fromEntries([0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9].map((f) => [f, +(w(fromTop(f)) / H).toFixed(3)])),
  };
  console.log(JSON.stringify(out, null, 1));
  fs.writeFileSync(file.replace(/\.glb$/i, '.silhouette.json'), JSON.stringify(out, null, 1));
}
function cmdFix(input, output, args) {
  const bands = [];
  const shift = (() => { const i = args.indexOf('--shift'); return i >= 0 ? Number(args[i + 1]) : 0; })();
  for (let i = 0; i < args.length; i++) {
    if (args[i] !== '--scale') continue;
    const m = /^([\d.]+):([\d.]+):([\d.]+)(?::(xyz|y|xz))?$/.exec(args[i + 1] || '');
    if (!m) throw new Error('--scale 需要 from:to:factor[:xyz|y|xz]');
    bands.push({ from: +m[1], to: +m[2], factor: +m[3], axis: m[4] || 'xz' });
  }
  const { json, bin } = readGlb(input);
  const b = bounds(json, bin), H = b.max[1] - b.min[1];
  const cx = (b.min[0] + b.max[0]) / 2, cz = (b.min[2] + b.max[2]) / 2;
  const fall = (f, band) => {
    const e = 0.03;
    if (f <= band.from - e || f >= band.to + e) return 0;
    if (f >= band.from && f <= band.to) return 1;
    const d = f < band.from ? band.from - f : f - band.to;
    return 0.5 * (1 + Math.cos((d / e) * Math.PI));
  };
  let moved = 0;
  for (const idx of posAccessors(json)) {
    const { a, base, stride } = view(json, idx);
    for (let i = 0; i < a.count; i++) {
      const p = base + i * stride;
      let x = bin.readFloatLE(p), y = bin.readFloatLE(p + 4), z = bin.readFloatLE(p + 8);
      const f = 1 - (y - b.min[1]) / H;
      let k = 1, ky = 1, anchor = b.min[1];
      for (const band of bands) {
        const wgt = fall(f, band);
        if (band.axis !== 'y') k += (band.factor - 1) * wgt;
        if (band.axis !== 'xz') { ky += (band.factor - 1) * wgt; anchor = Math.min(anchor, b.min[1] + (1 - band.to) * H); }
      }
      if (k !== 1) { x = cx + (x - cx) * k; z = cz + (z - cz) * k; }
      if (ky !== 1) y = anchor + (y - anchor) * ky;
      if (shift) y += shift * H;
      if (k !== 1 || ky !== 1 || shift) moved++;
      bin.writeFloatLE(x, p); bin.writeFloatLE(y, p + 4); bin.writeFloatLE(z, p + 8);
    }
    const acc = json.accessors[idx];
    const box = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
    for (let i = 0; i < acc.count; i++) {
      const p = base + i * stride;
      for (let k2 = 0; k2 < 3; k2++) {
        const v = bin.readFloatLE(p + k2 * 4);
        if (v < box.min[k2]) box.min[k2] = v;
        if (v > box.max[k2]) box.max[k2] = v;
      }
    }
    acc.min = box.min; acc.max = box.max;
  }
  writeGlb(output, json, bin);
  console.log(JSON.stringify({ ok: true, movedVertices: moved, bands, shift, out: path.basename(output) }, null, 1));
}
function crc32(buf) { let c, crc = 0xffffffff; for (let i = 0; i < buf.length; i++) { c = (crc ^ buf[i]) & 0xff; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1; crc = (crc >>> 8) ^ c; } return (crc ^ 0xffffffff) >>> 0; }
function chunk(type, data) { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type, 'latin1'), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); }
function writePng(file, w, h, rgb) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; rgb.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3); }
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 2;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0))]));
}
function triangles(json, bin) {
  const out = [];
  for (const m of json.meshes || []) for (const p of m.primitives || []) {
    const pi = p.attributes && p.attributes.POSITION;
    if (pi === undefined) continue;
    const { a, base, stride } = view(json, pi);
    const get = (k) => { const q = base + k * stride; return [bin.readFloatLE(q), bin.readFloatLE(q + 4), bin.readFloatLE(q + 8)]; };
    if (p.indices !== undefined) {
      const ia = json.accessors[p.indices], iv = json.bufferViews[ia.bufferView];
      const ib = (iv.byteOffset || 0) + (ia.byteOffset || 0);
      const sz = ia.componentType === 5125 ? 4 : ia.componentType === 5123 ? 2 : 1;
      const rd = (i) => (sz === 4 ? bin.readUInt32LE(ib + i * sz) : sz === 2 ? bin.readUInt16LE(ib + i * sz) : bin.readUInt8(ib + i * sz));
      for (let i = 0; i + 2 < ia.count; i += 3) out.push([get(rd(i)), get(rd(i + 1)), get(rd(i + 2))]);
    } else for (let i = 0; i + 2 < a.count; i += 3) out.push([get(i), get(i + 1), get(i + 2)]);
  }
  return out;
}
function cmdRender(file, outDir, args) {
  const { json, bin } = readGlb(file);
  const sizeArg = (() => { const i = args.indexOf('--size'); return i >= 0 ? args[i + 1] : '640x928'; })();
  const [W, H] = sizeArg.split('x').map(Number);
  const eight = args.includes('--eight');
  const yawOnly = (() => { const i = args.indexOf('--yaw'); return i >= 0 ? Number(args[i + 1]) : null; })();
  const b = bounds(json, bin), tris = triangles(json, bin);
  fs.mkdirSync(outDir, { recursive: true });
  const views = yawOnly !== null ? [['yaw' + yawOnly, yawOnly]]
    : eight ? [0, 45, 90, 135, 180, 225, 270, 315].map((y) => [String(y), y])
    : [['front', 0], ['side', 90], ['back', 180]];
  const files = [];
  const cx = (b.min[0] + b.max[0]) / 2, cz = (b.min[2] + b.max[2]) / 2;
  for (const [name, yaw] of views) {
    const r = (yaw * Math.PI) / 180, cs = Math.cos(r), sn = Math.sin(r);
    const proj = (p) => { const x = p[0] - cx, z = p[2] - cz; return [x * cs - z * sn, p[1], -(x * sn + z * cs)]; };
    let mnx = Infinity, mxx = -Infinity, mny = Infinity, mxy = -Infinity;
    for (const t of tris) for (const p of t) { const q = proj(p); if (q[0] < mnx) mnx = q[0]; if (q[0] > mxx) mxx = q[0]; if (q[1] < mny) mny = q[1]; if (q[1] > mxy) mxy = q[1]; }
    const sc = Math.min(W / (mxx - mnx || 1), H / (mxy - mny || 1)) * 0.92;
    const ox = (W - (mxx - mnx) * sc) / 2, oy = (H - (mxy - mny) * sc) / 2;
    const img = Buffer.alloc(W * H * 3, 255), zb = new Float32Array(W * H).fill(-Infinity);
    const L = [0.4, 0.7, 0.6], ln = Math.hypot(...L);
    for (const [p0, p1, p2] of tris) {
      const q0 = proj(p0), q1 = proj(p1), q2 = proj(p2);
      const x0 = (q0[0] - mnx) * sc + ox, y0 = oy + (mxy - q0[1]) * sc;
      const x1 = (q1[0] - mnx) * sc + ox, y1 = oy + (mxy - q1[1]) * sc;
      const x2 = (q2[0] - mnx) * sc + ox, y2 = oy + (mxy - q2[1]) * sc;
      const area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
      if (Math.abs(area) < 0.02) continue;
      const ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
      const vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
      const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      const nl = Math.hypot(nx, ny, nz) || 1;
      const shade = Math.max(0.12, Math.abs((nx * L[0] + ny * L[1] + nz * L[2]) / (nl * ln)));
      const col = Math.round(60 + 185 * shade);
      const minX = Math.max(0, Math.floor(Math.min(x0, x1, x2))), maxX = Math.min(W - 1, Math.ceil(Math.max(x0, x1, x2)));
      const minY = Math.max(0, Math.floor(Math.min(y0, y1, y2))), maxY = Math.min(H - 1, Math.ceil(Math.max(y0, y1, y2)));
      const inv = 1 / area;
      for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
        const px = x + 0.5, py = y + 0.5;
        const w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
        const w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
        const w2 = 1 - w0 - w1;
        if (w0 < 0 || w1 < 0 || w2 < 0) continue;
        const d = w0 * q0[2] + w1 * q1[2] + w2 * q2[2], o = y * W + x;
        if (d <= zb[o]) continue;
        zb[o] = d;
        const c = o * 3; img[c] = col; img[c + 1] = col; img[c + 2] = col;
      }
    }
    const out = path.join(outDir, `view-${name}.png`);
    writePng(out, W, H, img);
    files.push(out);
  }
  console.log(JSON.stringify({ ok: true, triangles: tris.length, size: [W, H], files }, null, 1));
}

const [cmd, ...rest] = process.argv.slice(2);
const pos = rest.filter((a, i) => !a.startsWith('--') && !['--size', '--yaw', '--scale', '--shift'].includes(rest[i - 1]));
try {
  if (cmd === 'info') cmdInfo(pos[0]);
  else if (cmd === 'sil') cmdSil(pos[0]);
  else if (cmd === 'fix') cmdFix(pos[0], pos[1], rest);
  else if (cmd === 'render') cmdRender(pos[0], pos[1] || path.join(path.dirname(path.resolve(pos[0])), 'preview'), rest);
  else { console.error('用法: glb.mjs render|info|sil|fix FILE ...'); process.exit(2); }
} catch (e) { console.error('失败: ' + e.message); process.exit(1); }