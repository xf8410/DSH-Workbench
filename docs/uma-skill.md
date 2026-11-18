# 内置育成 AI（uma-trainer 技能）

本文件记录随包内置的育成 AI 技能来源与落点，改动本文件会触发一次 Android 构建。

## 落点

- 技能目录：`skills/uma/uma-trainer/`（与 dsh 的 `dsh-home/skills/` 同构，首启由 App 复制或直接放入用户数据目录）
- 决策数据来源：`127.0.0.1:18765`（游戏内插件）的 `/summary`、`/api/sniff/metadata`
- 打分与守门链的权威实现：`umaai-project/umaai-matsuri` 的 `crates/matsuri-core/src/trainer.rs` 与 `value.rs`
  - 官方评分口径：五维公式 + PT×折算率（`pt_favor_rate = 1.0` 即官方分）
  - 禁止在技能内自定义系数，一律以该仓为准

## 版本

- 构建触发时间：2026-11-18