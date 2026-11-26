package com.phoclaw.chat.util

/**
 * 系统提示词生成器。
 *
 * 提示词里的「指令格式」「各指令说明」「规则」全部由 [ToolCatalog] 拼出来，
 * 不再手写。这样新增一个工具后，模型立刻就知道它存在 —— 不需要有人记得
 * 同步去改一段藏在 `CredentialStore` 里的多行字符串。
 *
 * ## 为什么要拆成「自定义部分 + 工具段」
 *
 * 1.11.0 之前，用户一旦点保存，**整份**提示词就被固定下来。工具表加了新指令，
 * 用户得自己把那一段说明重新抄一遍，否则模型根本不知道有这个东西 ——
 * 而用户想改的往往只是一句「回答用中文」「别写注释」。
 *
 * 现在只存用户写的那部分（角色、语气、项目约定），工具段在每次取用时现拼、
 * 永远追加在末尾。用户负责「AI 是谁」，代码负责「AI 会什么」，两件事分开维护。
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

    /** 默认开场白。用户自定义了内容时不再出现 —— 角色由用户自己写。 */
    private const val HEADER =
        "你是一个可以直接操作用户手机工作区文件的编程助手。你可以读取、创建、修改、删除、复制和移动文件。"

    /**
     * 工具段的起始标记。
     *
     * 它同时是拆分旧版整份提示词的切分点：1.11.0 之前 `KEY_SYSTEM_PROMPT`
     * 存的是完整提示词，迁移时从这里切开，前面那截才是用户真正写的东西。
     */
    const val TOOL_SECTION_START = "## 指令格式"

    /**
     * 更严格的切分标记：标题 + 紧跟的那行说明。
     *
     * 只用 `## 指令格式` 会误伤一种情况 —— 用户在自定义里恰好让 AI「输出一个
     * 叫 ## 指令格式 的小标题」。加上后面那行就基本不会撞了：真撞上说明他
     * 把工具段整段抄进了自定义，那时候切开也是对的。
     */
    private const val TOOL_SECTION_MARKER = "## 指令格式\n\n用代码块输出指令"

    /**
     * 完整提示词 = 自定义部分 + 工具段。
     *
     * 自定义为空时用默认开场白，输出与 1.11.0 之前逐字节一致 ——
     * 没有自定义过的用户升级后不该看到任何变化。
     */
    fun build(
        custom: String = "",
        codeExecEnabled: Boolean = false,
        shellEnabled: Boolean = false,
        shellSupported: Boolean = true
    ): String = compose(custom, toolSection(codeExecEnabled, shellEnabled, shellSupported))

    /** 拼接。工具段可以是缓存过的（只跟开关状态有关，与自定义内容无关）。 */
    fun compose(custom: String, toolSection: String): String {
        val c = custom.trim()
        return if (c.isEmpty()) "$HEADER\n\n$toolSection" else "$c\n\n$toolSection"
    }

    /** 工具段。不含开场白 —— 开场白只在没有自定义时出现。 */
    fun toolSection(
        codeExecEnabled: Boolean = false,
        shellEnabled: Boolean = false,
        shellSupported: Boolean = true
    ): String = buildString {
        appendLine(TOOL_SECTION_START)
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
     * 从旧版「整份提示词」里剥出用户真正写的那部分。
     *
     * 迁移用。三种情况都要能处理：
     * - 用户原样保存过（没改任何字）→ 返回空串，视为「未自定义」，
     *   否则设置页会显示一个「已自定义」而内容与默认一模一样的怪状态；
     * - 用户改了开场白、保留工具段 → 切出开场白那截；
     * - 用户整篇重写、压根没有工具段 → 整篇都算他的。
     */
    fun extractCustomPart(full: String): String {
        var marker = full.indexOf(TOOL_SECTION_MARKER)
        if (marker < 0) marker = full.indexOf(TOOL_SECTION_START)
        if (marker < 0) return full.trim()

        val head = full.substring(0, marker).trim()
        // 只有开场白、且与默认一致 = 用户没改过，别假装他自定义了
        return if (head == HEADER) "" else head
    }

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
        val rest = if (lines.size > 1) lines[1].trim('\n') else ""
        return if (rest.isBlank()) {
            "```phoclaw:$name $arg\n```"
        } else {
            "```phoclaw:$name $arg\n$rest\n```"
        }
    }
}
