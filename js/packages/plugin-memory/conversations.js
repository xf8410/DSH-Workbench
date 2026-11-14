/**
 * 跨对话读取：直接解析 DSH 自己的会话日志。
 *
 * 落盘位置（由 dsh-session-log-deepseek 写出）：
 *   $DSH_HOME/sessions/<项目键>/<会话 id>/session.v<N>.jsonl.zstd
 *
 * 每行一个事件，首行是 {type:"session"} 头，其余带 seq/time/data。
 * 这里只读，不写、不加锁；会话正在写入时按「已完成的整行」解析。
 *
 * @module @dsh-android/plugin-memory/conversations
 */
import { readdir, readFile, stat } from "node:fs/promises";
import { join } from "node:path";
import { zstdDecompressSync } from "node:zlib";
import { resolveDshHome } from "@deepseek-ai/dsh-home-paths";

const MAX_SESSIONS = 500;
const MAX_TRANSCRIPT_CHARS = 60_000;
const SESSION_FILE_RE = /^session\.v(\d+)\.jsonl(\.zstd)?$/;

function sessionsRoot() {
  return process.env.DSH_SESSIONS_DIR || join(resolveDshHome(), "sessions");
}

async function readSafe(file) {
  try {
    return await readFile(file);
  } catch {
    return null;
  }
}

function decodeSessionFile(buf, compressed) {
  let text;
  if (compressed) {
    try {
      text = zstdDecompressSync(buf).toString("utf8");
    } catch {
      return null;
    }
  } else {
    text = buf.toString("utf8");
  }
  const events = [];
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    try {
      events.push(JSON.parse(line));
    } catch {
      // 最后一行可能正在写，跳过
    }
  }
  return events;
}

function textOf(content) {
  if (!Array.isArray(content)) return "";
  return content
    .filter((part) => part && part.type === "text" && typeof part.text === "string")
    .map((part) => part.text)
    .join("\n")
    .trim();
}

/** 从事件流抽出可读的对话轮次。 */
function turnsOf(events) {
  const out = [];
  for (const event of events) {
    if (!event || typeof event !== "object") continue;
    if (event.type === "user/message") {
      const text = textOf(event.data?.content);
      if (text) out.push({ role: "user", time: event.time ?? null, text });
    } else if (event.type === "assistant/message") {
      const text = textOf(event.data?.message?.content);
      if (text) out.push({ role: "assistant", time: event.time ?? null, text });
    }
  }
  return out;
}

async function listSessionDirs() {
  const root = sessionsRoot();
  const dirs = [];
  let projects;
  try {
    projects = await readdir(root, { withFileTypes: true });
  } catch {
    return dirs;
  }
  for (const project of projects) {
    if (!project.isDirectory()) continue;
    const projectPath = join(root, project.name);
    let sessions;
    try {
      sessions = await readdir(projectPath, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const session of sessions) {
      if (!session.isDirectory()) continue;
      dirs.push({ project: project.name, id: session.name, dir: join(projectPath, session.name) });
    }
  }
  return dirs;
}

async function sessionFile(dir) {
  let names;
  try {
    names = await readdir(dir);
  } catch {
    return null;
  }
  const candidates = names
    .map((name) => {
      const match = SESSION_FILE_RE.exec(name);
      return match ? { name, version: Number(match[1]), compressed: Boolean(match[2]) } : null;
    })
    .filter(Boolean)
    .sort((a, b) => b.version - a.version);
  if (candidates.length === 0) return null;
  const best = candidates[0];
  return { path: join(dir, best.name), compressed: best.compressed };
}

async function sessionSummary(entry) {
  const file = await sessionFile(entry.dir);
  if (!file) return null;
  let size = 0;
  try {
    size = (await stat(file.path)).size;
  } catch {
    return null;
  }
  const buf = await readSafe(file.path);
  if (!buf) return null;
  const events = decodeSessionFile(buf, file.compressed);
  if (!events) return null;
  const header = events.find((event) => event?.type === "session") ?? {};
  const titleEvent = events.find((event) => event?.type === "session/title");
  const turns = turnsOf(events);
  return {
    id: header.id || entry.id,
    project: entry.project,
    title: titleEvent?.data?.title || "(无标题)",
    cwd: header.cwd || "",
    createdAt: header.createdAt ?? null,
    updatedMs: null,
    sizeBytes: size,
    turns: turns.length,
    events,
    file: file.path,
  };
}

function fmtTime(ms) {
  if (!ms) return "?";
  try {
    return new Date(ms).toISOString().replace("T", " ").slice(0, 16);
  } catch {
    return "?";
  }
}

export async function listConversations(limit = 30) {
  const dirs = await listSessionDirs();
  const summaries = [];
  for (const entry of dirs.slice(0, MAX_SESSIONS)) {
    const summary = await sessionSummary(entry);
    if (!summary) continue;
    summaries.push(summary);
  }
  summaries.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
  return summaries.slice(0, limit).map((item) => ({
    id: item.id,
    title: item.title,
    turns: item.turns,
    createdAt: fmtTime(item.createdAt),
    cwd: item.cwd,
  }));
}

export async function searchConversations(query, limit = 10) {
  const terms = String(query || "")
    .toLowerCase()
    .split(/[\s,，、;；]+/)
    .filter(Boolean);
  if (terms.length === 0) return [];
  const dirs = await listSessionDirs();
  const hits = [];
  for (const entry of dirs.slice(0, MAX_SESSIONS)) {
    const summary = await sessionSummary(entry);
    if (!summary) continue;
    const titleLower = String(summary.title).toLowerCase();
    const turns = turnsOf(summary.events);
    let score = 0;
    const matches = [];
    for (const turn of turns) {
      const lower = turn.text.toLowerCase();
      let turnScore = 0;
      for (const term of terms) {
        if (lower.includes(term)) turnScore += 1;
      }
      if (turnScore === 0) continue;
      score += turnScore;
      if (matches.length < 3) {
        const at = Math.max(0, lower.indexOf(terms.find((term) => lower.includes(term)) || ""));
        matches.push({
          role: turn.role,
          time: fmtTime(turn.time),
          snippet: turn.text.slice(Math.max(0, at - 80), at + 220).replace(/\s+/g, " ").trim(),
        });
      }
    }
    for (const term of terms) if (titleLower.includes(term)) score += 5;
    if (score === 0) continue;
    hits.push({
      id: summary.id,
      title: summary.title,
      createdAt: fmtTime(summary.createdAt),
      turns: summary.turns,
      score,
      matches,
    });
  }
  hits.sort((a, b) => b.score - a.score);
  return hits.slice(0, limit);
}

export async function readConversation(id, { limit = 40, includeAssistant = true } = {}) {
  const dirs = await listSessionDirs();
  const entry = dirs.find((item) => item.id === id);
  if (!entry) throw new Error(`没找到会话 ${id}`);
  const file = await sessionFile(entry.dir);
  if (!file) throw new Error(`会话 ${id} 没有日志文件`);
  const buf = await readSafe(file.path);
  if (!buf) throw new Error(`会话 ${id} 的日志读取失败`);
  const events = decodeSessionFile(buf, file.compressed);
  if (!events) throw new Error(`会话 ${id} 的日志无法解压`);
  const header = events.find((event) => event?.type === "session") ?? {};
  const titleEvent = events.find((event) => event?.type === "session/title");
  let turns = turnsOf(events);
  if (!includeAssistant) turns = turns.filter((turn) => turn.role === "user");
  const total = turns.length;
  const slice = turns.slice(-limit);
  const lines = [];
  for (const turn of slice) {
    lines.push(`[${turn.role} ${fmtTime(turn.time)}]`);
    lines.push(turn.text);
    lines.push("");
  }
  let transcript = lines.join("\n").trim();
  if (transcript.length > MAX_TRANSCRIPT_CHARS) transcript = transcript.slice(0, MAX_TRANSCRIPT_CHARS) + "\n…(截断)";
  return {
    id: header.id || id,
    title: titleEvent?.data?.title || "(无标题)",
    cwd: header.cwd || "",
    createdAt: fmtTime(header.createdAt),
    totalTurns: total,
    returned: slice.length,
    transcript,
  };
}
