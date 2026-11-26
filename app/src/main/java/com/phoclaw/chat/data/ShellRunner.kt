package com.phoclaw.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本机 shell 命令执行器。
 *
 * ## 和 `run` 的区别
 *
 * `run` 走 Wandbox 远程容器：能装编译器、能跑 30 多种语言，但**看不到工作区**，
 * 而且代码要离开设备。`shell` 跑在自己手机上：能直接看到工作区文件、
 * 数据不出设备，但只有系统自带的 toybox 工具（`ls` `cat` `grep` `find` `sed`
 * `awk` `stat` `du` `ps` 这些），没有 python / node / clang。
 *
 * 两者互补：要**算东西**用 `run`，要**翻文件**用 `shell`。
 *
 * ## 工作区怎么进来
 *
 * 这是整个功能最别扭的地方。Android 给的是 SAF 的 `content://` URI，
 * 根本没有真实路径，shell 进不去。所以用**镜像**：
 *
 * 1. 执行前把工作区的文本文件递归拷到 `files/shell/work/`（有大小和数量上限）；
 * 2. 在沙箱目录里执行命令，命令看到的就是一份正常的目录树；
 * 3. 执行完把**新增或改过**的文件写回工作区。
 *
 * 因此命令产生的文件最终会出现在工作区里，用户能用文件管理器看到。
 * 代价是每次执行多一趟 IO —— 但换来了「shell 真的能操作我的项目」
 * 这个体验，值。
 *
 * ### 为什么删除不同步
 *
 * `rm` 在沙箱里删掉一个文件后，如果也同步删除工作区里的同名文件，
 * 一次手滑就是数据丢失。删除仍然走 `delete` 指令（有确认框）。
 * 所以沙箱里删文件只是「这次命令看不到它」，不会影响真实工作区。
 *
 * @param workspace 用于镜像同步。未授权工作区时命令在空的沙箱里跑
 */
class ShellRunner(
    context: Context,
    private val workspace: WorkspaceRepository
) {

    /** 沙箱根目录。App 私有数据目录，卸载即清。 */
    private val sandbox: File = File(context.filesDir, "shell")

    /** 工作区镜像落地的位置。也是命令的工作目录。 */
    private val workDir: File = File(sandbox, "work")

    /** 没有工作区时的家目录。 */
    private val homeDir: File = File(sandbox, "home")

    /** 一次执行的结果。 */
    data class Result(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean,
        /** 本次写回工作区的文件（相对路径）。 */
        val syncedFiles: List<String>,
        /** 命令执行目录是否挂了工作区镜像。 */
        val hasWorkspace: Boolean
    )

    /**
     * 执行一条命令。
     *
     * @param command 整条 shell 命令，可以带管道、重定向、`&&`
     * @param timeoutMs 超时。到点直接 `destroyForcibly` —— 卡住的进程留着
     *   只会一直占着那点内存，没有价值
     */
    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
        withContext(Dispatchers.IO) {
            val dir = if (workspace.isReady) workDir else homeDir
            dir.mkdirs()

            // ⓪ 沙箱体积兜底。命令可以写任意大的文件（`yes > big.txt`），
            // 不清的话攒到把手机存储占满，用户只会看到「空间不足」而不知道是谁干的
            pruneSandbox(dir)

            // ① 工作区 → 沙箱
            val stamps = if (workspace.isReady) mirrorIn(dir) else emptyMap()

            // ② 执行
            val exec = execute(command, dir, timeoutMs)

            // ③ 沙箱 → 工作区
            val synced = if (workspace.isReady) mirrorOut(dir, stamps) else emptyList()

            Result(
                output = exec.output,
                exitCode = exec.exitCode,
                timedOut = exec.timedOut,
                syncedFiles = synced,
                hasWorkspace = workspace.isReady
            )
        }

    /** 把结果整理成给模型看的文本。 */
    fun formatForModel(command: String, result: Result): String = buildString {
        appendLine("$ $command")
        if (result.timedOut) {
            appendLine("（命令超时被强制结束）")
        } else {
            appendLine("（退出码 ${result.exitCode}）")
        }
        appendLine()
        if (result.output.isBlank()) {
            appendLine("（没有任何输出）")
        } else {
            appendLine(result.output)
        }

        if (result.syncedFiles.isNotEmpty()) {
            appendLine()
            appendLine("已同步回工作区的文件：")
            result.syncedFiles.take(20).forEach { appendLine("- $it") }
            if (result.syncedFiles.size > 20) {
                appendLine("（还有 ${result.syncedFiles.size - 20} 个未列出）")
            }
        } else if (!result.hasWorkspace) {
            appendLine()
            appendLine("注意：当前没有工作区，命令在应用私有沙箱目录里执行，产生的文件不会出现在用户的文件管理器里。")
        }
    }

    // ---------------------------------------------------------------- 执行

    private data class Exec(val output: String, val exitCode: Int, val timedOut: Boolean)

    /**
     * 起进程、读输出、等超时。
     *
     * 输出必须在**单独的线程**里读：如果等进程结束再读，管道缓冲区（约 64KB）
     * 满了之后进程会阻塞在 write 上，永远不退出，`waitFor` 就永远等不到 ——
     * 表现为「命令卡死」。
     */
    private fun execute(command: String, dir: File, timeoutMs: Long): Exec {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(true)
            .apply {
                // 只补必要变量，不整份替换环境 —— 某些 ROM 的 sh 依赖
                // 系统注入的 PATH / LD_LIBRARY_PATH
                environment()["HOME"] = dir.absolutePath
                environment()["PWD"] = dir.absolutePath
                environment()["TMPDIR"] = File(sandbox, "tmp").apply { mkdirs() }.absolutePath
            }
            .start()

        val buffer = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { br ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = br.read(chunk)
                        if (n < 0) break
                        synchronized(buffer) {
                            // 只留前 MAX_OUTPUT_CHARS*2 个字符，避免 `yes` 这种命令
                            // 把内存吃光。最终展示还会再截断一次
                            if (buffer.length < MAX_OUTPUT_CHARS * 2) {
                                buffer.append(chunk, 0, n)
                            }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = runCatching {
            process.waitFor(timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            return Exec(
                output = synchronized(buffer) { buffer.toString() }.trim().let(::truncate),
                exitCode = -1,
                timedOut = true
            )
        }

        reader.join(2_000)
        val text = synchronized(buffer) { buffer.toString() }.trim()
        return Exec(
            output = truncate(text),
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            timedOut = false
        )
    }

    /**
     * 输出截断。
     *
     * **保留头和尾**而不是只保留头：`ls` 之类的结果后面才是关键，
     * 而报错信息往往在最末一行。只砍中间那段对模型最没用。
     */
    private fun truncate(text: String): String {
        if (text.length <= MAX_OUTPUT_CHARS) return text
        val head = MAX_OUTPUT_CHARS * 2 / 3
        val tail = MAX_OUTPUT_CHARS - head
        return text.take(head) +
                "\n\n…（省略 ${text.length - MAX_OUTPUT_CHARS} 个字符）…\n\n" +
                text.takeLast(tail)
    }

    // ---------------------------------------------------------------- 镜像同步

    /**
     * 沙箱超限时清空工作目录。
     *
     * 只清 `work`，不动 `home` —— 没有工作区时用户可能在里面放了他自己
     * 想留住的东西。而 `work` 本身是工作区的镜像，清掉下一轮会重新同步。
     */
    private fun pruneSandbox(dir: File) {
        if (dir != workDir) return
        val total = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        if (total > MAX_SANDBOX_BYTES) {
            runCatching { dir.deleteRecursively() }
            dir.mkdirs()
        }
    }

    /**
     * 工作区 → 沙箱。
     *
     * 返回「相对路径 → 写入后的本地修改时间」，供 [mirrorOut] 判断哪些文件
     * 被命令改过。用 mtime 而不是内容比对：命令改一个字节也能抓到，
     * 而内容哈希要额外读一遍所有文件。
     */
    private suspend fun mirrorIn(dir: File): Map<String, Long> {
        val stamps = mutableMapOf<String, Long>()
        var files = 0
        var bytes = 0L

        suspend fun walk(path: String, target: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val entries = runCatching { workspace.list(path) }.getOrNull() ?: return
            for ((name, isDir, size) in entries) {
                if (files >= MAX_MIRROR_FILES || bytes >= MAX_MIRROR_BYTES) return
                val rel = if (path == "." || path.isBlank()) name else "$path/$name"
                val dest = File(target, name)
                if (isDir) {
                    // 构建产物和依赖目录不进沙箱：几百上千个文件能把遍历
                    // 拖到几十秒（每次 list 都是一次 binder 调用），而且
                    // 命令基本不会需要它们
                    if (name.lowercase() in SKIP_DIRS) continue
                    if (dest.mkdirs() || dest.isDirectory) walk(rel, dest, depth + 1)
                    continue
                }
                // 大文件不进沙箱：一个二进制大文件就能把整个镜像预算吃光
                if (size > MAX_MIRROR_FILE_BYTES) continue
                val text = runCatching { workspace.readText(rel) }.getOrNull() ?: continue
                // 含 NUL 基本可以断定是二进制；写进本地再用 cat 看会输出乱码
                if (text.contains('\u0000')) continue
                if (!runCatching { dest.writeText(text) }.isSuccess) continue
                stamps[rel] = dest.lastModified()
                files++
                bytes += size
            }
        }

        walk(".", dir, 0)
        return stamps
    }

    /**
     * 沙箱 → 工作区。
     *
     * 只写「新增的」和「mtime 变新的」，不处理删除。
     * 每个文件都单独 runCatching：一个文件写失败（比如没权限）不该
     * 让其余文件的同步全部中断。
     */
    private suspend fun mirrorOut(dir: File, stamps: Map<String, Long>): List<String> {
        val changed = mutableListOf<String>()
        val all = runCatching { dir.walkTopDown().filter { it.isFile }.toList() }.getOrDefault(emptyList())

        for (file in all) {
            val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
            val recorded = stamps[rel]
            // 未被命令改过：mtime 没变就跳过，省一次 SAF 写入
            if (recorded != null && file.lastModified() <= recorded) continue
            if (file.length() > MAX_MIRROR_FILE_BYTES) continue

            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (text.contains('\u0000')) continue

            runCatching { workspace.writeText(rel, text) }
                .onSuccess { changed += rel }
        }
        return changed
    }

    // ---------------------------------------------------------------- 危险判定

    companion object {
        /** 单次执行默认超时。 */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** 用户可指定的最大超时。再久就该考虑用 `run` 了。 */
        const val MAX_TIMEOUT_MS = 180_000L

        /** 回灌给模型的输出上限。超出的头尾保留、中间省略。 */
        const val MAX_OUTPUT_CHARS = 8_000

        private const val MAX_MIRROR_FILES = 300
        private const val MAX_MIRROR_BYTES = 6L * 1024 * 1024
        private const val MAX_MIRROR_FILE_BYTES = 512L * 1024
        private const val MAX_DEPTH = 6

        /** 沙箱总体积上限。超过就在下次执行前清空重建。 */
        private const val MAX_SANDBOX_BYTES = 64L * 1024 * 1024

        /**
         * 不镜像的目录名。
         *
         * 这些目录的共同点是「文件多、体积大、命令用不上」。全量遍历它们
         * 会让每次执行多花十几秒（SAF 的每次 list 都是一次 binder 调用）。
         * 真要读里面的东西，模型可以直接用 `read` 指令。
         */
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".idea", "node_modules", "build", "dist",
            "out", "__pycache__", ".venv", "venv", "target", ".next", ".nuxt"
        )

        /**
         * 需要用户确认的命令模式。
         *
         * 不是「黑名单就能挡住一切」——挡不住，shell 的表达能力太强。
         * 它只是把**最常见、最不可逆**的几种挑出来让用户看一眼：
         * 递归删根目录、格式化、刷机、卸载应用、往系统分区写东西。
         *
         * 判不出来的一律放行，因为每次都弹确认会让多轮工具调用变成灾难。
         * 真正的护栏是「默认关闭这个开关」和「只在你自己的应用沙箱里」。
         */
        private val DANGEROUS_PATTERNS = listOf(
            // 注意：raw string 里 `$` 会触发 Kotlin 插值，所以写成 [$] 字符类。
            // 在正则里两者等价，但这样不会被当成模板变量
            Regex("""\brm\s+(?:-[a-zA-Z]*\s+)*(?:-[a-zA-Z]*[rf][a-zA-Z]*)\s+(?:/|/\*|~|[$]HOME|/sdcard)\s*$"""),
            Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f|\brm\s+-[a-zA-Z]*f[a-zA-Z]*r"""),
            Regex("""\bmkfs(\.\w+)?\b"""),
            Regex("""\bdd\s+[^\n]*\bof=/dev/"""),
            Regex("""\b(reboot|shutdown|poweroff)\b"""),
            Regex("""\bsu(\s|$)"""),
            Regex("""\bpm\s+(uninstall|clear|disable)\b"""),
            Regex("""\bam\s+(force-stop|kill)\b"""),
            Regex("""\bsettings\s+put\b"""),
            Regex("""\b(chmod|chown)\s+-R\s+777\s+/"""),
            Regex("""\b(curl|wget)\b[^\n|]*\|\s*(?:sh|bash)\b"""),
            // 注意不要写 `\b>`：`>` 是非单词字符，和前面的空格之间不存在词边界，
            // 这个 `\b` 会让整条规则永远匹配不上
            Regex(""">\s*/system/|>\s*/data/""")
        )

        /** 这条命令要不要先问用户。 */
        fun isDangerous(command: String): Boolean =
            DANGEROUS_PATTERNS.any { it.containsMatchIn(command) }
    }
}
