#!/usr/bin/env node
// imagegen — 生图/改图：POST {BASE}/images/edits（多参考图 multipart）与 /images/generations
//   node imagegen.mjs -p "提示词" [-i a.png -i b.png] [--size 2:3] [--model X] [--out 路径]
//   node imagegen.mjs --models | --ping | --dry-run
// 配置：IMG_KEY / IMG_BASE / IMG_MODEL / IMG_HEADERS / IMG_PROXY / IMG_TLS_VERIFY，
//       可写在同目录 .img.env、$DSH_HOME/.img.env 或 /sdcard/DSH/img/.img.env
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ENV_FILES = [process.env.IMG_ENV, path.join(HERE, '.img.env'), path.join(process.env.DSH_HOME || '', '.img.env'), '/sdcard/DSH/img/.img.env'].filter(Boolean);
const fileEnv = (() => {
  const o = {};
  for (const f of ENV_FILES) {
    try {
      for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
        if (/^\s*#/.test(line)) continue;
        const m = /^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
        if (m && m[2] !== '' && o[m[1]] === undefined) o[m[1]] = m[2].replace(/^['"]|['"]$/g, '');
      }
    } catch { /* 没有就算了 */ }
  }
  return o;
})();
const env = (k, d) => (process.env[k] !== undefined && process.env[k] !== '' ? process.env[k] : fileEnv[k] !== undefined ? fileEnv[k] : d);

const RATIOS = { '1:1': '1024x1024', '1024x1024': '1024x1024', '3:2': '1536x1024', '4:3': '1536x1024', '16:9': '1536x1024', '1536x1024': '1536x1024', '2:3': '1024x1536', '3:4': '1024x1536', '9:16': '1024x1536', '1024x1536': '1024x1536' };
const OK_SIZES = new Set(['1024x1024', '1536x1024', '1024x1536', 'auto']);
const MIME = [['89504e47', 'image/png', '.png'], ['ffd8ff', 'image/jpeg', '.jpg'], ['47494638', 'image/gif', '.gif'], ['52494646', 'image/webp', '.webp']];

function sniff(buf) {
  const hex = buf.subarray(0, 4).toString('hex');
  for (const [sig, mime, ext] of MIME) if (hex.startsWith(sig)) return { mime, ext };
  return { mime: 'application/octet-stream', ext: '.bin' };
}

function argsOf(argv) {
  const a = { images: [], headers: [], n: 1 };
  for (let i = 2; i < argv.length; i++) {
    const k = argv[i], next = () => argv[++i];
    switch (k) {
      case '-p': case '--prompt': a.prompt = next(); break;
      case '--prompt-file': a.promptFile = next(); break;
      case '-i': case '--image': case '--ref': a.images.push(next()); break;
      case '-m': case '--mask': a.mask = next(); break;
      case '-s': case '--size': a.size = next(); break;
      case '-o': case '--out': a.out = next(); break;
      case '--outdir': a.outdir = next(); break;
      case '--model': a.model = next(); break;
      case '--base': a.base = next(); break;
      case '--key': a.key = next(); break;
      case '--n': a.n = Number(next()); break;
      case '--quality': a.quality = next(); break;
      case '--background': a.background = next(); break;
      case '--output-format': a.outputFormat = next(); break;
      case '--moderation': a.moderation = next(); break;
      case '--input-fidelity': a.inputFidelity = next(); break;
      case '-H': case '--header': a.headers.push(next()); break;
      case '--proxy': a.proxy = next(); break;
      case '--timeout': a.timeout = Number(next()); break;
      case '--retries': a.retries = Number(next()); break;
      case '--dry-run': a.dryRun = true; break;
      case '--ping': a.ping = true; break;
      case '--models': a.models = true; break;
      case '-h': case '--help': a.help = true; break;
      default: if (!k.startsWith('-')) a.prompt = a.prompt ? a.prompt + ' ' + k : k; else { console.error('未知参数: ' + k); process.exit(2); }
    }
  }
  return a;
}

const safeName = (p) => {
  const base = path.basename(p).replace(/[\r\n"\\]/g, '_');
  const ascii = base.replace(/[^\x20-\x7e]/g, '_');
  const rfc = encodeURIComponent(base).replace(/['()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
  return `filename="${ascii}"; filename*=UTF-8''${rfc}`;
};

function multipart(fields, files) {
  const b = '----dsh' + crypto.randomBytes(12).toString('hex');
  const parts = [];
  for (const [k, v] of Object.entries(fields)) {
    if (v === undefined || v === null || v === '') continue;
    parts.push(Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`));
  }
  for (const f of files) {
    const buf = fs.readFileSync(f.path);
    parts.push(Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="${f.field}"; ${safeName(f.path)}\r\nContent-Type: ${sniff(buf).mime}\r\n\r\n`));
    parts.push(buf, Buffer.from('\r\n'));
  }
  parts.push(Buffer.from(`--${b}--\r\n`));
  return { body: Buffer.concat(parts), contentType: `multipart/form-data; boundary=${b}` };
}

function request(url, opts) {
  return new Promise((resolve, reject) => {
    const u = new URL(url), lib = u.protocol === 'http:' ? http : https;
    const req = lib.request({
      host: u.hostname, port: u.port || (u.protocol === 'http:' ? 80 : 443), path: u.pathname + u.search,
      method: opts.method, headers: opts.headers, timeout: opts.timeout,
      rejectUnauthorized: env('IMG_TLS_VERIFY', '0') === '1',
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        let body = Buffer.concat(chunks);
        const enc = (res.headers['content-encoding'] || '').toLowerCase();
        try { if (enc === 'gzip') body = zlib.gunzipSync(body); else if (enc === 'br') body = zlib.brotliDecompressSync(body); else if (enc === 'deflate') body = zlib.inflateSync(body); } catch { /* 保持原样 */ }
        resolve({ status: res.statusCode, headers: res.headers, body });
      });
    });
    req.on('timeout', () => req.destroy(new Error('超时 ' + opts.timeout + 'ms')));
    req.on('error', reject);
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function send(url, opts) {
  const retries = opts.retries ?? 2;
  for (let i = 0; ; i++) {
    let res;
    try { res = await request(url, opts); } catch (e) { if (i >= retries) throw e; await sleep(600 * 2 ** i); continue; }
    if (res.status === 429 || res.status >= 500) {
      if (i >= retries) return res;
      const ra = Number(res.headers['retry-after']);
      await sleep(Number.isFinite(ra) && ra > 0 ? Math.min(ra * 1000, 30000) : 900 * 2 ** i);
      continue;
    }
    return res;
  }
}

function saveImages(body, headers, outPath) {
  const ct = headers['content-type'] || '';
  if (/^image\//.test(ct)) {
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    const target = outPath.replace(/\.[^.]+$/, sniff(body).ext);
    fs.writeFileSync(target, body);
    return [{ path: target, bytes: body.length }];
  }
  const json = JSON.parse(body.toString('utf8'));
  if (json.error) throw new Error('API 错误: ' + (json.error.message || JSON.stringify(json.error)));
  const files = [];
  (json.data || []).forEach((it, i) => {
    const p = i === 0 ? outPath : outPath.replace(/\.(\w+)$/, `-${i}.$1`);
    if (it.b64_json) {
      const buf = Buffer.from(it.b64_json, 'base64');
      const real = p.replace(/\.[^.]+$/, sniff(buf).ext);
      fs.mkdirSync(path.dirname(real), { recursive: true });
      fs.writeFileSync(real, buf);
      files.push({ path: real, bytes: buf.length, revised: it.revised_prompt });
    } else if (it.url) files.push({ url: it.url });
  });
  return files;
}

async function main() {
  const a = argsOf(process.argv);
  if (a.help) { console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(0, 7).join('\n')); return; }
  if (a.promptFile) a.prompt = fs.readFileSync(a.promptFile, 'utf8');
  const base = (a.base || env('IMG_BASE', 'https://43.173.105.2/v1')).replace(/\/+$/, '');
  const key = a.key || env('IMG_KEY', '') || env('IMG_TOKEN', '') || env('OPENAI_API_KEY', '');
  const timeout = a.timeout || Number(env('IMG_TIMEOUT', 300000));
  const retries = Number.isFinite(a.retries) ? a.retries : Number(env('IMG_RETRIES', 2));
  const extra = Object.fromEntries(a.headers.map((h) => { const i = h.indexOf(':'); return i > 0 ? [h.slice(0, i).trim(), h.slice(i + 1).trim()] : [h, '']; }));
  const jsonEnv = (() => { try { return JSON.parse(env('IMG_HEADERS', '{}')); } catch { return {}; } })();
  const headers = { 'User-Agent': env('IMG_UA', 'dsh-imagegen/1.0'), 'Accept-Encoding': 'gzip, deflate, br', ...(key ? { Authorization: 'Bearer ' + key } : {}), ...jsonEnv, ...extra };

  if (!key) { console.error('缺少 API Key：写进 .img.env 一行 IMG_KEY=sk-xxx，或 export IMG_KEY=sk-xxx'); process.exit(3); }

  if (a.ping || a.models) {
    const r = await send(`${base}/models`, { method: 'GET', headers: { ...headers, Accept: 'application/json' }, timeout, retries });
    if (r.status !== 200) { console.error('HTTP ' + r.status + ' ' + r.body.toString('utf8').slice(0, 300)); process.exit(5); }
    const ids = (JSON.parse(r.body.toString('utf8')).data || []).map((d) => d.id);
    console.log(JSON.stringify({ ok: true, models: ids.length, image: ids.filter((i) => /image|dall|flux|seedream/i.test(i)) }, null, 1));
    return;
  }
  if (!a.prompt) { console.error('缺少 -p/--prompt'); process.exit(2); }

  const model = a.model || env('IMG_MODEL', 'gpt-image-2.6-sunburst');
  let size = RATIOS[a.size || '1024x1024'] || a.size || '1024x1024';
  if (!OK_SIZES.has(size)) {
    const [w, h] = size.split('x').map(Number);
    size = w > h ? '1536x1024' : h > w ? '1024x1536' : '1024x1024';
  }
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  const outdir = a.outdir || '/sdcard/DCIM/Camera';
  const out = a.out || path.join(outdir, `img-${stamp}-${crypto.randomBytes(3).toString('hex')}.png`);

  let url, body, contentType, parts;
  if (a.images.length) {
    url = `${base}/images/edits`;
    const files = a.images.map((p) => ({ field: a.images.length > 1 ? 'image[]' : 'image', path: p }));
    if (a.mask) files.push({ field: 'mask', path: a.mask });
    const mp = multipart({ model, prompt: a.prompt, n: a.n, size, quality: a.quality, background: a.background, output_format: a.outputFormat, moderation: a.moderation, input_fidelity: a.inputFidelity }, files);
    body = mp.body; contentType = mp.contentType;
    parts = [`${files.length} 张参考图`, `${body.length} B`];
  } else {
    url = `${base}/images/generations`;
    body = Buffer.from(JSON.stringify({ model, prompt: a.prompt, n: a.n, size, quality: a.quality, background: a.background, output_format: a.outputFormat, moderation: a.moderation }));
    contentType = 'application/json';
    parts = ['JSON', `${body.length} B`];
  }

  if (a.dryRun) {
    console.log(JSON.stringify({ dryRun: true, url, model, size, parts, headers: { ...headers, Authorization: key ? 'Bearer ***' : '(none)' }, out }, null, 1));
    return;
  }
  const t0 = Date.now();
  const res = await send(url, { method: 'POST', headers: { ...headers, 'Content-Type': contentType, 'Content-Length': body.length, Accept: 'application/json' }, body, timeout, retries });
  const ms = Date.now() - t0;
  if (res.status !== 200) {
    console.error(`HTTP ${res.status} (${ms}ms) ${res.headers['x-request-id'] || ''}`);
    console.error(res.body.toString('utf8').slice(0, 600));
    process.exit(5);
  }
  const files = saveImages(res.body, res.headers, out);
  console.log(JSON.stringify({ ok: true, ms, model, size, files, requestId: res.headers['x-request-id'] || null }));
}

main().catch((e) => { console.error('失败: ' + e.message); process.exit(1); });