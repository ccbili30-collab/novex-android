package com.openminis.app.novex.domain

enum class NovexLearningControl {
    PAUSE,
    RESUME,
    EXTEND_BUDGET,
    CANCEL,
    DISMISS,
}

/** Keeps native controls aligned with the persisted task state. */
object NovexLearningControlPolicy {
    fun preflightMessage(preflight: NovexLearningPreflightSnapshot): String = buildString {
        append("将整理 ${preflight.sourceCount} 项资料，估算 ")
        append("原始资料约 ${preflight.estimatedSourceTokens} 个词元。\n")
        preflight.reviewBatchCount?.let { append("剩余通读：$it 批。\n") }
        preflight.reviewInputReservationTokens?.let { append("通读输入预留：$it 个词元（保守估算，不是账单）。\n") }
        append("含综合整理约 ${preflight.estimatedModelRounds} 轮模型处理；综合轮数仅为估算，长笔记可能需要额外分层。\n")
        append("预计耗时：约 ${preflight.estimatedDuration.minimumMinutes}–")
        append("${preflight.estimatedDuration.maximumMinutes} 分钟；实际时间取决于模型响应和网络。\n")
        append("模型：${preflight.modelProviderName} / ${preflight.modelId}\n")
        append("确认累计上限：输入 ${preflight.confirmedBudget.inputTokens}，输出 ${preflight.confirmedBudget.outputTokens} 个词元；含此前已用额度。\n")
        append("费用：当前价格无法可靠估算。每次调用前按预留预算检查，不足则暂停；用量可能含本地估算，实际消耗以提供商记录为准。")
        append("\n隐私：正文片段与整理笔记会交给 ${preflight.dataExposure.destination} 处理，可能离开本设备。")
        if (preflight.ocrSourceCount > 0) append("\n需要光学字符识别：${preflight.ocrSourceCount} 项。")
        if (preflight.networkSourceCount > 0) append("\n需要联网：${preflight.networkSourceCount} 项。")
        if (preflight.unsupportedSources.isNotEmpty()) {
            append("\n无法完整读取：${preflight.unsupportedSources.size} 项，将明确标为部分失败。")
        }
        if (preflight.risks.isNotEmpty()) {
            append("\n\n风险：")
            preflight.risks.forEach { risk -> append("\n• ").append(risk.message) }
        }
        append("\n\n整理只生成来源锚定笔记，不会创建或修改世界、角色、文游和原文件。")
    }

    fun progressMessage(task: NovexLearningTaskState): String = buildString {
        val statusLabel = when (task.status) {
            NovexLearningTaskStatus.INDEXING -> "正在建立资料索引"
            NovexLearningTaskStatus.REVIEWING -> "正在分批通读资料"
            NovexLearningTaskStatus.SYNTHESIZING -> "正在整理总览"
            NovexLearningTaskStatus.PAUSED -> "资料整理已暂停"
            NovexLearningTaskStatus.PAUSED_BUDGET_REACHED -> "已到达确认预算"
            NovexLearningTaskStatus.CANCELLED -> "资料整理已取消"
            NovexLearningTaskStatus.PARTIAL_FAILURE -> "资料整理部分完成"
            NovexLearningTaskStatus.COMPLETE -> "资料整理已完成"
            NovexLearningTaskStatus.NOT_STARTED -> "资料整理尚未开始"
        }
        val usage = task.usage
        append(statusLabel)
        append("。\n输入词元：${usage.usedInputTokens} / ${usage.maxInputTokens}")
        append("\n输出词元：${usage.usedOutputTokens} / ${usage.maxOutputTokens}")
        if (usage.containsEstimates) {
            append("\n用量含本地估算：提供商未返回的部分按请求预留计入预算，不是精确账单。")
        }
        if (usage.containsUnknownLegacyUsage) {
            append("\n部分旧版用量来源未记录，保留原计数，不能作为精确账单。")
        }
        when (task.status) {
            NovexLearningTaskStatus.PAUSED_BUDGET_REACHED -> {
                if (usage.usedInputTokens > usage.maxInputTokens || usage.usedOutputTokens > usage.maxOutputTokens) {
                    append("\n已计入的用量超出确认上限，后续调用已停止；实际用量与费用请核对提供商记录。")
                } else {
                    append("\n剩余预算不足以预留下一批调用，已暂停；已完成的进度与用量均保留。")
                }
            }
            NovexLearningTaskStatus.PARTIAL_FAILURE -> append("\n可读取内容已经保留笔记，无法读取的资料已明确记录。")
            NovexLearningTaskStatus.COMPLETE -> append("\n通读账本、分层笔记和资料集总览均已保存。")
            else -> append("\n每完成一批都会保存进度；退出应用后仍可恢复。")
        }
    }

    fun blocksReplacementPreflight(status: NovexLearningTaskStatus): Boolean = status in setOf(
        NovexLearningTaskStatus.INDEXING,
        NovexLearningTaskStatus.REVIEWING,
        NovexLearningTaskStatus.SYNTHESIZING,
        NovexLearningTaskStatus.PAUSED,
    )

    fun allowedControls(status: NovexLearningTaskStatus): Set<NovexLearningControl> = when (status) {
        NovexLearningTaskStatus.INDEXING,
        NovexLearningTaskStatus.REVIEWING,
        NovexLearningTaskStatus.SYNTHESIZING,
        -> setOf(NovexLearningControl.PAUSE, NovexLearningControl.CANCEL)

        NovexLearningTaskStatus.PAUSED ->
            setOf(NovexLearningControl.RESUME, NovexLearningControl.CANCEL)

        NovexLearningTaskStatus.PAUSED_BUDGET_REACHED -> setOf(
            NovexLearningControl.EXTEND_BUDGET,
            NovexLearningControl.CANCEL,
            NovexLearningControl.DISMISS,
        )

        NovexLearningTaskStatus.NOT_STARTED,
        NovexLearningTaskStatus.CANCELLED,
        NovexLearningTaskStatus.PARTIAL_FAILURE,
        NovexLearningTaskStatus.COMPLETE,
        -> setOf(NovexLearningControl.DISMISS)
    }
}
