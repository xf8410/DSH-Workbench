package com.hualuo.engine.vision

/**
 * 视频理解计划件（看视频功能第一刀，纯计算）：
 * 抽帧时间点、分批、两段式提示词——全在这里，测试不用碰任何视频与网络。
 *
 * 两段式（对齐「先看后总」的读法）：
 *  1. 逐批描述：每批 [PER_BATCH] 帧，让模型把画面与文字照实读出（选项/数值/界面元素）；
 *  2. 汇总：把各批描述拼起来，出「画面流水 + 攻略要点」结构化总结。
 */
object VideoPlan {

    /** 默认抽帧数：十帧覆盖一段录屏的关键流转，token 可控。 */
    const val DEFAULT_FRAME_COUNT = 10

    /** 每批发给视觉模型的帧数：多图一次发 token 爆，四帧一批稳。 */
    const val PER_BATCH = 4

    /**
     * 均匀抽帧时间点（毫秒）：头尾各让 5%（开场黑屏与结尾停顿不值得花帧），
     * [durationMs] 太短（不足 [MIN_DURATION_MS]）回单点 [0]。
     */
    fun frameTimes(durationMs: Long, count: Int = DEFAULT_FRAME_COUNT): List<Long> {
        require(count >= 1) { "帧数至少 1" }
        if (durationMs <= 0) return listOf(0L)
        if (durationMs < MIN_DURATION_MS) return listOf(durationMs / 2)
        val usable = durationMs * 9 / 10
        val start = durationMs / 20
        return (0 until count).map { i -> start + usable * i / (count - 1).coerceAtLeast(1) }.distinct()
    }

    /** 把时间点切成批（每批 [PER_BATCH] 帧），返回每批的时间点列表。 */
    fun batches(times: List<Long>): List<List<Long>> =
        if (times.isEmpty()) emptyList() else times.chunked(PER_BATCH)

    /** 抽帧描述的提问（每批同一句）：画面与文字照实读，不许脑补。 */
    fun describePrompt(batchIndex: Int, batchCount: Int, timesMs: List<Long>): String =
        "这是一段游戏录屏按时间均匀抽出的第 ${batchIndex + 1}/$batchCount 批画面" +
            "（时间点：${timesMs.joinToString("、") { "${it / 1000.0}s" }}）。\n" +
            "逐张照实读出：1) 界面/场景是什么；2) 画面里的文字（选项、按钮、数值、对话、提示）原样抄出；" +
            "3) 画面之间的变化与因果关系（点了什么、结果是什么）。\n" +
            "看不清的字标「不清」，不许猜。输出按帧顺序，简洁分点。"

    /** 汇总提问：输入是各批描述拼文。 */
    fun summarizePrompt(descriptions: String, userGoal: String?): String {
        val goal = userGoal?.takeIf { it.isNotBlank() }?.let { "用户的关注点：$it。\n" } ?: ""
        return goal +
            "以下是同一段视频按时间顺序分批读出的画面描述：\n\n$descriptions\n\n" +
            "请整理成两段：\n" +
            "【画面流水】按时间顺序讲清这段视频从头到尾发生了什么；\n" +
            "【攻略要点】提炼可复用的打法/选择/数值要点，分点列；画面里出现的关键文字（事件选项、养成数值、比赛结果）原样保留。\n" +
            "描述之间互相矛盾的地方指出并标注，不许自行取舍装没看见。"
    }

    private const val MIN_DURATION_MS = 2_000L
}
