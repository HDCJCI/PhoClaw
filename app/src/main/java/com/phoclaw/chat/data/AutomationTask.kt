package com.phoclaw.chat.data

/**
 * 一条自动化定时任务。
 *
 * 触发后，[prompt]（自然语言任务描述）会作为一条用户消息发给 AI，
 * 由 [HeadlessTurnRunner] 在后台无人值守地跑完整个「提问 → 调用工具 → 回灌 → 再提问」循环。
 */
data class AutomationTask(
    val id: String,
    val title: String,
    /** 简介，界面列表上展示的一句话说明。 */
    val summary: String,
    /** 任务描述（自然语言），触发时发给 AI 的内容。 */
    val prompt: String,
    /** cron 表达式原文，5 段或 6 段。 */
    val cron: String,
    /**
     * 是否允许危险操作（删除、移动）。
     *
     * 后台执行时没有人能点确认框，所以这里用**任务级预先授权**代替逐个操作的确认。
     * 关闭时 Runner 会拦下危险指令并把「已拒绝」写回日志，模型因此知道发生了什么、
     * 可以换个方式继续，而不是傻等一个永远不会有人点的对话框。
     */
    val allowDangerous: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long = 0,
    /** 上次执行结果的一句话摘要，展示在任务卡片上。 */
    val lastResult: String = "",
    val lastSuccess: Boolean = true,
    /**
     * 该任务独占的会话 id，每次执行覆盖写。
     *
     * 不做成「每次执行新建会话」是有原因的：一个每小时跑的任务，
     * 一天就会产生 24 个会话，把历史页彻底淹没，用户根本找不到自己真正聊过的东西。
     * 固定一个会话既保留了完整的执行历史，又不干扰正常对话。
     */
    val conversationId: String = ""
)

/** 任务调度状态，界面上用来提示用户。 */
enum class ScheduleState {
    /** 正常，精确闹钟已授权。 */
    EXACT,

    /** 系统未授权精确闹钟，降级为不精确（最差可能晚十几分钟）。 */
    INEXACT,

    /** 任务被停用。 */
    DISABLED
}
