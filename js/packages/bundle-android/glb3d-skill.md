---
name: glb3d
description: 手机上看/量/改 GLB 模型：正交多视角预览渲染、逐高度轮廓与头身比、按高度带修比例（纯 node，无需 Blender）
whenToUse: 需要看 GLB 长什么样、量头身比/肩腰比、按三视图改比例、出正侧背检查图时
role: media
enabled: true
---

# GLB 3D 工具（纯 node，无需 Blender / WebGL）

脚本：与本文件同目录的 `glb.mjs`（Node 22+ 内置模块，零依赖）

## 四个子命令

```bash
G="<本技能目录>/glb.mjs"

# 1) 看：正交多视角渲染成 PNG（默认正/侧/背，--eight 出八方向）
node "$G" render MODEL.glb /sdcard/Pictures/glb-preview --size 800x1160
node "$G" render MODEL.glb ./out --eight --size 480x696

# 2) 量：逐高度宽度 + 头身比 + 肩/腰位置，同时写 MODEL.silhouette.json
node "$G" sil MODEL.glb

# 3) 看结构：部件/面数/包围盒/材质/骨骼/动画
node "$G" info MODEL.glb

# 4) 改：按高度带缩放（from:to 是"自头顶起算"的归一化高度）
node "$G" fix IN.glb OUT.glb --scale 0:0.16:1.19:xyz --scale 0.55:0.65:1.27
node "$G" fix IN.glb OUT.glb --scale 0:1:0.84:xyz     # 整体归一身高
```

## 口径

- `--scale from:to:factor[:xyz|y|xz]`，默认只改横向（xz）；
  - `xyz` = 三轴等比（改头身比要用它：头部纵向变高）
  - `y` = 只拉高；`xz` = 只加宽（裙摆/披风）
- 带边缘 3% 余弦过渡，避免部件接缝处出现台阶
- 只改 POSITION 浮点与 accessor.min/max，不动拓扑、蒙皮、UV、动画
- `fix` 会回 `movedVertices`；改完立刻 `sil` 复测对比

## 渲染说明

- 正交投影 + z-buffer + 朗伯着色，白底灰度，1.4M 面约 10–30s（手机）
- 视角：`--yaw 0/90/180/270`，`--eight` 出 45° 间隔八方向
- 出图建议 `--size 480x696`（快）或 `800x1160`（细）

## 限制

- 单 mesh 合并模型只能按高度带改，部件级改动请用语义拆件版
- 不还原贴图（预览是纯几何灰度），材质/UV 验证仍需另外做
- 大幅缩放后法线方向仍成立，但建议在建模软件里重建一次法线