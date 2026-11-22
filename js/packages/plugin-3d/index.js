// @dsh-android/plugin-3d — 手机上的 GLB 工具链（不依赖 Blender / Python）
//
// 这个插件本身不注册工具，只做一件事：把包内的 tools/glb.mjs 与 skills/glb3d/SKILL.md
// 装进 $DSH_HOME/skills/glb3d/，于是每次启动都自带 `glb3d` 技能——模型能直接：
//   node <dsh-home>/skills/glb3d/glb.mjs info  MODEL.glb
//   node <dsh-home>/skills/glb3d/glb.mjs sil   MODEL.glb
//   node <dsh-home>/skills/glb3d/glb.mjs fix   IN.glb OUT.glb --scale 0:0.18:1.19
//   node <dsh-home>/skills/glb3d/glb.mjs compare MODEL.silhouette.json SHEET.spec.json
//
// 与 dsh-purge-community 的做法一致：技能来源是插件包内的 skills/ 目录，启动时落盘到
// $DSH_HOME/skills（幂等，内容变了才覆盖），所以升级 APK 后技能自动跟着更新。
//
// 失败必须吞掉并记日志：一个装技能失败不能拦住引擎启动。

import { promises as fsp } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

export const name = 'android-glb3d';
export const inject = [];

const here = path.dirname(fileURLToPath(import.meta.url));
const SKILL_ID = 'glb3d';

/** $DSH_HOME 优先取引擎注入的环境变量，退回到 ~/.dsh。 */
function resolveHome() {
  const fromEnv = process.env.DSH_HOME || process.env.DSH_HOME_DIR;
  if (fromEnv && fromEnv.trim()) return fromEnv.trim();
  const home = process.env.HOME || os.homedir();
  return path.join(home, '.dsh');
}

/** 一对 (源, 目标文件名) 清单。 */
const ASSETS = [
  [path.join(here, 'skills', SKILL_ID, 'SKILL.md'), 'SKILL.md'],
  [path.join(here, 'tools', 'glb.mjs'), 'glb.mjs'],
];

async function install(logger) {
  const dest = path.join(resolveHome(), 'skills', SKILL_ID);
  await fsp.mkdir(dest, { recursive: true });
  for (const [src, fileName] of ASSETS) {
    const target = path.join(dest, fileName);
    try {
      const [a, b] = await Promise.all([fsp.readFile(src), fsp.readFile(target).catch(() => null)]);
      if (b && a.equals(b)) continue;           // 内容一致就不写，避免无谓的 mtime 抖动
      await fsp.writeFile(target, a);
    } catch (error) {
      logger?.warn?.(`glb3d: install ${fileName} failed: ${error?.message ?? error}`);
    }
  }
  return dest;
}

export function apply(ctx, config = {}) {
  const logger = ctx.logger;
  // 不阻塞启动：技能装完之前会话照常可用，只是暂时看不到 glb3d。
  const run = install(logger).then((dest) => {
    if (config.verbose) logger?.info?.(`glb3d: skill installed at ${dest}`);
    return dest;
  }).catch((error) => {
    logger?.warn?.(`glb3d: install failed: ${error?.message ?? error}`);
  });
  // 暴露给测试/诊断用，不参与工具面。
  ctx.on?.('dispose', () => { /* nothing to release */ });
  return run;
}