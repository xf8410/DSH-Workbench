#!/usr/bin/env node
// DSH-Android engine entry (the app starts `node [flags] --require android-env.cjs <this>`).
//
// It is dsh's own CLI (`@deepseek-ai/dsh/lib/bin.js --profile android`) with what the phone
// needs around it:
//  1. extension bookkeeping before boot (@dsh-android/plugin-extensions/boot.js): a fatal crash
//     of the last run attributed to one third-party plugin suspends that plugin; two boots in
//     a row that never became ready (or DSH_ANDROID_SAFE_MODE=1) start in safe mode with every
//     third-party plugin suspended; the profile keeps its pnpm settings and phone gate; the
//     app's old generated profile patch moves to the overlay;
//  2. the app's settings layer `$DSH_HOME/android/app-layer.patch.yml` as a `--patch` overlay
//     (highest layer: the app wins over plugins and over the profile patch dsh writes);
//  3. dsh's `profileContext.packageManager` = the bundled pnpm on the node launcher
//     (libdshnode.so), so dsh's plugin manager installs plugins exactly as on desktop;
//  4. a crash recorder and the boot journal (`ready` once the tree is up).
// Extra argv after this script reaches the booted app unchanged (like bin.js's inner args).
import { installCrashRecorder, markBoot, prepareBoot } from '@dsh-android/plugin-extensions/boot.js'
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const boot = prepareBoot(process.env)
// What the app's overlay rows and plugins may reference (`!!js process.env.…`): the node
// launcher, the AT_SECURE preload for node children, the npm stdio MCP server project.
process.env.DSH_NODE_BIN ||= boot.packageManager.command
process.env.DSH_ANDROID_ENV_CJS ||= boot.paths.preload
process.env.DSH_ANDROID_MCP_DIR ||= boot.paths.mcpServers
installCrashRecorder(boot.paths)

// ── 内置 GLB 工具（glb3d）────────────────────────────────────────────────────
// 这个包自带 glb3d.mjs（渲染多视角预览 / 量比例 / 按高度带改比例）和它的技能说明。
// 启动时落进 $DSH_HOME/skills/glb3d/，于是每次开机、每次升级都自带这个技能，不需要
// 走插件市场、也不占用审批门后的工具面。内容一致就不重写，失败只记一行日志。
const here = dirname(fileURLToPath(import.meta.url))
const home = process.env.DSH_HOME || join(process.env.HOME || '', '.dsh')
try {
  const skillDir = join(home, 'skills', 'glb3d')
  mkdirSync(skillDir, { recursive: true })
  const pairs = [['glb3d.mjs', 'glb.mjs'], ['glb3d-skill.md', 'SKILL.md']]
  for (const [src, dst] of pairs) {
    const from = join(here, src)
    const to = join(skillDir, dst)
    let same = false
    try { same = readFileSync(from).equals(readFileSync(to)) } catch { same = false }
    if (!same) writeFileSync(to, readFileSync(from))
  }
} catch (error) {
  console.warn(`glb3d: install skipped: ${error && error.message ? error.message : error}`)
}

let ready = false
// A stop before the tree is up (the app gave up or the user left) is not a failed boot. Until
// dsh installs its own handlers, this one also keeps the default exit.
for (const [signal, code] of [['SIGTERM', 143], ['SIGINT', 130]]) {
  process.once(signal, () => {
    if (!ready) { try { markBoot('stopped', boot.paths) } catch {} }
    if (process.listenerCount(signal) === 0) process.exit(code)
  })
}

process.argv = [
  process.argv[0],
  process.argv[1],
  '--profile', 'android',
  ...boot.patchFiles.flatMap(file => ['--patch', file]),
  ...process.argv.slice(2),
]
const { runCli } = await import('@deepseek-ai/dsh/lib/bin.js')
await runCli({ packageManager: boot.packageManager })
ready = true
markBoot('ready', boot.paths)