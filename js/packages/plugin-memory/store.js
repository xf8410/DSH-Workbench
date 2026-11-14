/**
 * 记忆存储：活跃记忆一张「便利贴」+ 一组已保存记忆文件。
 *
 * 目录约定（全部在 $DSH_HOME/memory 下，不进工作区，不进仓库）：
 *   active.md            活跃记忆正文，每轮注入进对话
 *   saved/<slug>.md      已保存记忆，frontmatter 带 title/description
 *
 * 设计目标（对齐 agora 的记忆模型）：
 * - 活跃记忆保持简短，因为它每轮都进请求；
 * - 已保存记忆按需检索，靠 title/description 命中；
 * - 全部落盘为纯文本，用户可以用文件管理器直接看、直接改。
 *
 * @module @dsh-android/plugin-memory/store
 */
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { resolveDshHome } from "@deepseek-ai/dsh-home-paths";

const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
const MAX_MEMORY_BYTES = 128 * 1024;
const MAX_ACTIVE_BYTES = 16 * 1024;
const MAX_TITLE = 120;
const MAX_DESCRIPTION = 500;

function memoryRoot() {
  return process.env.DSH_MEMORY_DIR || join(resolveDshHome(), "memory");
}

export function memoryPaths() {
  const root = memoryRoot();
  return { root, active: join(root, "active.md"), saved: join(root, "saved") };
}

export function memoryHomeDisplay() {
  return "$DSH_HOME/memory";
}

async function ensureDirs() {
  const paths = memoryPaths();
  await mkdir(paths.saved, { recursive: true });
  return paths;
}

export function validSlug(slug) {
  return typeof slug === "string" && SLUG_RE.test(slug);
}

/** 从标题生成 slug；全中文标题退化为时间戳，保证始终合法。 */
export function slugFromTitle(title) {
  const base = String(title || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  if (base.length >= 2) return base;
  return `mem-${Date.now().toString(36)}`;
}

function parseFrontmatter(text) {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!match) return { meta: {}, body: text };
  const meta = {};
  for (const line of match[1].split(/\r?\n/)) {
    const kv = /^([A-Za-z][A-Za-z0-9]*):\s*(.*)$/.exec(line);
    if (!kv) continue;
    let value = kv[2].trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    meta[kv[1]] = value;
  }
  return { meta, body: text.slice(match[0].length) };
}

function renderFrontmatter(meta, body) {
  const lines = ["---"];
  for (const [key, value] of Object.entries(meta)) {
    if (value === undefined || value === null || value === "") continue;
    const flat = String(value).replace(/\r?\n/g, " ");
    lines.push(`${key}: ${flat}`);
  }
  lines.push("---", "");
  return lines.join("\n") + body;
}

export async function readActive() {
  const paths = memoryPaths();
  try {
    const text = await readFile(paths.active, "utf8");
    return { exists: true, content: text.trim(), bytes: Buffer.byteLength(text) };
  } catch (error) {
    if (error.code === "ENOENT") return { exists: false, content: "", bytes: 0 };
    throw error;
  }
}

export async function writeActive(content) {
  const text = String(content ?? "");
  const bytes = Buffer.byteLength(text);
  if (bytes > MAX_ACTIVE_BYTES) {
    throw new Error(`活跃记忆过大（${bytes} 字节），上限 ${MAX_ACTIVE_BYTES} 字节；它每轮都进请求，请精简`);
  }
  const paths = await ensureDirs();
  await writeFile(paths.active, text, "utf8");
  return { bytes, path: "$DSH_HOME/memory/active.md" };
}

export async function listSaved() {
  const paths = await ensureDirs();
  let names;
  try {
    names = await readdir(paths.saved);
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
  const out = [];
  for (const name of names.sort()) {
    if (!name.endsWith(".md")) continue;
    const slug = name.slice(0, -3);
    if (!validSlug(slug)) continue;
    try {
      const text = await readFile(join(paths.saved, name), "utf8");
      const { meta, body } = parseFrontmatter(text);
      out.push({
        slug,
        title: meta.title || slug,
        description: meta.description || "",
        bytes: Buffer.byteLength(body),
        updatedAt: meta.updatedAt || null,
      });
    } catch {
      // 读不了的文件直接跳过，不让单条记忆毁掉整个列表
    }
  }
  return out;
}

export async function readSaved(slug) {
  if (!validSlug(slug)) throw new Error(`记忆名不合法：${slug}（只允许小写字母、数字、连字符）`);
  const paths = memoryPaths();
  const file = join(paths.saved, `${slug}.md`);
  let text;
  try {
    text = await readFile(file, "utf8");
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
  const { meta, body } = parseFrontmatter(text);
  return {
    slug,
    title: meta.title || slug,
    description: meta.description || "",
    content: body.trim(),
    updatedAt: meta.updatedAt || null,
  };
}

export async function saveSaved({ slug, title, description, content }) {
  const id = slug && String(slug).trim() ? String(slug).trim().toLowerCase() : slugFromTitle(title);
  if (!validSlug(id)) throw new Error(`记忆名不合法：${id}（只允许小写字母、数字、连字符）`);
  if (!title || !String(title).trim()) throw new Error("title 不能为空");
  const body = String(content ?? "");
  const bytes = Buffer.byteLength(body);
  if (bytes > MAX_MEMORY_BYTES) throw new Error(`记忆内容过大（${bytes} 字节），上限 ${MAX_MEMORY_BYTES} 字节`);
  const existing = await readSaved(id);
  const now = new Date().toISOString();
  const paths = await ensureDirs();
  const text = renderFrontmatter(
    {
      title: String(title).trim().slice(0, MAX_TITLE),
      description: String(description || "").replace(/\r?\n/g, " ").trim().slice(0, MAX_DESCRIPTION),
      createdAt: existing ? undefined : now,
      updatedAt: now,
    },
    body,
  );
  await writeFile(join(paths.saved, `${id}.md`), text, "utf8");
  return { slug: id, created: !existing, bytes, path: `$DSH_HOME/memory/saved/${id}.md` };
}

export async function deleteSaved(slug) {
  if (!validSlug(slug)) throw new Error(`记忆名不合法：${slug}`);
  const paths = memoryPaths();
  const file = join(paths.saved, `${slug}.md`);
  if (!existsSync(file)) return { deleted: false, slug };
  await rm(file);
  return { deleted: true, slug };
}

/** 关键词打分检索：标题权重最高，其次描述，最后正文。 */
export async function searchSaved(query, limit = 10) {
  const terms = String(query || "")
    .toLowerCase()
    .split(/[\s,，、;；]+/)
    .filter(Boolean);
  const all = await listSaved();
  if (terms.length === 0) return all.slice(0, limit).map((item) => ({ ...item, score: 0, snippet: "" }));
  const scored = [];
  for (const item of all) {
    const full = await readSaved(item.slug);
    if (!full) continue;
    const title = full.title.toLowerCase();
    const description = full.description.toLowerCase();
    const content = full.content.toLowerCase();
    let score = 0;
    for (const term of terms) {
      if (title.includes(term)) score += 10;
      if (description.includes(term)) score += 5;
      const at = content.indexOf(term);
      if (at >= 0) {
        score += 2;
        for (let i = content.indexOf(term, at + term.length); i >= 0; i = content.indexOf(term, i + term.length)) score += 1;
      }
    }
    if (score === 0) continue;
    const at = terms.map((term) => content.indexOf(term)).filter((i) => i >= 0).sort((a, b) => a - b)[0] ?? 0;
    const snippet = at > 0 ? `…${full.content.slice(Math.max(0, at - 60), at + 120)}…` : full.content.slice(0, 180);
    scored.push({ ...item, score, snippet });
  }
  scored.sort((a, b) => b.score - a.score || a.slug.localeCompare(b.slug));
  return scored.slice(0, limit);
}
