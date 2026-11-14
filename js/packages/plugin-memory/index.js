/**
 * 记忆模块与跨对话读取。
 *
 * 三块能力，对应 agora 的记忆模型：
 *   1. 活跃记忆：$DSH_HOME/memory/active.md，每轮注入进对话，模型始终可见；
 *   2. 已保存记忆：$DSH_HOME/memory/saved/*.md，按需检索、增删改查；
 *   3. 跨对话读取：解析 $DSH_HOME/sessions 里的会话日志，列历史、搜历史、读历史。
 *
 * 全部是本机文件读写，不联网、不上传。
 *
 * @module @dsh-android/plugin-memory
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { createSystemMessage } from "@deepseek-ai/dsh-llm";
import {
  deleteSaved,
  listSaved,
  readActive,
  readSaved,
  saveSaved,
  searchSaved,
  writeActive,
} from "./store.js";
import { listConversations, readConversation, searchConversations } from "./conversations.js";

const name = "android-memory";
const inject = ["tools"];

const textOutput = (render) => ({
  schema: {
    type: "object",
    additionalProperties: true,
    properties: { text: { type: "string", required: true } },
  },
  render: (_args, value) => [{ type: "text", text: render ? render(value) : String(value.text ?? "") }],
});

const ok = (text) => ({ text });

function buildTools() {
  const activeRead = defineTool({
    name: "memory_active_read",
    description: "读取活跃记忆（每轮都会注入对话的简短笔记）。",
    parameters: {},
    output: textOutput(),
    async execute() {
      const active = await readActive();
      return ok(active.exists ? active.content : "(活跃记忆为空)");
    },
    presentCall() {
      return { card: "generic", title: "读取活跃记忆", kind: "read" };
    },
  });

  const activeWrite = defineTool({
    name: "memory_active_write",
    description: "覆盖写入活跃记忆。内容每轮进请求，必须精简（上限 16KB）。适合记用户偏好、长期背景。",
    parameters: {
      content: { type: "string", required: true, description: "新的活跃记忆正文；传空字符串表示清空。" },
    },
    output: textOutput(),
    async execute(args) {
      const result = await writeActive(args.content);
      return ok(`活跃记忆已写入 ${result.bytes} 字节 -> ${result.path}`);
    },
    presentCall() {
      return { card: "generic", title: "更新活跃记忆", kind: "write" };
    },
  });

  const memorySave = defineTool({
    name: "memory_save",
    description: "保存一条命名记忆（新建或覆盖）。适合参考资料、项目笔记、经验教训；之后可用 memory_search 找回。",
    parameters: {
      title: { type: "string", required: true, description: "描述性标题，用于检索匹配。" },
      description: { type: "string", description: "一句话摘要，搜索时权重较高。" },
      content: { type: "string", required: true, description: "完整记忆正文（Markdown）。" },
      slug: { type: "string", description: "可选记忆名（小写字母/数字/连字符），不传则按标题生成。" },
    },
    output: textOutput(),
    async execute(args) {
      const result = await saveSaved(args);
      return ok(`${result.created ? "已新建" : "已覆盖"}记忆 ${result.slug}（${result.bytes} 字节）-> ${result.path}`);
    },
    presentCall(args) {
      return { card: "generic", title: `保存记忆 ${args.title ?? ""}`, kind: "write" };
    },
  });

  const memorySearch = defineTool({
    name: "memory_search",
    description: "按关键词检索已保存记忆，返回标题、摘要与命中片段。不确定有没有相关记忆时先搜再读。",
    parameters: {
      query: { type: "string", required: true, description: "关键词，空格分隔多个词。" },
      limit: { type: "integer", description: "最多返回几条，默认 10。" },
    },
    output: textOutput(),
    async execute(args) {
      const hits = await searchSaved(args.query, Number(args.limit) || 10);
      if (hits.length === 0) return ok(`没有匹配「${args.query}」的记忆。`);
      return ok(
        hits
          .map((hit) => `• ${hit.slug} [${hit.title}] (score ${hit.score})\n  ${hit.description || "(无描述)"}\n  ${hit.snippet}`)
          .join("\n"),
      );
    },
    presentCall() {
      return { card: "generic", title: "检索记忆", kind: "read" };
    },
  });

  const memoryRead = defineTool({
    name: "memory_read",
    description: "按记忆名读取一条已保存记忆的完整正文。",
    parameters: {
      slug: { type: "string", required: true, description: "记忆名，来自 memory_search 的结果。" },
    },
    output: textOutput(),
    async execute(args) {
      const memory = await readSaved(args.slug);
      if (!memory) return ok(`没有名为 ${args.slug} 的记忆。`);
      return ok(`# ${memory.title}\n${memory.description ? memory.description + "\n\n" : ""}${memory.content}`);
    },
    presentCall(args) {
      return { card: "generic", title: `读取记忆 ${args.slug}`, kind: "read" };
    },
  });

  const memoryDelete = defineTool({
    name: "memory_delete",
    description: "删除一条已保存记忆。用于清理过时信息。",
    parameters: {
      slug: { type: "string", required: true, description: "要删除的记忆名。" },
    },
    output: textOutput(),
    async execute(args) {
      const result = await deleteSaved(args.slug);
      return ok(result.deleted ? `已删除记忆 ${args.slug}` : `没有名为 ${args.slug} 的记忆`);
    },
    presentCall(args) {
      return { card: "generic", title: `删除记忆 ${args.slug}`, kind: "write" };
    },
  });

  const memoryList = defineTool({
    name: "memory_list",
    description: "列出全部已保存记忆的标题与摘要（不含正文）。记忆很多时用它总览，再用 memory_read 看细节。",
    parameters: {
      limit: { type: "integer", description: "最多列出几条，默认 50。" },
    },
    output: textOutput(),
    async execute(args) {
      const items = await listSaved();
      if (items.length === 0) return ok("还没有任何已保存记忆。");
      const limited = items.slice(0, Number(args.limit) || 50);
      return ok(
        `共 ${items.length} 条记忆：\n` +
          limited.map((item) => `• ${item.slug} [${item.title}] ${item.bytes}B — ${item.description || "(无描述)"}`).join("\n"),
      );
    },
    presentCall() {
      return { card: "generic", title: "列出记忆", kind: "read" };
    },
  });

  const conversationList = defineTool({
    name: "conversation_list",
    description: "列出这台手机上的历史对话（来自本地会话日志），用于跨对话回顾。",
    parameters: {
      limit: { type: "integer", description: "最多列几条，默认 30。" },
    },
    output: textOutput(),
    async execute(args) {
      const items = await listConversations(Number(args.limit) || 30);
      if (items.length === 0) return ok("没有找到历史对话。");
      return ok(
        items
          .map((item) => `• ${item.id}\n  ${item.createdAt} | ${item.turns} 轮 | ${item.title}`)
          .join("\n"),
      );
    },
    presentCall() {
      return { card: "generic", title: "列出历史对话", kind: "read" };
    },
  });

  const conversationSearch = defineTool({
    name: "conversation_search",
    description: "在所有历史对话里按关键词检索，返回命中的会话与片段。用户说「之前聊过」「上次那个」时用它。",
    parameters: {
      query: { type: "string", required: true, description: "关键词，空格分隔多个词。" },
      limit: { type: "integer", description: "最多返回几个会话，默认 10。" },
    },
    output: textOutput(),
    async execute(args) {
      const hits = await searchConversations(args.query, Number(args.limit) || 10);
      if (hits.length === 0) return ok(`历史对话里没有匹配「${args.query}」的内容。`);
      return ok(
        hits
          .map((hit) => {
            const matches = hit.matches.map((m) => `    [${m.role} ${m.time}] ${m.snippet}`).join("\n");
            return `• ${hit.id} (score ${hit.score}) ${hit.createdAt} | ${hit.turns} 轮 | ${hit.title}\n${matches}`;
          })
          .join("\n"),
      );
    },
    presentCall() {
      return { card: "generic", title: "检索历史对话", kind: "read" };
    },
  });

  const conversationRead = defineTool({
    name: "conversation_read",
    description: "读取某个历史对话的正文。默认返回最近 40 轮，用于把旧对话的细节拿到当前会话来。",
    parameters: {
      id: { type: "string", required: true, description: "会话 id，来自 conversation_list / conversation_search。" },
      limit: { type: "integer", description: "返回最近多少轮，默认 40。" },
      include_assistant: { type: "boolean", description: "是否包含助手发言，默认 true。" },
    },
    output: textOutput(),
    async execute(args) {
      const conversation = await readConversation(args.id, {
        limit: Number(args.limit) || 40,
        includeAssistant: args.include_assistant !== false,
      });
      return ok(
        `# ${conversation.title}\n会话 ${conversation.id} | ${conversation.createdAt} | 共 ${conversation.totalTurns} 轮，返回最近 ${conversation.returned} 轮\n\n${conversation.transcript}`,
      );
    },
    presentCall(args) {
      return { card: "generic", title: `读取对话 ${args.id}`, kind: "read" };
    },
  });

  return [
    activeRead,
    activeWrite,
    memorySave,
    memorySearch,
    memoryRead,
    memoryList,
    memoryDelete,
    conversationList,
    conversationSearch,
    conversationRead,
  ];
}

function apply(ctx) {
  const tools = buildTools();
  for (const tool of tools) ctx.tools.register(tool);

  // 活跃记忆：每个 agent（即每个会话）注入一次，避免每步重复占 token。
  const injected = new WeakSet();
  ctx.on("agent/pre-step", async ({ agent }, next) => {
    const decision = await next();
    if (decision.kind === "reject") return decision;
    if (!agent || injected.has(agent)) return decision;
    let active;
    try {
      active = await readActive();
    } catch {
      return decision;
    }
    if (!active.exists || !active.content) {
      injected.add(agent);
      return decision;
    }
    injected.add(agent);
    // 用系统消息注入：活跃记忆是「始终可见的背景」，不冒充用户发言。
    const message = createSystemMessage(
      [
        "<active_memory>",
        "以下是用户长期保存的活跃记忆，每轮都可见，请当作已知背景，不要逐条复述：",
        "",
        active.content,
        "</active_memory>",
      ].join("\n"),
    );
    return { ...decision, messages: [...decision.messages, message] };
  });
}

export { name, inject, apply };
export default { name, inject, apply };
