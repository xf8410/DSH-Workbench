#!/usr/bin/env node
// dsh-glb3d — 纯 node 的 GLB 工具链（不需要 Blender、不需要 Python）
//
// 子命令
//   info    FILE.glb                     部件数、三角面、包围盒、最大部件列表
//   sil     FILE.glb [--bins 200]        逐高度宽度轮廓 + 头身比/肩腰比，输出 JSON
//   fix     IN.glb OUT.glb [--band 0.62:1.27] [--head 1:complete ...] 按高度带缩放顶点
//   compare MODEL.json SHEET.json        模型 vs 三视图规格 的差异表
//
// 设计约束
//   - 只改 POSITION 的浮点数与 accessor.min/max，不动拓扑、不动蒙皮、不动贴图；
//   - 顶点按"自脚底起的归一化高度"落入带内，带间用余弦过渡，避免硬缝；
//   - 写回时 JSON 与 BIN 都按 4 字节对齐，输出可直接导入 Blender / 三方查看器。
import fs from 'node:fs';

const MAGIC = 0x46546c67;

function readGlb(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32LE(0) !== MAGIC) throw new Error('不是 GLB: ' + file);
  const total = buf.readUInt32LE(8);
  let off = 12, json = null, bin = null;
  while (off < total) {
    const len = buf.readUInt32LE(off);
    const type = buf.toString('latin1', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'JSON') json = JSON.parse(data.toString('utf8'));
    else if (type.startsWith('BIN')) bin = data;
    off += 8 + len + ((4 - (len % 4)) % 4);
  }
  if (!json) throw new Error('GLB 缺少 JSON chunk');
  return { json, bin, bytes: buf.length };
}

function writeGlb(file, json, bin) {
  const jsonBuf = Buffer.from(JSON.stringify(json), 'utf8');
  const jsonPad = Buffer.alloc((4 - (jsonBuf.length % 4)) % 4, 0x20);
  const binPad = bin ? Buffer.alloc((4 - (bin.length % 4)) % 4, 0x00) : null;
  const parts = [];
  const total = 12 + 8 + jsonBuf.length + jsonPad.length + (bin ? 8 + bin.length + binPad.length : 0);
  const head = Buffer.alloc(12);
  head.writeUInt32LE(MAGIC, 0); head.writeUInt32LE(2, 4); head.writeUInt32LE(total, 8);
  parts.push(head);
  const jh = Buffer.alloc(8);
  jh.writeUInt32LE(jsonBuf.length + jsonPad.length, 0); jh.writeUInt32LE(0x4e4f534a, 4);
  parts.push(jh, jsonBuf, jsonPad);
  if (bin) {
    const bh = Buffer.alloc(8);
    bh.writeUInt32LE(bin.length + binPad.length, 0); bh.writeUInt32LE(0x004e4942, 4);
    parts.push(bh, bin, binPad);
  }
  fs.writeFileSync(file, Buffer.concat(parts));
}

function positionAccessors(json) {
  const set = new Map();
  for (const mesh of json.meshes || []) {
    for (const prim of mesh.primitives || []) {
      const idx = prim.attributes?.POSITION;
      if (idx !== undefined) set.set(idx, true);
    }
  }
  return [...set.keys()];
}

function accessorView(json, index) {
  const a = json.accessors[index];
  const v = json.bufferViews[a.bufferView];
  const stride = v.byteStride || 12;
  const base = (v.byteOffset || 0) + (a.byteOffset || 0);
  return { a, v, stride, base };
}

function forEachVertex(json, bin, index, fn) {
  const { a, stride, base } = accessorView(json, index);
  for (let i = 0; i < a.count; i++) {
    const p = base + i * stride;
    const x = bin.readFloatLE(p), y = bin.readFloatLE(p + 4), z = bin.readFloatLE(p + 8);
    const r = fn(x, y, z, i);
    if (r) {
      bin.writeFloatLE(r[0], p); bin.writeFloatLE(r[1], p + 4); bin.writeFloatLE(r[2], p + 8);
    }
  }
}

function bounds(json, bin) {
  const box = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
  for (const idx of positionAccessors(json)) {
    forEachVertex(json, bin, idx, (x, y, z) => {
      if (x < box.min[0]) box.min[0] = x; if (x > box.max[0]) box.max[0] = x;
      if (y < box.min[1]) box.min[1] = y; if (y > box.max[1]) box.max[1] = y;
      if (z < box.min[2]) box.min[2] = z; if (z > box.max[2]) box.max[2] = z;
      return null;
    });
  }
  return box;
}

function cmdInfo(file) {
  const { json, bin, bytes } = readGlb(file);
  const meshes = json.meshes || [];
  let tris = 0;
  const parts = [];
  for (const [i, mesh] of meshes.entries()) {
    let t = 0;
    const box = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
    for (const prim of mesh.primitives || []) {
      if (prim.indices !== undefined) t += json.accessors[prim.indices].count / 3;
      const idx = prim.attributes?.POSITION;
      if (idx === undefined) continue;
      const a = json.accessors[idx];
      for (let k = 0; k < 3; k++) {
        if (a.min) box.min[k] = Math.min(box.min[k], a.min[k]);
        if (a.max) box.max[k] = Math.max(box.max[k], a.max[k]);
      }
    }
    tris += t;
    parts.push({ mesh: mesh.name || 'mesh' + i, tris: Math.round(t), size: box.min[0] === Infinity ? null : box.max.map((v, k) => +(v - box.min[k]).toFixed(4)) });
  }
  const b = bounds(json, bin);
  const out = {
    file: file.split('/').pop(), bytes, generator: json.asset?.generator,
    nodes: (json.nodes || []).length, meshes: meshes.length, triangles: Math.round(tris),
    materials: (json.materials || []).length, textures: (json.textures || []).length,
    skins: (json.skins || []).length, animations: (json.animations || []).length,
    bbox: { min: b.min.map((v) => +v.toFixed(4)), max: b.max.map((v) => +v.toFixed(4)), size: b.max.map((v, k) => +(v - b.min[k]).toFixed(4)) },
    parts: parts.sort((x, y) => y.tris - x.tris).slice(0, 20),
  };
  console.log(JSON.stringify(out, null, 1));
}

function profile(json, bin, bins) {
  const b = bounds(json, bin);
  const H = b.max[1] - b.min[1];
  const wMin = new Float64Array(bins).fill(Infinity), wMax = new Float64Array(bins).fill(-Infinity);
  const dMin = new Float64Array(bins).fill(Infinity), dMax = new Float64Array(bins).fill(-Infinity);
  for (const idx of positionAccessors(json)) {
    forEachVertex(json, bin, idx, (x, y, z) => {
      const t = Math.min(bins - 1, Math.max(0, Math.floor(((y - b.min[1]) / H) * bins)));
      if (x < wMin[t]) wMin[t] = x; if (x > wMax[t]) wMax[t] = x;
      if (z < dMin[t]) dMin[t] = z; if (z > dMax[t]) dMax[t] = z;
      return null;
    });
  }
  const wx = (t) => (wMax[t] >= wMin[t] ? wMax[t] - wMin[t] : 0);
  const dz = (t) => (dMax[t] >= dMin[t] ? dMax[t] - dMin[t] : 0);
  const binAt = (fracFromTop) => Math.min(bins - 1, Math.max(0, Math.round((1 - fracFromTop) * (bins - 1))));
  const at = (f) => wx(binAt(f)) / H;
  let shoulder = 0, shoulderAt = 0;
  for (let t = binAt(0.30); t < bins; t++) if (wx(t) > shoulder) { shoulder = wx(t); shoulderAt = 1 - t / (bins - 1); }
  let waist = Infinity, waistAt = 0;
  for (let t = binAt(0.67); t <= binAt(0.42); t++) { const w = wx(t); if (w > 0 && w < waist) { waist = w; waistAt = 1 - t / (bins - 1); } }
  let neck = 0.18, minW = Infinity;
  for (let t = binAt(0.82); t >= binAt(0.94); t--) { const w = wx(t); if (w > 0 && w < minW) { minW = w; neck = 1 - t / (bins - 1); } }
  const headFrac = neck;
  return {
    height: +H.toFixed(4),
    headHeight: +(headFrac * H).toFixed(4),
    headToBody: +(H / (headFrac * H)).toFixed(2),
    shoulderWidth: +(shoulder / H).toFixed(3), shoulderAtFrac: +shoulderAt.toFixed(3),
    waistWidth: +(waist / H).toFixed(3), waistAtFrac: +waistAt.toFixed(3),
    widthProfile: Object.fromEntries([0.05, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.97].map((f) => [f, +at(f).toFixed(3)])),
    depthProfile: Object.fromEntries([0.1, 0.3, 0.5, 0.7, 0.9].map((f) => [f, +(dz(binAt(f)) / H).toFixed(3)])),
  };
}

function cmdSil(file, bins) {
  const { json, bin } = readGlb(file);
  if (!bin) throw new Error('GLB 没有 BIN chunk');
  const p = profile(json, bin, bins);
  console.log(JSON.stringify({ file: file.split('/').pop(), ...p }, null, 1));
  fs.writeFileSync(file.replace(/\.glb$/i, '.silhouette.json'), JSON.stringify(p, null, 1));
}

/** 解析 --scale from:to:factor 形式：from/to 是"自头顶起的高度比例" */
function parseBands(args) {
  const bands = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] !== '--scale') continue;
    const spec = args[++i] || '';
    const m = /^([\d.]+):([\d.]+):([\d.]+)$/.exec(spec);
    if (!m) throw new Error('--scale 需要 from:to:factor，例如 0:0.18:1.19');
    bands.push({ from: Number(m[1]), to: Number(m[2]), factor: Number(m[3]) });
  }
  return bands;
}

function cmdFix(input, output, args) {
  const bands = parseBands(args);
  const shiftY = (() => { const i = args.indexOf('--shift'); return i >= 0 ? Number(args[i + 1]) : 0; })();
  if (!bands.length && !shiftY) throw new Error('没有要给的比例（--scale / --shift）');
  const { json, bin } = readGlb(input);
  if (!bin) throw new Error('GLB 没有 BIN chunk');
  const b = bounds(json, bin);
  const H = b.max[1] - b.min[1];
  const cx = (b.min[0] + b.max[0]) / 2, cz = (b.min[2] + b.max[2]) / 2;
  const falloff = (fracFromTop, band) => {
    const edge = 0.03;                      // 3% 过渡带
    if (fracFromTop <= band.from - edge || fracFromTop >= band.to + edge) return 0;
    if (fracFromTop >= band.from && fracFromTop <= band.to) return 1;
    const d = fracFromTop < band.from ? band.from - fracFromTop : fracFromTop - band.to;
    return 0.5 * (1 + Math.cos((d / edge) * Math.PI));
  };
  let changed = 0;
  for (const idx of positionAccessors(json)) {
    const { a, stride, base } = accessorView(json, idx);
    for (let i = 0; i < a.count; i++) {
      const p = base + i * stride;
      let x = bin.readFloatLE(p), y = bin.readFloatLE(p + 4), z = bin.readFloatLE(p + 8);
      const fracFromTop = 1 - (y - b.min[1]) / H;
      let k = 1;
      for (const band of bands) k += (band.factor - 1) * falloff(fracFromTop, band);
      if (k !== 1) { x = cx + (x - cx) * k; z = cz + (z - cz) * k; changed++; }
      if (shiftY) { const f = bands.length ? Math.max(...bands.map((bd) => falloff(fracFromTop, bd))) : 1; y += shiftY * H * f; }
      bin.writeFloatLE(x, p); bin.writeFloatLE(y, p + 4); bin.writeFloatLE(z, p + 8);
    }
    // 重算 min/max，保持 accessor 语义正确
    const acc = json.accessors[idx];
    const box = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
    for (let i = 0; i < acc.count; i++) {
      const p = base + i * stride;
      for (let k = 0; k < 3; k++) {
        const v = bin.readFloatLE(p + k * 4);
        if (v < box.min[k]) box.min[k] = v;
        if (v > box.max[k]) box.max[k] = v;
      }
    }
    acc.min = box.min; acc.max = box.max;
  }
  writeGlb(output, json, bin);
  console.log(JSON.stringify({ ok: true, input: input.split('/').pop(), output: output.split('/').pop(), bands, shiftY, movedVertices: changed, height: +H.toFixed(4) }, null, 1));
}

function cmdCompare(modelFile, sheetFile) {
  const model = JSON.parse(fs.readFileSync(modelFile, 'utf8'));
  const sheet = JSON.parse(fs.readFileSync(sheetFile, 'utf8'));
  const target = sheet.front || sheet;
  const rows = [
    ['头身比', model.headToBody, target.headToBody, ''],
    ['肩宽/身高', model.shoulderWidth, target.shoulderToHeight, ''],
    ['腰宽/身高', model.waistWidth, target.waistToHeight, ''],
    ['腰位(自头顶)', model.waistAtFrac, target.waistY, ''],
  ];
  const out = rows.map(([label, m, t]) => ({
    metric: label,
    model: m, target: t,
    deltaPct: t ? +(((m - t) / t) * 100).toFixed(1) : null,
  }));
  const widths = Object.keys(target.widthsAt || {}).map((f) => ({
    fracFromTop: Number(f),
    model: model.widthProfile?.[f] ?? null,
    target: +(target.widthsAt[f] / target.bbox.h).toFixed(3),
  }));
  console.log(JSON.stringify({ model: modelFile.split('/').pop(), target: sheetFile.split('/').pop(), summary: out, widthProfile: widths }, null, 1));
}

const [cmd, ...rest] = process.argv.slice(2);
const binsFlag = (() => { const i = rest.indexOf('--bins'); return i >= 0 ? Number(rest[i + 1]) : 200; })();
const pos = rest.filter((a, i) => !a.startsWith('--') && !(rest[i - 1] === '--bins' || rest[i - 1] === '--scale' || rest[i - 1] === '--shift'));
try {
  if (cmd === 'info') cmdInfo(pos[0]);
  else if (cmd === 'sil') cmdSil(pos[0], binsFlag);
  else if (cmd === 'fix') cmdFix(pos[0], pos[1], rest);
  else if (cmd === 'compare') cmdCompare(pos[0], pos[1]);
  else {
    console.error('用法: glb.mjs info|sil|fix|compare FILE [参数]');
    process.exit(2);
  }
} catch (e) {
  console.error('失败: ' + e.message);
  process.exit(1);
}