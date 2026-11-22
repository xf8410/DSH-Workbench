---
name: glb3d
description: 手机上检查和改 GLB 模型：部件/面数/包围盒、逐高度轮廓与头身比、按高度带缩放修比例、与三视图规格比对（不需要 Blender）
whenToUse: 需要看 GLB 结构、量头身比/肩腰比、按三视图修比例、比对模型与立绘时
role: media
enabled: true
---

# GLB 3D 工具（纯 node，无需 Blender）

脚本：与本文件同目录的 `glb.mjs`（Node 22+ 内置模块，零依赖）

## 四个子命令

```bash
G="<本技能目录>/glb.mjs"
# 1) 结构体检：部件、三角面、包围盒、材质/贴图/骨骼/动画
node "$G" info MODEL.glb
# 2) 轮廓测量：逐高度宽度、头身比、肩/腰位置，同时写 MODEL.silhouette.json
node "$G" sil MODEL.glb
# 3) 按高度带修比例（自头顶起算的比例区间）：头放大 19%、裙摆放宽 27%、整体上移 2.2% 身高
node "$G" fix IN.glb OUT.glb --scale 0:0.18:1.19 --scale 0.55:0.65:1.27 --shift 0.022
# 4) 与三视图规格比对（三视图侧用 measure 出的 .spec.json）
node "$G" compare MODEL.silhouette.json SHEET.spec.json
```

## 判定口径

- **自头顶起算**：`--scale from:to:factor` 里的 from/to 是"从头顶往下"的归一化高度，和立绘测量的口径一致；
- **过渡带**：带边缘 3% 用余弦过渡，避免部件之间出现硬缝；
- **只动几何**：改写 POSITION 浮点与 accessor.min/max，不动拓扑、蒙皮、贴图、动画；
- **前后对比**：`fix` 会回 `movedVertices`，改完立刻再跑一次 `sil` 对比数值。

## 典型用法

```bash
node "$G" sil current.glb                       # 现状：头身比 6.63、裙摆 0.339H
node "$G" fix current.glb fixed.glb --scale 0:0.18:1.19 --scale 0.55:0.65:1.27
node "$G" sil fixed.glb                          # 复测：应接近 5.59 / 0.43H
```

## 限制

- 单 mesh 合并模型只能按高度带改，无法按语义部件改（要部件级请用语义拆件版）；
- 不做渲染：需要预览图时用三视图/图像模型生成对照，不给 GLB 出图；
- 不改 UV 与法线（法线在纯缩放时保持方向即可；大幅缩放后建议在 Blender 里重建法线）。