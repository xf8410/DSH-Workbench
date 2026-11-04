# DSH-Workbench 整合边界

DSH-Workbench 只有一个 Android App、一个 APK 和一套原有 DSH UI。Agora、花落与 hlpatch 不作为并列应用迁入，`umaai-matsuri` 也继续独立开发。

## 模块

- `app/`：DSH 主 App。原页面、导航、设置页与交互保持不变。
- `engine/`：花落的纯 Kotlin/JVM 引擎，不包含花落 App、UI、签名或发布产物。
- `app/.../core/workbench/`：DSH 与花落工具、SO 推送缓存之间的适配层。
- `version.properties`：App 与引擎测试共用的版本号来源。

## hlpatch 数据通道

DSH 主进程启动时自动监听 `127.0.0.1:18766`，不监听 `0.0.0.0`。现有 SO 插件按原协议发送：

```http
POST /data HTTP/1.1
Host: 127.0.0.1:18766
Content-Type: application/json
Content-Length: <UTF-8 字节数>
Connection: close

<summary JSON>
```

接收器还提供 `GET /health` 和 `GET /data`。请求头最大 16 KiB，正文最大 8 MiB；POST 必须提供 Content-Length，不支持 chunked。非法 JSON、错误路由和超大正文分别返回明确的 4xx，且不会覆盖上一份有效快照。

每份有效快照保存 `sequence`、`receivedAtMs`、`current`、`previous` 和通用顶层字段差分。`stats`、`trainings`、`ramen`、`matsuri`、`ai` 与以后新增的顶层字段走同一套差分逻辑。

本仓库不包含、构建或注入 hachimi SO，也不包含 hlpatch 的 Rust 源码。游戏侧插件的安装、启用和推送开关仍由用户现有环境负责。

## DSH 工具

### SO 推送

- `uma_push_status`：接收器与最新帧状态。
- `uma_get_snapshot`：最新完整快照，或一个顶层字段。
- `uma_get_changes`：最近两帧的顶层 before/after 差分。

### 18765 只读补充

保留健康、状态、summary、事件、训练、回合与拉面规划等白名单 GET 工具。首次调用时惰性探测，不新增轮询 UI。通用任意路径读取工具不向模型注册。

### GitHub 与 CI

花落 `ToolRegistry` 动态适配为 DSH `Tool`，统一进入 `CapabilityHub`，复用 System 工具组，不新增设置页。默认仓库为 `xf8410/DSH-Workbench`，调用时可用 `repo=owner/name` 覆盖。

- 读取：仓库列表、目录、文件、代码搜索、分支、提交、Actions runs/jobs/log。
- 写入：整文件新建或更新（sha 防覆盖冲突）、创建 PR、按 expected head sha 合并 PR。
- 风险：读取为 read；写文件和建 PR 为 write；合并 PR 为 dangerous。全部由 DSH 现有风险审批链处理，不伪造花落确认卡。
- 密钥：`github_set_token` 仅在用户明确提供令牌时写入 `SecretStore` 的 `github-token` 命名槽；底层为 Android Keystore AES-GCM。`github_auth_status` 只返回是否已保存，绝不返回令牌；`github_clear_token` 删除该槽。

### 未注册的花落模块

`MemoryTool`、`RagTool`、`WebTool`、`SkillTool`、`VideoUrlTool`、`ImageGenTool`、`VideoTool`、`SandboxToolFamily` 和 `TaskTool` 仍只是引擎源码，不会出现在 `capability/list`，不能对模型调用。这是刻意的去重与运行链边界，不把“编译进 APK”算作可用功能：

- 记忆、网页、技能、沙箱和定时自动化分别沿用 DSH 的知识、浏览器、扩展、命令与 Rule 自动化链，不能再注册一套同名语义的状态系统。
- 花落的任务账本没有 Android 到点调度与生成执行器，注册后只会制造不会运行的任务，因此不接。
- 图像、视频、沙箱等实现依赖原 App 的服务配置、会话文件或资产；缺少真实执行后端时不提供空壳工具。

## 模型服务

模型配置只有 DSH 这一套：`ProviderConfig` → DataStore `providers_json` → `renderProfilePatch` → `EngineManager` → `llm-service-lite`。Agora 与花落的设置存储、模型客户端和运行时均未接入，也不会同时启动。

现有“设置 → 模型服务”支持每家公司保存多个命名 Key、选择活动 Key 和删除 Key。明文只在 `SecretStore`，配置仅保存 Key id、名称和活动 id；旧版 `provider-<routeId>` 单 Key 作为 legacy Key 保留。删除活动 Key 时，同一次配置写入确定剩余列表和回退活动 Key，随后才清除旧密钥；删除最后一个或不存在的 id 均为安全操作。引擎环境只注入活动 Key。

OpenAI 兼容供应商继续使用 `openai-chat-completions`；Anthropic 使用引擎已有的 `anthropic-messages`，连接测试同步使用 `/messages`、`x-api-key` 与 `tool_use`。Gemini 没有另造原生协议，仍走其 OpenAI 兼容端点。DeepSeek 与 Groq 已加入供应商预设。

## 模拟器边界

真机帧可通过推送快照交给独立的 `umaai-matsuri` 做状态重建和确定性训练决策。本整合仓库不复制模拟器源码，不修改其 AGENTS.md 约定的提交流程，也不让大模型承担高频逐回合控制。

## 验证

```bash
./gradlew :engine:test
./gradlew :app:testDebugUnitTest --tests 'io.github.jackwener.dsh.core.workbench.*'
./gradlew :app:testDebugUnitTest --tests 'io.github.jackwener.dsh.core.provider.*'
./gradlew :app:compileDebugKotlin
```

Android 构建需要 JDK 21 与 compileSdk 37.2。无法取得原生 Node/Shizuku 预编译产物时，可用 `-Pdsh.fetchShizuku=false` 做 Kotlin 与 JVM 验证，但这不等同于生成可发布的完整 APK。
