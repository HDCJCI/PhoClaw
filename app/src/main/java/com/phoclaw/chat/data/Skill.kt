package com.phoclaw.chat.data

/**
 * 一个可导入的技能。
 *
 * 技能 = **提示词** + **指令白名单**。激活后：
 * - [prompt] 会被拼进 system message，给模型补充领域知识或工作规范
 * - [allowedActions] 非空时，只有列出的指令能在工作区执行，其余一律拦下
 *
 * 白名单是「能力收窄」而不是「能力扩展」：技能不能给模型超出原有 11 条指令的权限。
 * 这样即使用户导入了一个来路不明的技能，最大风险也只是模型被误导，
 * 不会凭空获得删除整个目录的能力。
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    /** 提示词正文。 */
    val prompt: String,
    /**
     * 允许执行的指令名（规范名，见 [com.phoclaw.chat.util.CommandParser.actionName]）。
     *
     * **空集表示不限制** —— 与「未声明」语义一致。这是刻意的：
     * 如果空集表示「一条都不许」，用户手滑写了个 `actions:` 就会让 AI 完全动不了。
     */
    val allowedActions: Set<String>,
    val enabled: Boolean,
    /** 导入来源文件名，仅用于界面展示。 */
    val source: String,
    val importedAt: Long
) {
    /** 是否有指令限制。 */
    val hasRestriction: Boolean get() = allowedActions.isNotEmpty()
}

/**
 * 技能导入的解析结果。
 *
 * 一次可以导入多个文件，所以按文件分别记录成功与失败 ——
 * 其中一个坏文件不应该让整批导入失败。
 */
data class SkillImportOutcome(
    val skills: List<Skill>,
    val errors: List<String>
)
