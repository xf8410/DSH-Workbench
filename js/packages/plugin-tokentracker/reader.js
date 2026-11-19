// reader.js — DSH session-log usage reader (metadata only).
//
// Reads the durable per-session logs DSH writes under $DSH_HOME/sessions:
//
//   sessions/<project>/<session>/session.v<N>.jsonl[.zstd]
//
// and returns only token counts, model names, timestamps and session identity.
// Prompts, assistant bodies, tool arguments and command output are never kept.
//
// The assistant settlement record carries the immutable usage dsh meters:
//   { type: "assistant/message", time, seq,
//     data: { usage: { inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, totalTokens },
//             message: { source: { provider, model } } } }
// Plain `.jsonl` logs (v1-v3) use the same usage contract, so one parser covers both.

import { closeSync, openSync, readSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import zlib from 'node:zlib'

const LOG_NAME = /^session(?:\.v(\d+))?\.jsonl(?:\.zstd)?$/
/** A single log bigger than this is skipped rather than decoded into memory. */
const MAX_BYTES = 96 * 1024 * 1024
/** Hard ceiling on parsed events per log (protects a long history from OOM). */
const MAX_EVENTS = 400_000
/** Usage events older than this are placeholders (epoch 0), not real spending. */
const MIN_TS = Date.UTC(2020, 0, 1)

const num = (value) => {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
}

function readFully(file) {
  const size = statSync(file).size
  if (size > MAX_BYTES) throw Object.assign(new Error(`log too large (${size} B)`), { code: 'TOO_LARGE' })
  const fd = openSync(file, 'r')
  try {
    const buf = Buffer.allocUnsafe(size)
    let read = 0
    while (read < size) {
      const n = readSync(fd, buf, read, size - read, read)
      if (n <= 0) break
      read += n
    }
    return buf.subarray(0, read)
  } finally {
    closeSync(fd)
  }
}

function decompress(file) {
  const zstd = zlib.zstdDecompressSync
  if (typeof zstd !== 'function') {
    throw Object.assign(new Error('this node has no zlib.zstdDecompressSync'), { code: 'NO_ZSTD' })
  }
  return zstd(Buffer.from(readFully(file))).toString('utf8')
}

/** Raw log text: zstd frames are decoded, plain jsonl is read as-is. */
export function readLogText(file) {
  return file.endsWith('.zstd') ? decompress(file) : readFully(file).toString('utf8')
}

/**
 * Every session log under `sessionsRoot`, newest first.
 * Layout is `<root>/<project>/<session>/session.vN.jsonl[.zstd]`; a log placed
 * directly under the root or the project dir is picked up too.
 */
export function listSessionLogs(sessionsRoot) {
  const out = []
  const push = (dir, project) => {
    let names = []
    try { names = readdirSync(dir) } catch { return }
    for (const name of names) {
      if (!LOG_NAME.test(name)) continue
      const file = join(dir, name)
      try {
        const st = statSync(file)
        if (!st.isFile()) continue
        out.push({ file, project, size: st.size, mtime: st.mtimeMs })
      } catch { /* vanished between readdir and stat */ }
    }
  }
  const walk = (dir, project, depth) => {
    push(dir, project)
    if (depth >= 2) return
    let dirents = []
    try { dirents = readdirSync(dir, { withFileTypes: true }) } catch { return }
    for (const dirent of dirents) {
      if (!dirent.isDirectory()) continue
      walk(join(dir, dirent.name), depth === 0 ? dirent.name : project, depth + 1)
    }
  }
  walk(sessionsRoot, '', 0)
  out.sort((a, b) => b.mtime - a.mtime)
  return out
}

function modelOf(entry, header) {
  const data = entry?.data ?? {}
  const source = data.message?.source ?? data.response?.source ?? {}
  const configured = header?.config ?? {}
  return {
    provider: String(source.provider ?? data.provider ?? configured.provider ?? 'dsh'),
    model: String(source.model ?? data.model ?? configured.model ?? 'unknown'),
  }
}

function usageOf(entry) {
  const data = entry?.data ?? {}
  const usage = data.usage ?? data.message?.usage ?? data.response?.usage
  if (usage === null || typeof usage !== 'object') return null
  const input = num(usage.inputTokens ?? usage.input_tokens)
  const output = num(usage.outputTokens ?? usage.output_tokens)
  const cacheRead = num(usage.cacheReadTokens ?? usage.cached_input_tokens ?? usage.cache_read_input_tokens)
  const cacheWrite = num(usage.cacheWriteTokens ?? usage.cacheCreationInputTokens ?? usage.cache_creation_input_tokens)
  const reasoning = num(usage.reasoningOutputTokens ?? usage.reasoning_output_tokens)
  const total = num(usage.totalTokens ?? usage.total_tokens) || input + output
  if (input + output + cacheRead + cacheWrite + total === 0) return null
  return { input, output, cacheRead, cacheWrite, reasoning, total }
}

/** Parse one session log into `{ header, records }` (one record per settled assistant message). */
export function parseLog(file, text, meta = {}) {
  let header = null
  const records = []
  const lines = text.split('\n')
  let events = 0
  for (const line of lines) {
    if (line.length === 0) continue
    if (++events > MAX_EVENTS) break
    let entry
    try { entry = JSON.parse(line) } catch { continue }
    if (entry?.type === 'session' && header === null) {
      header = {
        id: String(entry.id ?? entry.sessionId ?? ''),
        createdAt: Number(entry.createdAt) || null,
        cwd: typeof entry.cwd === 'string' ? entry.cwd : '',
        version: Number(entry.version) || 0,
        delegationDepth: Number(entry.delegationDepth) || 0,
      }
      continue
    }
    if (entry?.type !== 'assistant/message') continue
    const usage = usageOf(entry)
    if (usage === null) continue
    const time = Number(entry.time)
    if (!Number.isFinite(time) || time < MIN_TS) continue
    const { provider, model } = modelOf(entry, header)
    records.push({
      key: `${file}:${entry.seq ?? records.length}`,
      session: header?.id ?? meta.session ?? '',
      project: header?.cwd ?? '',
      slug: meta.project ?? '',
      turn: Number(entry?.data?.turn) || 0,
      step: Number(entry?.data?.step) || 0,
      time,
      provider,
      model,
      ...usage,
    })
  }
  return { header, records }
}

/** Parse every log under `sessionsRoot`; unreadable files are reported, never thrown. */
export function readSessions(sessionsRoot) {
  const files = listSessionLogs(sessionsRoot)
  const records = []
  const sessions = new Map()
  const errors = []
  const seen = new Set()
  for (const meta of files) {
    let text
    try { text = readLogText(meta.file) } catch (error) {
      errors.push({ file: meta.file, error: String(error?.code ?? error?.message ?? error) })
      continue
    }
    let parsed
    try { parsed = parseLog(meta.file, text, meta) } catch (error) {
      errors.push({ file: meta.file, error: String(error?.message ?? error) })
      continue
    }
    if (parsed.header !== null) {
      const id = parsed.header.id || meta.project || meta.file
      const known = sessions.get(id) ?? {
        id,
        cwd: parsed.header.cwd,
        createdAt: parsed.header.createdAt,
        version: parsed.header.version,
        delegationDepth: parsed.header.delegationDepth,
        slug: meta.project ?? '',
        files: 0,
      }
      known.files += 1
      sessions.set(id, known)
    }
    for (const record of parsed.records) {
      if (seen.has(record.key)) continue
      seen.add(record.key)
      records.push(record)
    }
  }
  records.sort((a, b) => a.time - b.time)
  return { files: files.length, records, sessions: [...sessions.values()], errors }
}

export const READER_LIMITS = Object.freeze({ MAX_BYTES, MAX_EVENTS, MIN_TS })