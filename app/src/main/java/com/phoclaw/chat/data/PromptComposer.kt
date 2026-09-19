package com.phoclaw.chat.data

/**
 * 提示词合成：把基础系统提示词与已激活技能拼成最终的 system message。
 *
 * 设计原则是**无技能时行为完全不变** —— 没激活任何技能时原样返回基础提示词，
 * 保证既有的对话表现（以及历史教程里写的默认提示词）一字不差。
 */
object PromptComposer {

    /**
     * 合成系统提示词。
     *
     * @param basePrompt 用户在设置里写的基础提示词
     * @param skills 已激活的技能（调用方负责过滤 `enabled`）
     */
    fun compose(basePrompt: String, skills: List<Skill>): String {
        if (skills.isEmpty()) return basePrompt

        val builder = StringBuilder()
        if (basePrompt.isNotBlank()) {
            builder.append(basePrompt.trim())
            builder.append("\n\n")
        }

        builder.append("## 已激活技能\n\n")
        builder.append("以下技能由用户导入并启用，请在相应场景下遵循其中的要求。\n")

        skills.forEach { skill ->
            builder.append("\n### ").append(skill.name).append('\n')
            if (skill.description.isNotBlank()) {
                builder.append(skill.description.trim()).append("\n\n")
            }
            builder.append(skill.prompt.trim()).append('\n')
        }

        // 白名单取并集：多个技能各声明一部分时，用户预期是「这些都能用」。
        // 只有**全都激活技能都没声明**白名单时才不输出限制段落。
        val restricted = skills.filter { it.hasRestriction }
        if (restricted.isNotEmpty()) {
            val union = restricted.flatMap { it.allowedActions }.toSortedSet()
            builder.append("\n## 指令限制\n\n")
            builder.append("当前**仅允许**使用以下指令：")
            builder.append(union.joinToString("、"))
            builder.append("\n\n其它指令一律会被系统拒绝，请不要尝试。需要完成的任务若依赖被禁用的指令，")
            builder.append("请直接说明无法完成，不要反复重试。\n")
        }

        return builder.toString()
    }

    /**
     * 计算当前生效的指令白名单。
     *
     * @return `null` 表示不做限制；非空集合表示只允许这些指令
     */
    fun effectiveWhitelist(skills: List<Skill>): Set<String>? {
        val restricted = skills.filter { it.hasRestriction }
        if (restricted.isEmpty()) return null
        return restricted.flatMap { it.allowedActions }.toSortedSet()
    }
}
