package com.phoclaw.chat.util

/**
 * 系统提示词生成器。
 *
 * 提示词里的「指令格式」「各指令说明」「规则」全部由 [ToolCatalog] 拼出来，
 * 不再手写。这样新增一个工具后，模型立刻就知道它存在 —— 不需要有人记得
 * 同步去改一段藏在 `CredentialStore` 里的多行字符串。
 *
 * @param codeExecEnabled `run` 指令的开关状态。关着的时候仍然把用法写进去，
 *   但会明确告诉模型「现在用不了、要让用户去设置里开」，否则模型会反复重试
 *   一条注定失败的指令。
 * @param shellEnabled `shell` 指令的开关状态。理由同上。
 * @param shellSupported 设备本身是否支持本机命令。鸿蒙上为 false ——
 *   这时要说的是「这设备不行」，而不是「去设置里开一下」，
 *   否则用户会跑去设置页发现根本没有这个开关。
 */
object SystemPromptBuilder {

    fun build(
        codeExecEnabled: Boolean = false,
        shellEnabled: Boolean = false,
        shellSupported: Boolean = true
    ): String = buildString {
        appendLine("你是一个可以直接操作用户手机工作区文件的编程助手。你可以读取、创建、修改、删除、复制和移动文件。")
        appendLine()

        appendLine("## 指令格式")
        appendLine()
        appendLine("用代码块输出指令，语言标记为 phoclaw:动作名：")
        appendLine()

        ToolCatalog.tools.forEach { tool ->
            val examples = listOfNotNull(tool.example) + tool.extraExamples
            examples.forEach { body ->
                appendLine(fence(tool.name, body))
                appendLine()
            }
        }

        appendLine("## 各指令说明")
        appendLine()
        appendLine("| 指令 | 用途 |")
        appendLine("|---|---|")
        ToolCatalog.tools.forEach { tool ->
            appendLine("| `${tool.signature}` | ${tool.summary} |")
        }
        appendLine()

        appendLine("## 规则")
        appendLine()

        val rules = buildList {
            addAll(ToolCatalog.generalRules)
            addAll(ToolCatalog.groupRules)
            ToolCatalog.tools.forEach { tool ->
                addAll(tool.rules)
                // 开关状态紧跟在规则后面：模型读到「run 有开关」，
                // 下一句就是「现在开没开」，不用翻到全文末尾去找
                when (tool.action) {
                    CommandParser.Command.Action.RUN -> add(
                        if (codeExecEnabled) {
                            "「代码执行」当前**已开启**，`run` 可以直接用。"
                        } else {
                            "「代码执行」当前**未开启**：`run` 会立刻返回「代码执行未启用」。用户让你运行代码时，" +
                                    "直接告诉他去「设置 → 代码执行」打开开关，不要反复重试。"
                        }
                    )

                    CommandParser.Command.Action.SHELL -> add(
                        when {
                            !shellSupported ->
                                "**本设备不支持 `shell`**（检测到鸿蒙系统，或系统没有可用的 sh）。" +
                                        "用户要用 `shell` 时，直接说明不支持、不要尝试；" +
                                        "需要跑代码改用 `run`。"

                            shellEnabled ->
                                "「本机命令」当前**已开启**，`shell` 可以直接用。"

                            else ->
                                "「本机命令」当前**未开启**：`shell` 会立刻返回「命令执行未启用」。用户想让你执行命令时，" +
                                        "直接告诉他去「设置 → 本机命令」打开开关，不要反复重试。"
                        }
                    )

                    else -> Unit
                }
            }
        }
        rules.forEachIndexed { index, rule ->
            appendLine("${index + 1}. $rule")
        }
    }.trimEnd()

    /**
     * 把长期记忆拼到系统提示词末尾。
     *
     * 刻意**不**做进 [build]：记忆是运行时的可变数据，而 [build] 的结果
     * 会被缓存成「用户看到的那份提示词」。混在一起的话，设置页上显示的
     * 会是一大段记忆正文，用户改都没法改。
     *
     * 记忆为空时原样返回 [base]，不增任何空小节 —— 没记忆的用户不该
     * 在提示词里看到一段「长期记忆（空）」的空话。
     */
    fun withMemory(base: String, memory: String): String {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return base
        return buildString {
            appendLine(base)
            appendLine()
            appendLine("## 长期记忆")
            appendLine()
            appendLine("以下是从之前对话里积累的长期记忆，**每一轮都会带上**。把它当作已知事实直接使用，不要再去问用户一遍相同的偏好或约定。")
            appendLine("如果其中的内容与用户当前的说法冲突，**以当前说法为准**，并且用 `memory` 修正那条旧记忆。")
            appendLine()
            appendLine(trimmed)
        }.trimEnd()
    }

    /**
     * 把示例正文包进 phoclaw 围栏。
     *
     * 示例的**第一行是参数行**，必须跟在动作名后面（`phoclaw:write src/a.js`），
     * 不能另起一行 —— 解析器虽然两种都能吃，但约定格式是同一行，
     * 换行会让模型学到一个和文档不一致的写法。
     *
     * 其余行是正文；只有参数、没有正文时（如 `list .`）不能多出空行。
     */
    private fun fence(name: String, body: String): String {
        val trimmed = body.trim('\n')
        if (trimmed.isEmpty()) return "```phoclaw:$name\n```"

        val lines = trimmed.split('\n', limit = 2)
        val arg = lines[0].trim()
        val rest = lines.getOrNull(1).orEmpty()

        return if (rest.isBlank()) {
            "```phoclaw:$name $arg\n```"
        } else {
            "```phoclaw:$name $arg\n$rest\n```"
        }
    }
}
