package com.phoclaw.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 远程代码执行客户端（Wandbox）。
 *
 * ## 为什么是远程
 *
 * Android 系统里**没有任何语言运行时**：`/system/bin` 下只有 `sh`，
 * 没有 python / node / clang。想本地跑 `.py` 就得把解释器打进 APK
 *（30 MB 起步），跑 `.cpp` 还得带整套编译器（几百 MB），不现实。
 *
 * 更要命的是 Android 10+ 的 W^X 策略：`targetSdk >= 29` 的应用
 * **不能执行可写目录里的 ELF**，所以连用户自己传进来的二进制都跑不了。
 *
 * 远程执行把这些麻烦一次性绕开：代码发过去、结果取回来，APK 体积不变，
 * 还能顺带支持 C++ / Rust / Go 这些本地根本没法编译的语言。
 *
 * ## 代价
 *
 * - 需要联网；
 * - **代码会离开设备**，发送到 wandbox.org，所以设置里默认关闭；
 * - 只能传代码和 stdin，访问不到工作区的其它文件；
 * - 不支持命令行参数（Wandbox 的接口没有 argv 通道）。
 *
 * ## 编译器版本
 *
 * Wandbox 的 `*-head` 镜像经常坏（实测 `cpython-head` 直接报
 * `catatonit: failed to exec pid1`），所以这里一律用**带版本号的稳定镜像**。
 */
class CodeRunnerClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /** 一次执行的原始结果。 */
    data class Outcome(
        val compiler: String,
        val displayName: String,
        val fileName: String,
        /** Wandbox 的 status：0 表示正常结束。 */
        val status: Int,
        val stdout: String,
        val stderr: String,
        /** 编译器的输出（编译型语言才有）。 */
        val compileMessage: String,
        /** 非空表示被信号终止（如段错误 11、超时 9）。 */
        val signal: String,
        /** 是否因为本地超时中断。 */
        val timedOut: Boolean
    ) {
        /** 是否成功（退出码 0 且没有信号）。 */
        val succeeded: Boolean get() = status == 0 && signal.isBlank() && !timedOut
    }

    /** 一个可执行目标：用哪个编译器、展示名、默认文件名。 */
    data class Target(
        val compiler: String,
        val displayName: String,
        val defaultFileName: String
    )

    /**
     * 按文件名解析执行目标。
     *
     * @return null 表示扩展名不认识，调用方应给出「不支持」提示
     */
    fun targetForFile(fileName: String): Target? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return BY_EXTENSION[ext]
    }

    /** 按语言名解析执行目标（用于 `run python` 这种内联写法）。 */
    fun targetForLanguage(language: String): Target? =
        BY_NAME[language.trim().lowercase()]

    /**
     * 执行一段代码。
     *
     * @param code 代码全文
     * @param fileName 只用来判断语言与展示，上传时不带路径
     * @param stdin 标准输入。脚本里用 `input()` / `readline()` 时能从这儿拿到
     */
    suspend fun run(code: String, fileName: String, stdin: String = ""): Result<Outcome> =
        withContext(Dispatchers.IO) {
            val target = targetForFile(fileName) ?: targetForLanguage(fileName)
            ?: return@withContext Result.failure(
                IllegalArgumentException(
                    "不支持的文件类型：$fileName。" + supportedExtensionsHint()
                )
            )

            if (code.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("代码为空，没有可执行的内容"))
            }
            if (code.length > MAX_CODE_CHARS) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "代码过长（${code.length} 字符），上限 $MAX_CODE_CHARS 字符"
                    )
                )
            }

            runCatching {
                val payload = JSONObject().apply {
                    put("compiler", target.compiler)
                    put("code", preprocess(code, target))
                    if (stdin.isNotEmpty()) put("stdin", stdin)
                }
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("User-Agent", "PhoClaw/1.8 (Android)")
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body ?: error("执行服务返回了空响应")
                    if (body.contentLength() > MAX_RESPONSE_BYTES) {
                        error("执行服务返回内容过大")
                    }
                    val text = body.string()
                    if (!resp.isSuccessful) {
                        error("执行服务返回 HTTP ${resp.code}" + extractMessage(text))
                    }
                    parseOutcome(text, target, fileName)
                }
            }.recoverCatching { e ->
                when (e) {
                    is SocketTimeoutException -> throw IllegalStateException(
                        "执行超时（超过 ${READ_TIMEOUT_SECONDS} 秒）。可能是死循环，或编译时间过长。"
                    )
                    is java.net.UnknownHostException -> throw IllegalStateException(
                        "无法连接执行服务（域名解析失败），请检查网络。"
                    )
                    is javax.net.ssl.SSLException -> throw IllegalStateException(
                        "无法连接执行服务（HTTPS 握手失败）。"
                    )
                    is java.io.IOException -> throw IllegalStateException(
                        "网络错误：${e.message ?: e.javaClass.simpleName}"
                    )
                    else -> throw e
                }
            }
        }

    /** 探活，用于设置页的「测试执行服务」。 */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("compiler", "cpython-3.12.7")
            put("code", "print('ok')")
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "PhoClaw/1.8 (Android)")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            if (json.optString("status") == "0" && json.optString("program_output").contains("ok")) {
                "执行服务可用"
            } else {
                "执行服务异常：${json.optString("program_error").take(120)}"
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 上传前的源码修补。
     *
     * ## 为什么需要
     *
     * Wandbox 不接收文件名，它把源码固定写成 `prog.<ext>`。这对大多数语言
     * 无所谓，但 Java 有一条硬规则：**public 类的名字必须与文件名一致**。
     * 于是 `public class Main` 放进 `prog.java` 会直接编译失败：
     *
     * ```
     * prog.java:1: error: class Main is public, should be declared in a file named Main.java
     * ```
     *
     * 解法是去掉顶层类型的 `public` 修饰符 —— 包私有类不要求文件名匹配。
     * 只匹配「public + 可选的 final/abstract + class」，不会误伤
     * `public static void main`。
     */
    private fun preprocess(code: String, target: Target): String {
        if (!target.compiler.startsWith("openjdk")) return code
        return Regex("""\bpublic\s+(?=(?:final\s+|abstract\s+)?class\s)""").replace(code, "")
    }

    private fun parseOutcome(text: String, target: Target, fileName: String): Outcome {
        val json = JSONObject(text)

        // Wandbox 在服务端编译失败时仍然返回 HTTP 200，错误藏在
        // compiler_message / compiler_error 里，必须显式读出来，
        // 否则用户只会看到「退出码 1」而不知道哪里错了。
        val compileMessage = sequenceOf(
            json.optString("compiler_message"),
            json.optString("compiler_error"),
            json.optString("compiler_output")
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        return Outcome(
            compiler = target.compiler,
            displayName = target.displayName,
            fileName = fileName,
            status = json.optString("status", "-1").toIntOrNull() ?: -1,
            stdout = json.optString("program_output"),
            stderr = json.optString("program_error"),
            compileMessage = compileMessage,
            signal = json.optString("signal"),
            timedOut = false
        )
    }

    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val json = JSONObject(body)
            json.optString("message").ifBlank { body.take(160) }
        }.getOrElse { body.take(160) }
    }

    private fun supportedExtensionsHint(): String =
        "支持：${BY_EXTENSION.keys.sorted().joinToString(", ") { ".$it" }}"

    companion object {
        const val ENDPOINT = "https://wandbox.org/api/compile.json"

        private const val MAX_CODE_CHARS = 200_000
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val READ_TIMEOUT_SECONDS = 90

        private val JSON = "application/json; charset=utf-8".toMediaType()

        // ---------------------------------------------------------------- 语言表

        private val PY = Target("cpython-3.12.7", "Python 3.12", "main.py")
        private val JS = Target("nodejs-20.17.0", "Node.js 20", "main.js")
        private val TS = Target("typescript-5.6.2", "TypeScript 5.6", "main.ts")
        private val C = Target("gcc-13.2.0-c", "C (GCC 13)", "main.c")
        private val CPP = Target("gcc-13.2.0", "C++ (GCC 13)", "main.cpp")
        private val JAVA = Target("openjdk-jdk-21+35", "Java 21", "Main.java")
        private val GO = Target("go-1.23.2", "Go 1.23", "main.go")
        private val RUST = Target("rust-1.82.0", "Rust 1.82", "main.rs")
        private val BASH = Target("bash", "Bash", "main.sh")
        private val PHP = Target("php-8.3.12", "PHP 8.3", "main.php")
        private val RUBY = Target("ruby-3.4.9", "Ruby 3.4", "main.rb")
        private val LUA = Target("lua-5.4.7", "Lua 5.4", "main.lua")
        private val SWIFT = Target("swift-5.10.1", "Swift 5.10", "main.swift")
        private val CSHARP = Target("dotnetcore-8.0.402", "C# (.NET 8)", "Program.cs")
        private val SCALA = Target("scala-3.3.4", "Scala 3.3", "main.scala")
        private val HASKELL = Target("ghc-9.0.1", "Haskell (GHC 9.0)", "main.hs")
        private val PERL = Target("perl-5.42.0", "Perl 5.42", "main.pl")
        private val R = Target("r-4.4.1", "R 4.4", "main.r")
        private val D = Target("dmd-2.109.1", "D (DMD 2.109)", "main.d")
        private val ELIXIR = Target("elixir-1.17.3", "Elixir 1.17", "main.exs")
        private val ERLANG = Target("erlang-27.1", "Erlang 27", "main.erl")
        private val NIM = Target("nim-2.2.8", "Nim 2.2", "main.nim")
        private val OCAML = Target("ocaml-5.2.0", "OCaml 5.2", "main.ml")
        private val JULIA = Target("julia-1.10.5", "Julia 1.10", "main.jl")
        private val GROOVY = Target("groovy-4.0.23", "Groovy 4.0", "main.groovy")
        private val CRYSTAL = Target("crystal-1.13.3", "Crystal 1.13", "main.cr")

        /** 扩展名 → 目标。多写几个别名，模型生成的文件名不一定规范。 */
        private val BY_EXTENSION: Map<String, Target> = mapOf(
            "py" to PY, "pyw" to PY,
            "js" to JS, "mjs" to JS, "cjs" to JS,
            "ts" to TS, "mts" to TS,
            "c" to C, "h" to C,
            "cpp" to CPP, "cc" to CPP, "cxx" to CPP, "hpp" to CPP, "hxx" to CPP, "c++" to CPP,
            "java" to JAVA,
            "go" to GO,
            "rs" to RUST,
            "sh" to BASH, "bash" to BASH,
            "php" to PHP,
            "rb" to RUBY,
            "lua" to LUA,
            "swift" to SWIFT,
            "cs" to CSHARP,
            "scala" to SCALA,
            "hs" to HASKELL,
            "pl" to PERL, "pm" to PERL,
            "r" to R,
            "d" to D,
            "ex" to ELIXIR, "exs" to ELIXIR,
            "erl" to ERLANG,
            "nim" to NIM,
            "ml" to OCAML,
            "jl" to JULIA,
            "groovy" to GROOVY,
            "cr" to CRYSTAL
        )

        /** 语言名 → 目标，用于 `run python` + 内联代码。 */
        private val BY_NAME: Map<String, Target> = mapOf(
            "python" to PY, "python3" to PY, "py" to PY,
            "javascript" to JS, "js" to JS, "node" to JS, "nodejs" to JS,
            "typescript" to TS, "ts" to TS,
            "c" to C,
            "cpp" to CPP, "c++" to CPP, "cxx" to CPP,
            "java" to JAVA,
            "go" to GO, "golang" to GO,
            "rust" to RUST, "rs" to RUST,
            "bash" to BASH, "shell" to BASH, "sh" to BASH,
            "php" to PHP,
            "ruby" to RUBY, "rb" to RUBY,
            "lua" to LUA,
            "swift" to SWIFT,
            "csharp" to CSHARP, "c#" to CSHARP, "cs" to CSHARP,
            "scala" to SCALA,
            "haskell" to HASKELL, "hs" to HASKELL,
            "perl" to PERL,
            "r" to R,
            "d" to D,
            "elixir" to ELIXIR,
            "erlang" to ERLANG,
            "nim" to NIM,
            "ocaml" to OCAML,
            "julia" to JULIA,
            "groovy" to GROOVY,
            "crystal" to CRYSTAL
        )
    }
}

/**
 * 把执行结果整理成模型易读的文本。
 *
 * 分段展示而不是直接拼接：编译错误、标准输出、标准错误混在一起时，
 * 模型很难判断「这是编译失败还是程序自己打印的」。空结果也要说明，
 * 否则模型会以为是工具没返回。
 */
fun CodeRunnerClient.Outcome.formatForModel(): String = buildString {
    appendLine("===== 运行 $fileName（$displayName）=====")
    appendLine(
        when {
            timedOut -> "状态：超时"
            signal.isNotBlank() -> "状态：被信号 $signal 终止（退出码 $status）"
            status == 0 -> "状态：正常结束"
            else -> "状态：异常退出（退出码 $status）"
        }
    )

    if (compileMessage.isNotBlank()) {
        appendLine("--- 编译输出 ---")
        appendLine(clip(compileMessage))
    }
    if (stdout.isNotBlank()) {
        appendLine("--- 标准输出 ---")
        appendLine(clip(stdout))
    }
    if (stderr.isNotBlank()) {
        appendLine("--- 标准错误 ---")
        appendLine(clip(stderr))
    }
    if (compileMessage.isBlank() && stdout.isBlank() && stderr.isBlank()) {
        appendLine("（程序没有任何输出）")
    }
}.trimEnd()

private const val MAX_OUTPUT_CHARS = 20_000

private fun clip(text: String): String {
    val clean = text.trimEnd()
    return if (clean.length <= MAX_OUTPUT_CHARS) clean
    else clean.take(MAX_OUTPUT_CHARS) + "\n…（输出过长，已截断，共 ${clean.length} 字符）"
}
