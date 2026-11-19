---
name: tokentracker
description: 本机 token 与费用账本（TokenTracker 口径）：今天/7天/30天用了多少 token、花了多少钱、哪个模型最烧、哪个会话最贵。用户问「用了多少 token」「花了多少钱」「哪个会话/模型最贵」「长任务前先看用量」时使用。
---

# TokenTracker（本机账本）

宿主里已经装了 `@dsh-android/plugin-tokentracker`，它把 DSH 自己的会话日志折成用量账本，
口径与 [TokenTracker](https://github.com/xiufengsun/TokenTracker) 的 dsh 读取器一致：
`$DSH_HOME/sessions/<project>/<session>/session.v<N>.jsonl[.zstd]` 里每条
`assistant/message` 落定的 `data.usage`
（`inputTokens` / `cacheReadTokens` / `cacheWriteTokens` / `outputTokens` / `totalTokens`）
按天、按模型、按会话汇总。只读元数据，不读提示词、不读工具输出、不落任何正文。

## 怎么用

优先用工具，不要自己 grep 日志（zstd 日志手撸容易漏字段、算错缓存价）：

```
tokentracker action=stats  window=7d          # 7 天总量（默认 today）
tokentracker action=status                    # 账本位置、扫描健康、今天 + 全量
tokentracker action=models window=all limit=5 # 各模型花多少
tokentracker action=sessions limit=10         # 各会话花多少
tokentracker action=days   limit=14           # 每日序列
tokentracker action=scan                      # 立刻重扫会话日志
tokentracker action=export                    # records.jsonl / summary.json 路径
```

返回字段：`calls`（落定的助手消息数）、`input`、`cacheRead`、`cacheWrite`、`output`、
`reasoning`、`total`、`costCNY`。`total` 是 DSH 记的 `totalTokens`（含缓存命中部分），
所以「用了多少」通常看 `total`，「真花钱」看 `costCNY`。

## 费用口径

估算价按人民币 / 百万 token：默认 `input 2`、`cacheRead 0.2`、`output 3`；
`deepseek-flash` 走 `0.5 / 0.05 / 2`。输入按「总输入 − 缓存命中」计费，缓存命中单独按
`cacheRead` 计。要改价就在 profile patch 里给这一行加 `config.prices`
（形如 `{"deepseek-flash": {"input": 0.5, "cacheRead": 0.05, "output": 2}}`）。

## 数据在哪

| 文件 | 内容 |
| --- | --- |
| `$DSH_HOME/tokentracker/usage.json` | 聚合结果（天 / 模型 / 会话 / 总计）+ 最近扫描时间 |
| `$DSH_HOME/tokentracker/records.jsonl` | 每条落定助手消息一行，TokenTracker 字段名 |
| `$DSH_HOME/tokentracker/summary.json` | 状态卡用的小对象 |

`records.jsonl` 字段：`ts`、`iso`、`session`、`turn`、`step`、`provider`、`model`、
`input_tokens`、`cached_input_tokens`、`cache_write_input_tokens`、`output_tokens`、
`reasoning_output_tokens`、`total_tokens`、`cost_cny`、`project`。
桌面版 TokenTracker CLI 读的是同一份会话日志，所以两边数字能对上；要导进它的库就用这个文件。

## 边界

- 数据只来自本机会话日志，不联网、不登录、不碰账号。
- 日志被截断/损坏的文件会跳过并在 `status.errors` 里列出来（最多 8 条），不会中断扫描。
- 子智能体（`delegationDepth > 0`）的用量同样计入，按它自己的会话 id 分行。