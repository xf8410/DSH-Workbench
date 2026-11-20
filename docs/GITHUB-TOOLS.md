# GitHub 工具面（P0 十四件）

这份文档只讲**工具面**（模型能调到什么），不讲 App 界面。界面层另有「GitHub 工作台」。

## 为什么补这一批

原来的 15 件工具（读仓库 / 读文件 / 搜代码 / 看 CI / 建合并 PR / 改码 / 建仓 / 传文件）
覆盖了「看得到仓库、改得动文件」，但四个闭环都断在最后一步：

| 断点 | 原来只能 | 现在有 |
| --- | --- | --- |
| 看不到改动 | `github_list_commits` 只给 message；PR 只能建/合 | `github_commit_diff`、`github_pr_list/read/files` |
| CI 只能看不能动 | 看 run / job / 日志 | `github_ci_rerun`、`github_ci_cancel`、`github_ci_dispatch` |
| 拿不到产物 | 无 | `github_artifact_list`、`github_artifact_download` |
| issue 全家为零 | 无 | `github_issue_list/read/create/comment/close` |
| 发版要外部工具 | 只有「读最新发布」（界面用） | `github_release_list`、`github_release_create`、`github_release_upload_asset` |

## 新增工具（17 件）

### 读（8 件，Risk.read，无需审批）

| 工具 | 干什么 | 关键参数 |
| --- | --- | --- |
| `github_pr_list` | 列 PR | `state=open/closed/all`、`limit` |
| `github_pr_read` | 读一个 PR 的详情（正文、+/-、可合状态） | `number` |
| `github_pr_files` | 读 PR 改了哪些文件（含 patch，超长截断并出声） | `number`、`limit` |
| `github_commit_diff` | 看提交改了什么 | `sha`、`format=json\|text`、`max_files` |
| `github_issue_list` | 列 issue（**已筛掉混进来的 PR 并报数**） | `state`、`limit` |
| `github_issue_read` | 读 issue 详情（拿到 PR 号会明确指路） | `number` |
| `github_release_list` | 列发布版（含 id，传附件要用） | `limit` |
| `github_artifact_list` | 列 Actions 产物（可按 `run_id` 筛） | `run_id`（可空）、`limit` |

### 写（9 件，Risk.write，走 DSH 风险审批）

| 工具 | 干什么 | 边界 |
| --- | --- | --- |
| `github_ci_rerun` | 重跑运行 | `failed_only=true` 只重跑失败 job；成功码 201 |
| `github_ci_cancel` | 取消运行 | 成功码 202；已结束会 409 |
| `github_ci_dispatch` | 手动触发 workflow | 需要 `workflow_dispatch`；`inputs` 逗号分隔 `k=v` |
| `github_artifact_download` | 下载 artifact zip 到手机 | 只收绝对路径 + 必须以 `.zip` 结尾 + 不许 `..`；上限 200MB，超了删半截 |
| `github_issue_create` | 建 issue | `labels` 逗号分隔 |
| `github_issue_comment` | 评论 issue/PR | 正文必填 |
| `github_issue_close` | 关 issue | 走 `PATCH state=closed` |
| `github_release_create` | 建发布 | `draft` / `prerelease` / `target_commitish`；成功回 `id` |
| `github_release_upload_asset` | 传附件 | 单件上限 20MB；同名 422 会说「换个名字」 |

## 三条实现纪律

1. **令牌只进第一跳**：artifact 下载走 302 到第三方对象存储，签名 URL 自带凭据，
   第二跳**不带** `Authorization`——把 Bearer 令牌转发给第三方就是泄令牌。
2. **截断必须出声**：PR 的 patch、commit 的 patch、整段 diff、issue/PR 正文，
   超上限就截断并在文本里写明「原文多少字符」，不许当完整账回填。
3. **失败是人话**：401/403/404/409/422 各有出路说明；artifact 过期、同名附件、run 已结束
   都有专门文案，不回显令牌、不猜「可能也行」。

## 写类的两道闸

- 第一道在工具族：`GitHubExtraWriteTool.register(..., confirmer = null)` 时**一件都不注册**；
- 第二道在 App：`HualuoTools` 把这一族标成 `Risk.write`，由 DSH 的审批流程放行后才执行。

两道闸不互相替代：工具族那道保证「没有确认通道就没有写工具」，App 那道保证「每一步都有人点头」。

## 自测

```sh
bash gradlew :engine:test --tests 'com.hualuo.engine.github.*' --tests 'com.hualuo.engine.toolcalls.GitHub*'
```

新增测试 20 条，全部用注入的假 fetch，不碰真网：

- PR 清单解析 / 坏条目计数 / patch 截断出声 / mergeable 的 null 语义
- commit diff 走 `application/vnd.github.diff` 档、stats 与 files 解析
- issue 清单筛掉 PR 并报数、拿 PR 号读 issue 时明确指路
- release 建发布 422 人话、上传走 `uploads.github.com` 且同名会说换名
- artifact 无跳转时报「过期」、第二跳只拿签名 URL、下载上限与路径校验
- 写类工具：无闸门零注册、闸门拒绝时一次网络都不发、`failed_only` 走对路由
