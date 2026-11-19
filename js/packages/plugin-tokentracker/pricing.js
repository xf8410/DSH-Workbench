// pricing.js — CNY per 1M tokens, the same shape plugin-guard uses for its budget.
//
// Prices are estimates used for reporting only; `config.prices` in the profile patch
// overrides any entry (`{"model-or-prefix": {input, cacheRead, output}}`).

export const DEFAULT_PRICES = Object.freeze({
  default: { input: 2, cacheRead: 0.2, output: 3 },
  'deepseek-chat': { input: 2, cacheRead: 0.2, output: 3 },
  'deepseek-reasoner': { input: 2, cacheRead: 0.5, output: 8 },
  'deepseek-flash': { input: 0.5, cacheRead: 0.05, output: 2 },
  'deepseek-v3': { input: 2, cacheRead: 0.2, output: 3 },
  'deepseek-v3.2': { input: 2, cacheRead: 0.2, output: 3 },
})

function pick(table, model) {
  if (table[model] !== undefined) return table[model]
  let best
  for (const key of Object.keys(table)) {
    if (key === 'default' || !model.startsWith(key)) continue
    if (best === undefined || key.length > best.length) best = key
  }
  return best === undefined ? table.default : table[best]
}

/** Cost in CNY for one record (input is billed net of the cached prefix). */
export function costOf(record, table = DEFAULT_PRICES) {
  const price = pick(table, String(record.model ?? '')) ?? table.default
  const input = Math.max(0, (Number(record.input) || 0) - (Number(record.cacheRead) || 0))
  const cached = Number(record.cacheRead) || 0
  const output = Number(record.output) || 0
  const total = (input * price.input + cached * price.cacheRead + output * price.output) / 1_000_000
  return Math.round(total * 1e6) / 1e6
}