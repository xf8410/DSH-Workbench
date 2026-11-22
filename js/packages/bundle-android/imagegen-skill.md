---
name: imagegen
description: 生图 / 改图：POST /v1/images/edits（多参考图 multipart）与 /v1/images/generations，支持改比例、多张参考、重试与自检
whenToUse: 用户要生成图片、按参考图改比例/姿势、修图重绘、核对网关有哪些图像模型时
role: media
enabled: true
---

# 生图 / 改图（网关直连）

脚本：与本文件同目录的 `imagegen.mjs`（Node 22+ 内置模块，零依赖）

## 配置

优先级：环境变量 → 同目录 `.img.env` → `$DSH_HOME/.img.env` → `/sdcard/DSH/img/.img.env`

```
IMG_KEY=sk-xxx                 # 必填
IMG_BASE=https://43.173.105.2/v1
IMG_MODEL=gpt-image-2.6-sunburst
IMG_HEADERS={"X-Foo":"bar"}    # 额外请求头（JSON）
IMG_TLS_VERIFY=1               # 网关是 IP + 自签，默认不校验
```

## 用法

```bash
G="<本技能目录>/imagegen.mjs"
node "$G" --models                                   # 列网关模型，核对图像模型是否在表
node "$G" -p "只改第一张的比例和姿势，不换角色" -i a.png -i ref.png --size 2:3
node "$G" -p "方形白底立绘" --size 1:1 -n 2
node "$G" -p "..." -i a.png --dry-run                # 只看请求，不发
```

## 参数

`-p/--prompt`、`--prompt-file`、`-i/--image`（可重复，多图走 `image[]`）、`-m/--mask`、
`--size`（`1:1 3:2 4:3 16:9 2:3 3:4 9:16` 或 `1024x1024|1536x1024|1024x1536|auto`）、
`--n`、`--quality`、`--background`、`--output-format`、`--moderation`、`--input-fidelity`、
`-H "K: V"`、`--proxy`、`--timeout`、`--retries`、`--out`/`--outdir`、`--dry-run`、`--ping`、`--models`

## 已内置

- 鉴权 `Authorization: Bearer`；`IMG_HEADERS` 可加任意头（`api-key`、组织头等）
- multipart 文件名去 CR/LF/引号/反斜杠，非 ASCII 走 RFC 5987 `filename*`
- MIME 按魔数判定（PNG/JPEG/GIF/WebP）
- 429/5xx 退避重试（遵守 `Retry-After`）、gzip/deflate/br 解压、自签证书开关
- 输出：默认落 `/sdcard/DCIM/Camera`（相册即时可见），也可 `--out` 指定

## 排错

- `401 Invalid token` → key 错/过期
- `INVALID_REQUEST` 且无字段名 → 先 `--models` 核对模型名是否在网关表里
- 参考图必须是 PNG/JPG；SVG 先栅格化