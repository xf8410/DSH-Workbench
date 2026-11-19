// @dsh-android/plugin-tokentracker — TokenTracker for DSH on the phone.
//
// TokenTracker (github.com/xiufengsun/TokenTracker) is a local-first token/cost
// tracker for AI coding tools. Its dsh reader parses the session logs DSH writes
// under ~/.dsh/sessions and folds the `usage` object of every settled assistant
// message (`assistant/message` → data.usage) into per-day, per-model and
// per-session spending. This plugin does the same fold inside DSH itself, so the
// numbers are available on a phone with no Node CLI, no SQLite build step and no
// network call, and it exports a TokenTracker-shaped record stream that the
// desktop CLI can ingest unchanged.
//
// Contract notes:
//  - metadata only: prompts, assistant bodies, tool arguments and command output
//    are never read into the store;
//  - `session.v<N>.jsonl[.zstd]` logs are decoded with node:zlib zstd (Node 22.15+),
//    plain `.jsonl` logs (v1–v3) parse with the same usage contract;
//  - the fold is incremental-free on purpose: session logs are small, and a full
//    rescan can never double count the way a half-tracked cursor can;
//  - prices are estimates (CNY/1M tokens) and overridable through `config.prices`,
//    the same shape plugin-guard uses for its budget.

import { costOf, DEFAULT_PRICES } from './pricing.js'
import { createStore, defaultRoot, sessionsRootOf, windowTotals } from './store.js'

export { costOf, DEFAULT_PRICES, defaultRoot, sessionsRootOf }

export const name = 'android-tokentracker'
export const inject = ['tools']

const WINDOWS = new Set(['today', '7d', '30d', 'all'])
const ACTIONS = new Set(['status', 'scan', 'stats', 'models', 'sessions', 'days', 'export'])
const STALE_MS = 60_000
const TOP = 8

const asText = (payload) => ({
  content: [{ type: 'text', text: typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2) }],
})

const round = (value) => Math.round((Number(value) || 0) * 1e6) / 1e6
const compact = (bucket) => ({
  calls: bucket.calls ?? 0,
  input: bucket.input ?? 0,
  cacheRead: bucket.cacheRead ?? 0,
  cacheWrite: bucket.cacheWrite ?? 0,
  output: bucket.output ?? 0,
  reasoning: bucket.reasoning ?? 0,
  total: bucket.total ?? 0,
  costCNY: round(bucket.cost),
})

const dayKey = (time) => {
  const d = new Date(time)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const byCostDesc = (bucketMap, limit) =>
  Object.entries(bucketMap ?? {})
    .map(([key, bucket]) => ({ key, ...compact(bucket) }))
    .sort((a, b) => b.costCNY - a.costCNY || b.total - a.total)
    .slice(0, limit)

export function apply(ctx, config = {}) {
  const store = createStore(config)
  let disposed = false
  ctx.on('dispose', () => { disposed = true })

  // Warm the aggregate off the plugin start path: a cold boot must not wait on a scan,
  // and a broken log can never keep the plugin from mounting.
  const warm = () => {
    if (disposed) return
    try { store.ensure(STALE_MS) } catch (error) {
      ctx.logger?.warn?.(`tokentracker: initial scan failed: ${error?.message ?? error}`)
    }
  }
  setImmediate(warm)

  const track = (args) => {
    const action = ACTIONS.has(args?.action) ? args.action : 'stats'
    const window = WINDOWS.has(args?.window) ? args.window : 'today'
    const limit = Number.isFinite(Number(args?.limit)) && Number(args.limit) > 0
      ? Math.min(50, Math.floor(Number(args.limit)))
      : TOP
    let state
    try {
      state = action === 'scan' ? store.scan() : store.ensure(STALE_MS)
    } catch (error) {
      return {
        ok: false,
        error: String(error?.message ?? error),
        root: store.root,
        sessionsRoot: store.sessionsRoot,
        hint: 'scan failed: check that config.root is writable and that $DSH_HOME/sessions exists',
      }
    }

    if (action === 'scan') {
      return {
        ok: true,
        action,
        root: store.root,
        sessionsRoot: store.sessionsRoot,
        files: state.files,
        events: state.events,
        sessions: state.sessionsSeen,
        errors: state.errors,
        totals: compact(state.totals ?? {}),
        updatedAt: state.updatedAt,
      }
    }

    if (action === 'status') {
      return {
        ok: true,
        source: 'dsh session logs (assistant/message usage)',
        root: store.root,
        sessionsRoot: store.sessionsRoot,
        recordsFile: store.files.records,
        updatedAt: state.updatedAt,
        stale: Date.now() - state.updatedAt > STALE_MS,
        files: state.files,
        events: state.events,
        sessions: state.sessionsSeen,
        first: state.first,
        last: state.last,
        errors: state.errors,
        today: compact(state.days?.[dayKey(Date.now())] ?? {}),
        allTime: compact(state.totals ?? {}),
        prices: 'CNY per 1M tokens; override with config.prices',
      }
    }

    if (action === 'export') {
      return {
        ok: true,
        action,
        recordsFile: store.files.records,
        summaryFile: store.files.summary,
        records: state.events,
        note: 'TokenTracker field names (input_tokens / cached_input_tokens / output_tokens / total_tokens / cost_cny). '
          + 'The TokenTracker CLI reads the same session logs directly; use this file for its importer or for spreadsheets.',
      }
    }

    if (action === 'models') {
      return {
        ok: true,
        window,
        totals: compact(windowTotals(state, window)),
        models: byCostDesc(state.models, limit),
        providers: byCostDesc(state.providers, limit),
        prices: store.prices,
      }
    }

    if (action === 'sessions') {
      return {
        ok: true,
        window,
        sessions: byCostDesc(state.sessions, limit),
        note: 'per-session numbers sum every recorded day, not just `window`',
      }
    }

    if (action === 'days') {
      const days = Object.entries(state.days ?? {})
        .sort((a, b) => (a[0] < b[0] ? 1 : -1))
        .slice(0, limit)
        .map(([day, bucket]) => ({ day, ...compact(bucket) }))
      return { ok: true, days, windowTotals: compact(windowTotals(state, window)) }
    }

    return {
      ok: true,
      window,
      totals: compact(windowTotals(state, window)),
      topModels: byCostDesc(state.models, Math.min(limit, 5)),
      updatedAt: state.updatedAt,
    }
  }

  ctx.tools.register({
    name: 'tokentracker',
    description: 'Local token and cost accounting for DSH itself, in the shape of TokenTracker '
      + '(github.com/xiufengsun/TokenTracker): folds the usage of every settled assistant message in '
      + '$DSH_HOME/sessions, where the log keeps input / cached-input / cache-write / output / total '
      + 'tokens per model, into today / 7d / 30d / all-time totals with an estimated CNY cost, and '
      + 'exports a records.jsonl the TokenTracker CLI can ingest. Use it for "how many tokens did I use", '
      + '"what did this session cost", "which model eats my quota", or before a long task to see the '
      + 'current spend. Actions: stats (default), status, scan, models, sessions, days, export. '
      + 'No prompt text, tool output or file content is read or stored.',
    parameters: {
      action: {
        type: 'string',
        description: 'stats (window totals, default) | status (store, scan health, today) | scan (rescan session logs now) '
          + '| models (per-model and per-provider spend) | sessions (per-session spend) | days (daily series) '
          + '| export (where records.jsonl is written)',
      },
      window: { type: 'string', description: 'today (default) | 7d | 30d | all' },
      limit: { type: 'number', description: `how many rows to return for list actions (default ${TOP}, max 50)` },
    },
    timeoutMs: 120_000,
    isConcurrencySafe: () => false,
    output: {
      schema: {},
      render: (_args, value) => (Array.isArray(value?.content) ? value.content : [{ type: 'text', text: JSON.stringify(value) }]),
    },
    async execute(args) {
      return asText(track(args ?? {}))
    },
  })

  ctx.logger?.info?.(`tokentracker: store at ${store.root}; sessions at ${store.sessionsRoot}`)
}