# 硬守门（HARD GATES）

> **铁律**：本文件列出的每一条都是 CI 强制守门。**任何人不许改 CI/workflow 来绕过这些红线——包括 AI。**
> 这条规矩本身（"硬守门不许改"）由 CODEOWNERS 锁死，只有 owner（花落）能批准修改。

## 总原则

1. **红了就是红了**——不跳过、不吞、不伪装、不截断
2. **每个 PR 一个 feature**——不允许"一次性塞多个不相关功能"
3. **每个 release 一个 tag**——不允许一个 tag 含多个 feature 产物
4. **每个 artifact 必须可独立审计**——不带 zip-of-zip、不带隐式依赖
5. **每个 commit message 必须能复述"做了什么"**——禁止"修一些 bug"这种含糊话

---

## 红线一 · 禁止构建时改源码（已存在）

> CI 不许用 `sed -i` / `python3` / `gh api contents/git/refs` 等手段偷偷改源码然后编译。
> 唯一例外：签名密钥首铸（commit 中带"密钥首铸"标记），且只写 `signing/` 两个文件。

**检查位置**：`.github/workflows/ci.yml` 第 112-124 行

---

## 红线二 · 禁止把整个文件读进内存（已存在）

> 正式代码不许出现 `readAllBytes()` / `readBytes()` / `toByteArray()`——大文件闪退的根因。
> 统一走 `com.hualuo.engine.io.streamingCopy`，每次搬 64 KiB。

**检查位置**：`.github/workflows/ci.yml` 第 126-137 行

---

## 红线三 · 单文件不许超过 999 行（已存在）

> 单 .kt/.kts 文件超过 999 行必须拆开。巨型文件是补丁摞补丁的屎山信号。

**检查位置**：`.github/workflows/ci.yml` 第 139-164 行

---

## 红线四 · 版本号只能有一个来源（已存在）

> `version.properties` 是唯一版本号来源。`app/build.gradle.kts` / `engine/build.gradle.kts` / 源码里不许再写版本号。

**检查位置**：`.github/workflows/ci.yml` 第 166-191 行

---

## 🆕 红线五 · 禁止假绿机制（核心防御）

> **本红线直接挡"外绿内红"**——CI run 整体显示绿勾，但内部某步其实失败了。

### 禁止 1：`continue-on-error: true`（除非显式批准）

```yaml
# ❌ 红——失败被吞
- name: compile
  continue-on-error: true
  run: make build

# ✅ 绿——失败就红
- name: compile
  run: make build
```

**唯一允许**：`actions/upload-artifact@v4` 的失败（网络/配额问题）可以用 `continue-on-error`，但必须在同一个 step 的 `if: always()` 块里打印配额状态。

**历史教训**：
- 旧 Agora 的 `build-workbench.yml` 三步全开此开关，结果"红被伪装成 skipped"，真正的编译错误又被 `tail -n 200` 截断
- 旧 Agora 的 `bash -e` 把失败轮里的日志查看命令全变成死代码，想查都查不了

### 禁止 2：禁止 `tail -n N` / `head -n N` 截断日志

```bash
# ❌ 红——错误行被截掉
make build 2>&1 | tail -n 200

# ✅ 绿——全量保留
make build 2>&1 | tee build.log
# 失败时再读全量日志
```

**历史教训**：Agora 的 CI 把所有日志 `tail -n 200`，导致编译错误在 tail 之后——run 看起来绿，错误其实在那里。

### 禁止 3：禁止 `|| true` 吞错误

```bash
# ❌ 红——失败被吞
make build || true

# ❌ 红——bash 三段式错误吞
cmd1 && cmd2 || echo "fallback"
```

**正确做法**：`set -euo pipefail` 强制每一步失败就退出。

### 禁止 4：禁止 `set +e` / `set +o pipefail` 关错误检查

```bash
# ❌ 红——禁用错误检查
set +e
make build
```

**正确做法**：`set -euo pipefail` 在每个 step 第一行。

### 禁止 5：禁止 `if: always()` 隐藏关键 step 失败

```yaml
# ❌ 红——上传日志这一步无论成败都跑，但语义上"日志"在 build 失败时反而隐藏错误
- name: 收日志
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: log
    path: logs/

# ✅ 绿——失败时单独跑失败摘要
- name: 收日志
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: log
    path: logs/
- name: 失败摘要
  if: failure()
  run: cat logs/* | head -200
```

**关键**：`if: always()` 可以用于"无论成败都收日志"——这是合理的；但**禁止把它当"吞错工具"**——必须在 `if: failure()` 块里把错误显式暴露出来。

### 检查脚本

`.github/scripts/check-no-fake-green.sh` 自动跑上述 5 项检查。
**触发位置**：CI 的 "红线五·禁止假绿机制" step。

---

## 🆕 红线六 · PR 必须单功能

> **每个 PR 只允许改一个 module**——不允许"一次性塞多个不相关功能"。

### 强制 1：PR title 必须是 Conventional Commits 格式

```
feat(<scope>): <什么新功能>
fix(<scope>): <修了什么>
refactor(<scope>): <重构了什么>
perf(<scope>): <性能优化>
test(<scope>): <测试相关>
docs(<scope>): <文档>
ci(<scope>): <CI 改动>
chore(<scope>): <杂项>
```

**scope 必须**是以下之一：`engine` / `app` / `docs` / `ci` / `signing` / `release` / `scripts`。

### 强制 2：PR 改动的文件必须属于一个 module

```
engine/  →  engine
app/     →  app
docs/    →  docs
.github/ + scripts/  →  ci
signing/  →  signing
releases/  →  release
```

**规则**：
- PR 不能同时改 `engine/ + app/`——除非是跨 module 的接口调整，且 commit message 显式标注
- PR 不能同时改 `engine/ + ci/`——"功能改动"和"CI 改动"必须分开
- 一个 PR 一个 feature——不允许"feat A + refactor B + 修个 typo"三合一

### 检查脚本

`.github/scripts/check-pr-single-feature.sh` 自动跑。
**触发位置**：CI 的 "红线六·PR 必须单功能" step（PR trigger）。

---

## 🆕 红线七 · artifact 不嵌 zip

> **CI 产出的 zip 里不许再套 zip**——用户抱怨"16.5 MB 的自检日志里塞了一堆 zip"。

### 强制 1：release asset 禁止 zip-of-zip

```bash
# ❌ 红——release 里塞 zip 包
Hualuo-v3.0.zip
├── Hualuo-v3.0.so
└── Hualuo-v3.0-extra.zip  # ← 这层不允许

# ✅ 绿——扁平结构
Hualuo-v3.0.zip
├── Hualuo-v3.0.so
├── self-check.log
├── MANIFEST.txt
└── Hualuo-v3.0.so.sha256
```

### 强制 2：CI artifact zip 不超过 5 MB（除非是 build 产物）

| Artifact 类型 | 大小上限 |
|---|---|
| 自检日志 | 5 MB |
| 单元测试 XML | 2 MB |
| 编译产物（apk/aar/so） | 不限 |
| 文档 | 5 MB |
| 其他 | 1 MB |

**超额自动红**——避免像 #301 那样把 870 MB 塞进 artifact 撑爆配额。

### 强制 3：每个 artifact 必须含 `self-check.log`（自检日志）

**self-check.log 必备字段**：

```
== self-check ==
tag=<release tag>
commit_sha=<full sha>
timestamp=<ISO 8601 UTC>
version=<versionName>
build_target=<debug|release>
test_engine=<PASS|FAIL count=...>
test_app=<PASS|FAIL count=...>
clippy=<PASS|FAIL warnings=...>
red_lines=<PASS|FAIL which=...>
artifact_size_total=<bytes>
apk_debug=<path> sha256=<sha>
apk_release=<path> sha256=<sha>
== /self-check ==
```

### 检查脚本

`.github/scripts/check-artifact-no-zip-in-zip.sh` 自动跑。
**触发位置**：CI 的 "红线七·artifact 不嵌 zip" step。

---

## 🆕 红线八 · 每 tag 单次 CI + 单 feature release

> **每个 release tag 必须对应一次完整 CI + 单个 feature**。

### 强制 1：release tag 格式

```
vX.Y.Z            # 主版本号
vX.Y.Z-feature    # feature 版本（一个 tag 一个 feature）
vX.Y.Z-fix        # 修复版本
```

### 强制 2：每个 tag 触发一次 CI（不串联）

```yaml
on:
  push:
    tags:
      - 'v*'
# 不允许 workflow_call / workflow_dispatch 串联多 tag
```

### 强制 3：release asset 单 feature

每个 release tag 最多 3 个 asset：
- `Hualuo-<version>.apk` —— 主产物
- `self-check.log` —— 自检日志（必备）
- `MANIFEST.txt` —— 变更清单（必备）

**禁止**：
- ❌ 一个 tag 塞多个 feature 的产物
- ❌ 一个 tag 不带 self-check.log
- ❌ 一个 tag 不带 MANIFEST.txt

### 强制 4：tag 必须能在历史 commit 中找到对应 commit message

```bash
# ❌ 红——tag 但找不到对应 commit
git tag --points-at HEAD  # 必须有一个 commit message 跟 tag 名匹配

# ✅ 绿——tag v0.7.0-feat-streaming 对应 commit "feat(engine): ..."
```

### 检查脚本

`.github/scripts/check-tag-single-feature.sh` 自动跑。
**触发位置**：CI 的 "红线八·每 tag 单次 CI" step（tag trigger）。

---

## 守门纪律

1. **红线改了要写原因**——任何对 HARD-GATES.md 的修改必须配 commit message 写清楚为什么改
2. **改了红线必须过审计**——CODEOWNERS 强制 owner 审批
3. **红线发现漏洞先堵 PR 再补文档**——不允许"先 commit 漏洞、再写文档说以后注意"
4. **AI 不许绕过守门**——任何"AI 为了图方便调过红线"的尝试一律拒绝
