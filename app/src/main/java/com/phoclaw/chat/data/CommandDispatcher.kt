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
 * @param tavilyKey 供 [TavilyClient] / [WebFetchClient] 使用的 Key。空字符串 =
 *   用户没配，此时对应的指令会被明确拒绝
 * @param webFetch 网页抓取客户端。与 [tavily] 共用同一个 Key，但职责独立
 * @param httpClient 通用 HTTP 请求客户端。不需要 Key
 * @param codeRunner 远程代码执行客户端。代码会发送到 wandbox.org
 * @param codeExecEnabled 用户是否在设置里打开了「代码执行」。默认关闭 ——
 *   把代码上传到第三方是明确的数据外发行为，不能让用户被动接受
 * @param memory 长期记忆存储。与工作区**无关**：没授权工作区时记忆照样可用
 */
class CommandDispatcher(
    private val workspace: WorkspaceRepository,
    private val tavily: TavilyClient? = null,
    private val tavilyKey: String = "",
    private val webFetch: WebFetchClient? = null,
    private val httpClient: HttpToolClient? = null,
    private val codeRunner: CodeRunnerClient? = null,
    private val codeExecEnabled: Boolean = false,
    private val memory: MemoryStore? = null
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

        CommandParser.Command.Action.WEBFETCH -> webFetch(cmd.path)

        // http 指令：参数行是「方法 URL」，正文是「头 + 空行 + 请求体」
        CommandParser.Command.Action.HTTP -> http(cmd.path, cmd.content)

        // run 指令：参数行是「文件或语言」，正文是内联代码（仅语言形式用）
        CommandParser.Command.Action.RUN -> run(cmd.path, cmd.content)

        // memory 指令：参数行是内容或子命令，正文可作为多行内容的备选来源
        CommandParser.Command.Action.MEMORY -> memory(cmd.path, cmd.content)
    }

    /**
     * 长期记忆的读写。
     *
     * 参数有三种形态，靠参数行的第一个词区分：
     * - 空（或只有正文）→ 追加一条
     * - `forget <关键词>` → 删除匹配的条目
     * - `list` → 查看全部
     *
     * 没有 `clear`：整份清空的风险太高，交给设置页里那个要二次确认的按钮。
     * 模型误判一次就把用户攒了几个月的记忆清光，这个代价不值得。
     */
    private suspend fun memory(rawPath: String, content: String): String {
        val store = memory ?: return "长期记忆未启用，无法读写。"
        val arg = buildString {
            if (rawPath.isNotBlank() && rawPath != ".") append(rawPath)
            if (content.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(content)
            }
        }.trim()

        if (arg.isEmpty() || arg.equals("list", ignoreCase = true)) {
            val text = store.load().trim()
            return if (text.isEmpty()) {
                "长期记忆目前是空的。用 `memory 内容` 可以写入第一条。"
            } else {
                "===== 长期记忆 =====\n$text\n===== 结束 ====="
            }
        }

        if (arg.equals("forget", ignoreCase = true) ||
            arg.startsWith("forget ", ignoreCase = true)
        ) {
            // 已经确认以 forget 开头（忽略大小写），直接按长度切，
            // 免得 removePrefix 对大小写敏感踩坑
            val keyword = arg.substring("forget".length).trim()
            if (keyword.isEmpty()) {
                return "请提供要忘记的关键词，格式：memory forget 关键词"
            }
            val removed = store.forget(keyword)
            return if (removed == 0) {
                "没有找到包含「$keyword」的记忆。"
            } else {
                "已删除 $removed 条包含「$keyword」的记忆。"
            }
        }

        return when (val result = store.remember(arg)) {
            is MemoryStore.RememberResult.Added ->
                "已记住（当前共 ${result.total} 条）。之后每次对话都会带上这条。"
            MemoryStore.RememberResult.Duplicate ->
                "这条内容已经在记忆里了，没有重复写入。"
            MemoryStore.RememberResult.Empty ->
                "记忆内容为空，没有写入。"
            is MemoryStore.RememberResult.Full ->
                "记忆已满（当前 ${result.currentChars} 字符），没有写入。" +
                        "请让用户去「设置 → 长期记忆」清理一些不再成立的条目。"
            is MemoryStore.RememberResult.Failed ->
                "写入记忆失败：${result.message}"
        }
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

    /**
     * 抓取网页正文。
     *
     * 参数可以给多个 URL，用空白分隔 —— 模型经常需要一次对照几个页面。
     * 与 [webSearch] 一样，失败时要给出模型**能据以行动**的说明。
     */
    private suspend fun webFetch(arg: String): String {
        val raw = arg.trim().trim('"', '\'', '`')
        if (raw.isBlank() || raw == ".") {
            return "请提供要抓取的网址，格式：webfetch https://example.com"
        }

        // 按空白拆分，允许一次抓多个；顺手剥掉包裹的引号
        val urls = raw.split(Regex("\\s+"))
            .map { it.trim().trim('"', '\'', '`') }
            .filter { it.isNotBlank() }

        if (urls.isEmpty()) {
            return "请提供要抓取的网址，格式：webfetch https://example.com"
        }

        // 提前拦掉显然不是网址的输入：模型偶尔会把搜索词当成 URL 传进来。
        // 在这里拒掉比丢给接口再等一个失败响应要快，错误信息也更清楚
        val invalid = urls.filterNot {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
        if (invalid.isNotEmpty()) {
            return "这些不是合法的网址（必须以 http:// 或 https:// 开头）：${invalid.joinToString(", ")}。" +
                    "如果你想搜索网页，请改用 websearch 指令。"
        }

        val client = webFetch
            ?: return "网页抓取不可用（当前环境未提供该能力），请直接根据已有知识回答"

        if (tavilyKey.isBlank()) {
            return "网页抓取未启用：用户还没有在「设置」里填写 Tavily API Key。" +
                    "请直接告知用户需要配置该 Key 才能抓取网页，" +
                    "并改用你已有的知识回答这个问题，不要重复尝试抓取。"
        }

        return client.fetch(tavilyKey, urls).fold(
            onSuccess = { client.formatForModel(it, urls) },
            onFailure = { e ->
                "网页抓取失败：${e.message ?: e.javaClass.simpleName}。" +
                        "你可以确认一下网址是否正确，或直接根据已有知识回答。"
            }
        )
    }

    /**
     * 发一次 HTTP 请求。
     *
     * ## 指令格式
     *
     * ```phoclaw:http GET https://api.example.com/users
     * Authorization: Bearer xxx
     * Accept: application/json
     *
     * ```
     *
     * 参数行 = 「方法 URL」；正文 = 若干请求头，空行之后的全部内容作为请求体。
     *
     * 为什么把请求头放在正文而不是参数行：参数行是单行，塞不下多个头；
     * 而且请求体本身可能是多行 JSON，用空行分隔是 HTTP 本身的惯例，
     * 模型也更熟悉这种写法。
     */
    private suspend fun http(arg: String, content: String): String {
        val parts = arg.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return "请提供请求信息，格式：http GET https://example.com（可选在下方写请求头和请求体）"
        }

        // 允许只写 URL 不写方法（默认 GET）—— 模型有时会省掉
        val (method, url) = when {
            parts.size >= 2 && parts[0].uppercase() in HttpToolClient.ALLOWED_METHODS ->
                parts[0].uppercase() to parts[1]
            parts.size == 1 && looksLikeUrl(parts[0]) -> "GET" to parts[0]
            else -> return "请求格式看不懂：「$arg」。格式应为 `http 方法 网址`，" +
                    "例如 `http GET https://api.github.com/users/octocat`。" +
                    "可用方法：${HttpToolClient.ALLOWED_METHODS.joinToString(", ")}"
        }

        if (!looksLikeUrl(url)) {
            return "这不是合法的网址（必须以 http:// 或 https:// 开头）：$url"
        }

        val headers = parseHeaders(content)
        val body = parseBody(content)

        val client = httpClient
            ?: return "HTTP 请求不可用（当前环境未提供该能力）"

        return client.request(method, url, headers, body).fold(
            onSuccess = { client.formatForModel(it, method, url) },
            onFailure = { e ->
                "HTTP 请求失败：${e.message ?: e.javaClass.simpleName}。" +
                        "请确认地址与网络后重试，或改用其它方式完成任务。"
            }
        )
    }

    /**
     * 运行工作区里的代码，或运行一段内联代码。
     *
     * 两种写法：
     * - `run main.py` —— 读取工作区里的文件内容
     * - `run python` + 指令块正文 —— 内联代码，适合先验证一小段逻辑
     *
     * 执行是**远程**的（Wandbox），这意味着两件事必须讲清楚：
     * ① 代码会离开设备；② 运行环境是隔离的，读不到工作区的其它文件。
     * 如果不说明第 ② 点，模型很容易写出「读取 data.txt」然后困惑为什么报错。
     */
    private suspend fun run(arg: String, content: String): String {
        if (!codeExecEnabled) {
            return "代码执行未启用：用户还没有在「设置 → 代码执行」里打开开关。" +
                    "请直接告诉用户需要打开它才能运行代码，不要重复尝试。"
        }

        val client = codeRunner
            ?: return "代码执行不可用（当前环境未提供该能力）"

        val parts = arg.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return "请指定要运行的文件或语言，格式：run <文件|语言>，例如 run main.py"
        }

        val first = parts[0]
        val extraArgs = parts.drop(1)

        val fileTarget = client.targetForFile(first)
        val languageTarget = client.targetForLanguage(first)

        if (fileTarget == null && languageTarget == null) {
            return "无法识别「$first」：既不是支持的文件类型，也不是已知的语言名。" +
                    "支持的文件类型见提示词里的说明；如果这是二进制文件，Android 不允许运行，请直接告诉用户。"
        }

        // 没有扩展名、但能当语言名解析 → 内联写法（如 `run python`）
        val isLanguageForm = fileTarget == null
        val target = fileTarget ?: languageTarget!!

        val code: String
        val fileName: String
        if (isLanguageForm) {
            if (content.isBlank()) {
                return "用语言名运行时要提供代码，例如：\n" +
                        "```phoclaw:run python\nprint(1 + 1)\n```"
            }
            code = content
            fileName = target.defaultFileName
        } else {
            code = try {
                workspace.readText(first)
            } catch (e: Exception) {
                return "读取文件失败：${e.message ?: e.javaClass.simpleName}。" +
                        "请先用 list / read 确认路径是否正确。"
            }
            fileName = first.substringAfterLast('/')
        }

        // Wandbox 没有 argv 通道。静默丢弃参数会让模型以为程序收到了参数、
        // 从而对着错误的结果继续推理，所以这里必须显式说明
        val argNote = if (extraArgs.isNotEmpty()) {
            "注意：命令行参数「${extraArgs.joinToString(" ")}」**没有传进去** —— " +
                    "远程执行服务不支持 argv。需要参数的话请在代码里写死，或改用标准输入。\n\n"
        } else {
            ""
        }

        return client.run(code, fileName).fold(
            onSuccess = { argNote + it.formatForModel() },
            onFailure = { e ->
                "运行失败：${e.message ?: e.javaClass.simpleName}。" +
                        "请检查代码与网络；如果是文件类型不支持，换一种语言试试。"
            }
        )
    }

    /** 判断是否像 http(s) 网址。 */
    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    /**
     * 从指令正文里解析请求头。
     *
     * 规则：正文开头连续若干行 `名字: 值` 视为头；遇到第一个不匹配的行
     * （通常是空行或 `{`）就停，剩下的全是请求体。
     *
     * 这样做的好处是模型怎么写都不会太离谱：不写头的纯 body（如一段 JSON）
     * 也能正确识别为 body，不会被误当成头丢掉。
     */
    private fun parseHeaders(content: String): List<Pair<String, String>> {
        if (content.isBlank()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (line in content.lines()) {
            val t = line.trim()
            if (t.isEmpty()) break          // 空行 = 头结束
            if (t.startsWith("{") || t.startsWith("[")) break  // 明显是 JSON body
            val idx = t.indexOf(':')
            if (idx <= 0) break             // 不是「名字: 值」的形式，当作 body 开始
            val name = t.substring(0, idx).trim()
            val value = t.substring(idx + 1).trim()
            // 名字不能含空格（HTTP 头名不允许），否则多半是别的文本
            if (name.isEmpty() || name.contains(' ')) break
            out += name to value
        }
        return out
    }

    /** 请求体：头之后（跳过空行）的全部内容。 */
    private fun parseBody(content: String): String {
        if (content.isBlank()) return ""
        val lines = content.lines()
        var i = 0
        // 跳过头部分
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty()) { i++; break }
            if (t.startsWith("{") || t.startsWith("[")) break
            val idx = t.indexOf(':')
            if (idx <= 0) break
            if (t.substring(0, idx).trim().contains(' ')) break
            i++
        }
        return lines.drop(i).joinToString("\n").trim('\n')
    }

    companion object {
        /**
         * 删除、目录移动属于不可逆操作。
         *
         * `http` 的写操作也在这里拦：POST / PUT / PATCH / DELETE 会改变
         * 服务端状态，用户应该有机会看一眼「AI 要把什么发到哪去」。
         * GET / HEAD / OPTIONS 是只读的，不拦。
         */
        fun needsApproval(cmd: CommandParser.Command): Boolean = when (cmd.action) {
            CommandParser.Command.Action.DELETE -> true
            CommandParser.Command.Action.MOVE -> true
            CommandParser.Command.Action.HTTP -> {
                val method = cmd.path.trim().split(Regex("\\s+"))
                    .firstOrNull()?.uppercase().orEmpty()
                HttpToolClient.isMutating(method)
            }
            else -> false
        }

        /** 危险操作的人类可读描述，用于确认框和拒绝日志。 */
        fun describeDanger(cmd: CommandParser.Command): String = when (cmd.action) {
            CommandParser.Command.Action.DELETE -> "删除 ${cmd.path}"
            CommandParser.Command.Action.MOVE ->
                if (cmd.target.isBlank()) "移动 ${cmd.path}" else "移动 ${cmd.path} → ${cmd.target}"
            CommandParser.Command.Action.HTTP -> {
                // 确认框要把「发到哪」说清楚 —— 这是用户唯一能看懂的机会
                val parts = cmd.path.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val method = parts.firstOrNull()?.uppercase().orEmpty()
                val url = parts.getOrNull(1).orEmpty()
                if (url.isBlank()) "$method 请求" else "$method 请求 → $url"
            }
            else -> cmd.path
        }
    }
}
