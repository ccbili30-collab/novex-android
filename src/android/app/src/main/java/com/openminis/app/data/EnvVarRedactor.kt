package com.openminis.app.data

import com.openminis.app.data.repository.EnvVarRepository

/**
 * shell 工具输出回灌模型前的环境变量值打码器（血统清剿 P3.7 就地真重写；
 * 掩码规则、MIN_MATCH_LEN、SYSTEM_REMINDER 全文为契约冻结面）。
 *
 * 掩码规则：`len < 8` → 全 `*`；`len >= 8` → 头 2 位 + (len-4) 个 `*` +
 * 尾 2 位（如 `sk-1********ajhks`）。
 *
 * 匹配刻意走朴素 `String.replace` 而非正则——输出可能受攻击者影响，
 * 正则就是 ReDoS 的门。长度 `<= 4` 的值直接跳过：短常见串（`"true"`、
 * `"data"`、`"http"`）高频撞上正常文本，把它们全打成 `*` 比留着可见
 * 更破坏输出。
 */
object EnvVarRedactor {
    const val MIN_MATCH_LEN = 5

    /**
     * 至少发生一次打码时附加到工具结果尾部的英文系统提醒。刻意整段单串，
     * 下游任何裁剪/归一化都拆不断它；内容给模型明确指路——如何在不回显
     * 密钥的前提下继续干活。
     */
    const val SYSTEM_REMINDER = "<system-reminder>Privacy mode is ON: one or more environment variable values were detected in this output and have been masked. Prefer running commands that consume secrets via environment variable references (e.g. `curl -H \"Authorization: Bearer \$API_KEY\" ...`) rather than echoing them. If the user needs to inspect raw values, ask them to disable Privacy Mode at Settings → Environment Variables.</system-reminder>"

    /**
     * 静态交接点：非 DI 调用点（如后台协程里的 ShellExecutor）不必层层
     * 透传仓库就能拿到它。MinisApp.onCreate 设一次。
     */
    @Volatile
    var envVarRepository: EnvVarRepository? = null

    fun mask(value: String): String {
        val len = value.length
        return when {
            len < 8 -> "*".repeat(len)
            else -> value.take(2) + "*".repeat(len - 4) + value.takeLast(2)
        }
    }

    /**
     * 用给定环境变量值集合改写 [output]。返回改写后的串与「至少造成一次
     * 替换的不同值个数」。候选先去重、再按长度降序处理——长值 `FOOBAR`
     * 里的短值 `BAR` 才不会抢先吞掉长匹配。
     */
    fun redact(output: String, values: Collection<String>): Pair<String, Int> {
        val candidates = values.asSequence()
            .filter { it.length >= MIN_MATCH_LEN }
            .distinct()
            .sortedByDescending { it.length }
            .toList()
        if (candidates.isEmpty()) return output to 0

        var rewritten = output
        var distinctHits = 0
        for (secret in candidates) {
            if (!rewritten.contains(secret)) continue
            rewritten = rewritten.replace(secret, mask(secret))
            distinctHits++
        }
        return rewritten to distinctHits
    }

    /**
     * 便捷包装：从仓库（若已接）取值集合，返回可能带提醒尾巴的输出与命中
     * 数。隐私模式关闭、仓库未接线（启动极早期）、或集合为空时原样返回。
     */
    fun redactIfEnabled(output: String): Pair<String, Int> {
        if (!EnvVarPrivacyStore.isEnabled) return output to 0
        val repo = envVarRepository ?: return output to 0
        val secrets = repo.allAsDict().values.filter { it.isNotEmpty() }
        if (secrets.isEmpty()) return output to 0
        val (masked, hits) = redact(output, secrets)
        if (hits == 0) return masked to 0
        return (masked + "\n\n" + SYSTEM_REMINDER) to hits
    }
}
