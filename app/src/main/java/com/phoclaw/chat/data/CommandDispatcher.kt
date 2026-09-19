package com.phoclaw.chat.data

import com.phoclaw.chat.util.CommandParser

/**
 * 指令执行器：把一条解析好的指令真正落到工作区上。
 *
 * 从 `MainViewModel` 里抽出来是为了让**前台对话**与**后台定时任务**共用同一份实现。
 * 两处各写一套的话，迟早会出现「手动执行能删文件、定时任务删不掉」这类
 * 行为不一致的 bug，而且修一处忘一处。
 *
 * @param tavily 联网搜索客户端。传 null 表示调用方不支持联网搜索
 *   （目前没有这种场景，但显式表达比隐式依赖好）
 * @param tavilyKey 供 [TavilyClient] 使用的 Key。空字符串 = 用户没配，
 *   此时 [CommandParser.Command.Action.WEBSEARCH] 会被明确拒绝
 */
class CommandDispatcher(
    private val workspace: WorkspaceRepository,
    private val tavily: TavilyClient? = null,
    private val tavilyKey: String = ""
) {

    /** 真正执行单条指令，返回给模型看的文本结果。 */
    suspend fun dispatch(cmd: CommandParser.Command): String = when (cmd.action) {
        CommandParser.Command.Action.WRITE ->
            workspace.writeText(cmd.path, cmd.content)

        CommandParser.Command.Action.APPEND ->
            workspace.appendText(cmd.path, cmd.content)

        CommandParser.Command.Action.READ ->
            "===== ${cmd.path} =====\n" + workspace.readText(cmd.path)

        CommandParser.Command.Action.LIST -> {
            val entries = workspace.list(cmd.path)
            if (entries.isEmpty()) "目录为空：${cmd.path}"
            else "===== ${cmd.path} =====\n" + entries.joinToString("\n") { (name, isDir, size) ->
                if (isDir) "[DIR ] $name/" else "[FILE] $name ($size 字节)"
            }
        }

        CommandParser.Command.Action.TREE -> workspace.tree(cmd.path)

        CommandParser.Command.Action.SEARCH -> {
            // 参数格式：`search 关键词` 或 `search 关键词 路径`
            val parts = cmd.path.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.isEmpty()) "请提供搜索关键词，格式：search 关键词 [路径]"
            else workspace.search(keyword = parts[0], path = parts.getOrNull(1) ?: ".")
        }

        CommandParser.Command.Action.INFO -> workspace.info(cmd.path)

        CommandParser.Command.Action.DELETE -> workspace.delete(cmd.path)

        CommandParser.Command.Action.MKDIR -> workspace.createDirectory(cmd.path)

        CommandParser.Command.Action.COPY ->
            if (cmd.target.isBlank()) "请指定目标路径，格式：copy 源路径 -> 目标路径"
            else workspace.copy(cmd.path, cmd.target)

        CommandParser.Command.Action.MOVE ->
            if (cmd.target.isBlank()) "请指定目标路径，格式：move 源路径 -> 目标路径"
            else workspace.move(cmd.path, cmd.target)

        CommandParser.Command.Action.WEBSEARCH -> webSearch(cmd.path)
    }

    /**
     * 联网搜索。
     *
     * 三种失败情形都要给出**模型能据以行动**的说明，而不是一句干巴巴的报错：
     * - 用户没配 Key → 告诉模型去回答里说明需要配置，并且别再重试
     * - 搜索本身失败 → 把失败原因回灌，模型可以选择换个关键词或直接作答
     *
     * 关键词取自指令的 `path` 字段 —— 解析器把第一个参数塞在那里，
     * 对 `websearch 今天北京天气` 来说就是整句话（因为参数是按行取到行尾的）。
     */
    private suspend fun webSearch(query: String): String {
        val q = query.trim().trim('"', '\'', '`')
        if (q.isBlank() || q == ".") {
            return "请提供搜索关键词，格式：websearch 关键词"
        }

        val client = tavily
            ?: return "联网搜索不可用（当前环境未提供搜索能力），请直接根据已有知识回答"

        if (tavilyKey.isBlank()) {
            return "联网搜索未启用：用户还没有在「设置」里填写 Tavily API Key。" +
                    "请直接告知用户需要配置该 Key 才能联网搜索，" +
                    "并改用你已有的知识回答这个问题，不要重复尝试搜索。"
        }

        return client.search(tavilyKey, q).fold(
            onSuccess = { client.formatForModel(q, it) },
            onFailure = { e ->
                "联网搜索失败：${e.message ?: e.javaClass.simpleName}。" +
                        "你可以换一个更具体的关键词重试一次，或直接根据已有知识回答。"
            }
        )
    }

    companion object {
        /** 删除与目录移动属于不可逆操作。 */
        fun needsApproval(cmd: CommandParser.Command): Boolean = when (cmd.action) {
            CommandParser.Command.Action.DELETE -> true
            CommandParser.Command.Action.MOVE -> true
            else -> false
        }

        /** 危险操作的人类可读描述，用于确认框和拒绝日志。 */
        fun describeDanger(cmd: CommandParser.Command): String = when (cmd.action) {
            CommandParser.Command.Action.DELETE -> "删除 ${cmd.path}"
            CommandParser.Command.Action.MOVE ->
                if (cmd.target.isBlank()) "移动 ${cmd.path}" else "移动 ${cmd.path} → ${cmd.target}"
            else -> cmd.path
        }
    }
}
