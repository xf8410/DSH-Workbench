# 内置技能

本目录存放随仓库分发的 DSH 技能，供魔改版 DSH 直接取用。

## 技能来源与加载规则

DSH 的技能提供方是 `@deepseek-ai/dsh-skill-filesystem`，它扫描以下根目录：

| 来源 | 路径 | 优先级 |
| --- | --- | --- |
| project-dsh | `<project>/.dsh/skills` | 100 |
| project-agents | `<project>/.agents/skills` | 200 |
| custom | `customSkillDirs` 配置 | 300 |
| user-dsh | `$DSH_HOME/skills` | 400 |
| user-agents | `$AGENTS_HOME/skills` | 500 |
| bundled | `bundledSkillDir` | 最低 |

两种布局都能被识别：

1. 平铺文件：`<root>/<name>.md`（本仓库采用这种）
2. 目录包：`<root>/<name>/SKILL.md`

frontmatter 必须有 `name` 和 `description`，`name` 用 kebab-case 且与文件名一致。

## 让手机用上这些技能

任一即可：

- 把 `skills/redteam/` 整体拷到 `$DSH_HOME/skills/redteam/`（App 私有目录，需 root 或用 App 内置文件管理）；
- 或在 profile 的 `cordis.patch.yml` 里加：

```yaml
- id: skill-filesystem
  config:
    customSkillDirs:
      - /绝对路径/skills/redteam
```

## 技能清单（23）

active-scan、asset-correlation、browser-automation、chisel-tunnel、cn-proxy-pool、
credential-attack、dir-bruteforce、fofa-recon、frp-tunnel、fscan-intranet、
gogo-intranet、kimi-webbridge、lateral-movement、nuclei-scan、passive-recon、
recon-pipeline、redteam-setup、shell-handler、suo5-tunnel、unauth-exploit、
vps-reverse-shell、web-fingerprint、webshell-toolkit
