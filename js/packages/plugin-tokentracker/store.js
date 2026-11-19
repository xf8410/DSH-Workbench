// store.js — on-disk state and aggregation for the TokenTracker plugin.
//
// Everything stays inside `config.root` (default $DSH_HOME/tokentracker):
//   usage.json     totals, per-day/per-model/per-session aggregates, last scan
//   records.jsonl  one settled assistant message per line, TokenTracker field names
//   summary.json   the small object the status action returns
// No prompts or message bodies are written: the reader only yields usage metadata.

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { costOf, DEFAULT_PRICES } from './pricing.js'
import { readSessions } from './reader.js'

const STATE_VERSION = 1
const FIELDS = ['input', 'output', 'cacheRead', 'cacheWrite', 'reasoning', 'total']

export function dshHome(env = process.env) {
  return env.DSH_HOME && env.DSH_HOME.length > 0 ? env.DSH_HOME : join(homedir(), '.dsh')
}

export function defaultRoot(env = process.env) {
  return join(dshHome(env), 'tokentracker')
}

export function sessionsRootOf(env = process.env) {
  return join(dshHome(env), 'sessions')
}

const empty = () => {
  const bucket = { calls: 0, cost: 0 }
  for (const field of FIELDS) bucket[field] = 0
  return bucket
}

function addTo(bucket, record, cost) {
  for (const field of FIELDS) bucket[field] = (bucket[field] || 0) + (record[field] || 0)
  bucket.calls = (bucket.calls || 0) + 1
  bucket.cost = Math.round(((bucket.cost || 0) + cost) * 1e6) / 1e6
}

function dayOf(time) {
  const d = new Date(time)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** Fold records into totals / days / models / providers / sessions. */
export function aggregate(records, prices = DEFAULT_PRICES) {
  const totals = empty()
  const days = {}
  const models = {}
  const providers = {}
  const sessions = {}
  let first = 0
  let last = 0
  for (const record of records) {
    const cost = costOf(record, prices)
    addTo(totals, record, cost)
    addTo(days[dayOf(record.time)] ??= empty(), record, cost)
    addTo(models[record.model] ??= empty(), record, cost)
    addTo(providers[record.provider] ??= empty(), record, cost)
    addTo(sessions[record.session || 'unknown'] ??= empty(), record, cost)
    if (first === 0 || record.time < first) first = record.time
    if (record.time > last) last = record.time
  }
  return { totals, days, models, providers, sessions, first, last }
}

function readJson(file) {
  try { return JSON.parse(readFileSync(file, 'utf8')) } catch { return null }
}

function writeFile(file, text) {
  const tmp = `${file}.tmp`
  writeFileSync(tmp, text)
  renameSync(tmp, file)
}

function exportLine(record, cost) {
  return JSON.stringify({
    ts: record.time,
    iso: new Date(record.time).toISOString(),
    session: record.session,
    turn: record.turn,
    step: record.step,
    provider: record.provider,
    model: record.model,
    input_tokens: record.input,
    cached_input_tokens: record.cacheRead,
    cache_write_input_tokens: record.cacheWrite,
    output_tokens: record.output,
    reasoning_output_tokens: record.reasoning,
    total_tokens: record.total,
    cost_cny: cost,
    project: record.project,
  })
}

export function createStore(config = {}, env = process.env) {
  const root = typeof config.root === 'string' && config.root.length > 0 ? config.root : defaultRoot(env)
  const sessionsRoot = typeof config.sessionsRoot === 'string' && config.sessionsRoot.length > 0
    ? config.sessionsRoot
    : sessionsRootOf(env)
  const prices = { ...DEFAULT_PRICES, ...(config.prices ?? {}) }
  const files = {
    state: join(root, 'usage.json'),
    records: join(root, 'records.jsonl'),
    summary: join(root, 'summary.json'),
  }
  const cache = { state: null }

  const load = () => {
    if (cache.state !== null) return cache.state
    const state = readJson(files.state)
    cache.state = state !== null && state.version === STATE_VERSION ? state : null
    return cache.state
  }

  /** Rescan the session logs and persist aggregates. */
  const scan = () => {
    mkdirSync(root, { recursive: true })
    const { files: count, records, sessions, errors } = readSessions(sessionsRoot)
    const agg = aggregate(records, prices)
    const state = {
      version: STATE_VERSION,
      updatedAt: Date.now(),
      sessionsRoot,
      files: count,
      events: records.length,
      sessionsSeen: sessions.length,
      errors: errors.slice(0, 8),
      first: agg.first,
      last: agg.last,
      totals: agg.totals,
      days: agg.days,
      models: agg.models,
      providers: agg.providers,
      sessions: agg.sessions,
    }
    writeFile(files.state, JSON.stringify(state))
    writeFile(files.records, records.map((record) => exportLine(record, costOf(record, prices))).join('\n') + (records.length > 0 ? '\n' : ''))
    const summary = {
      updatedAt: state.updatedAt,
      events: state.events,
      files: state.files,
      sessions: state.sessionsSeen,
      errors: state.errors.length,
      first: state.first,
      last: state.last,
      totals: state.totals,
      today: state.days[dayOf(Date.now())] ?? empty(),
    }
    writeFile(files.summary, JSON.stringify(summary, null, 2))
    cache.state = state
    return state
  }

  /** Persisted state, rescanned when stale or missing. */
  const ensure = (maxAgeMs) => {
    const state = load()
    if (state === null) return scan()
    if (Date.now() - state.updatedAt > maxAgeMs) return scan()
    return state
  }

  return { root, sessionsRoot, prices, files, load, scan, ensure }
}

/** Sum a window of day buckets (`today`, `7d`, `30d`, `all`). */
export function windowTotals(state, window) {
  if (state === null) return empty()
  const days = state.days ?? {}
  const size = window === 'today' ? 1 : window === '7d' ? 7 : window === '30d' ? 30 : 0
  if (size === 0) return state.totals ?? empty()
  const out = empty()
  const now = Date.now()
  for (let i = 0; i < size; i += 1) {
    const bucket = days[dayOf(now - i * 86_400_000)]
    if (bucket === undefined) continue
    for (const field of FIELDS) out[field] += bucket[field] || 0
    out.calls += bucket.calls || 0
    out.cost = Math.round((out.cost + (bucket.cost || 0)) * 1e6) / 1e6
  }
  return out
}