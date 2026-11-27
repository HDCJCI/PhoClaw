package com.phoclaw.chat.util

/**
 * 极简语法着色。
 *
 * 不引 [Prism4j] / [highlights] 这类库的理由：它们为了覆盖 200 种语言，
 * 体积从几 MB 起，而且大多依赖 ANTLR 运行时。这个应用里代码块的用途是
 * 「读一眼 AI 给的代码」，需要的是关键字、字符串、注释、数字四种颜色 ——
 * 一个手写扫描器两百行就能到 90% 的观感，还不增加依赖。
 *
 * 只做**词法**，不做语法分析。所以像 `if` 在字符串里不会被误着色（扫描到
 * 引号就整段吃掉），但像「宏定义里的关键字」这种需要语义的场景不做处理。
 *
 * 输出是 [Segment] 列表而不是带样式的字符串：颜色属于主题，应该由 UI 层决定。
 * 这样同一份着色结果在深色 / 浅色下都能用，也便于单独测试。
 */
object SyntaxHighlighter {

    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE, META }

    data class Segment(val text: String, val kind: Kind)

    /** 一段注释 / 字符串最长吃到多少字符 —— 防止未闭合的引号把整个文件染成一种颜色。 */
    private const val MAX_RUN = 4000

    fun highlight(code: String, language: String): List<Segment> {
        val lang = resolve(language) ?: return listOf(Segment(code, Kind.PLAIN))
        if (code.isEmpty()) return emptyList()

        val out = ArrayList<Segment>(32)
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += Segment(plain.toString(), Kind.PLAIN)
                plain.clear()
            }
        }

        fun emit(text: String, kind: Kind) {
            flushPlain()
            // 同类型的相邻片段合并，减少 AnnotatedString 里的 Span 数量
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) {
                out[out.lastIndex] = last.copy(text = last.text + text)
            } else {
                out += Segment(text, kind)
            }
        }

        while (i < code.length) {
            val c = code[i]

            // ---- 行注释 ----
            val lineMark = lang.lineComments.firstOrNull { code.startsWith(it, i) }
            if (lineMark != null) {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                emit(code.substring(i, end), Kind.COMMENT)
                i = end
                continue
            }

            // ---- 块注释 ----
            val block = lang.blockComment
            if (block != null && code.startsWith(block.first, i)) {
                val close = code.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) minOf(code.length, i + MAX_RUN) else close + block.second.length
                emit(code.substring(i, end), Kind.COMMENT)
                i = end
                continue
            }

            // ---- 字符串 ----
            if (c in lang.quotes) {
                // 三引号（Python 的 """ / '''）优先，否则 " 会被当成空串
                val triple = lang.tripleQuotes && i + 2 < code.length &&
                        code[i + 1] == c && code[i + 2] == c
                val quote = if (triple) "$c$c$c" else c.toString()
                val end = findStringEnd(code, i + quote.length, quote, c, lang.escape)
                emit(code.substring(i, end), Kind.STRING)
                i = end
                continue
            }

            // ---- 数字 ----
            if (c.isDigit() && (i == 0 || !isIdentChar(code[i - 1]))) {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                emit(code.substring(i, j), Kind.NUMBER)
                i = j
                continue
            }

            // ---- 标识符 / 关键字 ----
            if (c.isLetter() || c == '_' || c == '@' || c == '$') {
                var j = i
                while (j < code.length && isIdentChar(code[j])) j++
                val word = code.substring(i, j)
                val bare = word.trimStart('@', '$')
                val probe = if (lang.caseInsensitive) bare.lowercase() else bare
                val keywordSet = if (lang.caseInsensitive) lang.lowerKeywords else lang.keywords
                val typeSet = if (lang.caseInsensitive) lang.lowerTypes else lang.types
                val kind = when {
                    probe in keywordSet -> Kind.KEYWORD
                    probe in typeSet -> Kind.TYPE
                    lang.meta.contains(bare) && bare != word -> Kind.META
                    else -> Kind.PLAIN
                }
                if (kind == Kind.PLAIN) plain.append(word) else emit(word, kind)
                i = j
                continue
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return out
    }

    /**
     * 从 [start] 开始找字符串的结束引号。
     *
     * 换行会终止单行字符串 —— 这是刻意的容错：模型流式输出时经常先写一个
     * 半个字符串，如果一路吃到文件末尾，整段代码都会变成字符串色。
     */
    private fun findStringEnd(code: String, start: Int, quote: String, q: Char, escape: Boolean): Int {
        var i = start
        val limit = minOf(code.length, start + MAX_RUN)
        while (i < limit) {
            val c = code[i]
            if (escape && c == '\\') { i += 2; continue }
            if (code.startsWith(quote, i)) return i + quote.length
            if (quote.length == 1 && c == '\n') return i      // 未闭合的单行字符串
            i++
        }
        return limit
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    // ------------------------------------------------------------------ 语言表

    private class Lang(
        val keywords: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
        val meta: Set<String> = emptySet(),
        val lineComments: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val quotes: Set<Char> = setOf('"', '\''),
        val tripleQuotes: Boolean = false,
        val escape: Boolean = true,
        /**
         * 关键字比对忽略大小写。
         *
         * SQL、CSS、HTML 都是大小写不敏感的（`SELECT` / `select` / `Select`
         * 一个意思），而代码里习惯写大写。不做这一步的话，
         * 满屏大写的 SQL 会一个字都高亮不出来。
         */
        val caseInsensitive: Boolean = false
    ) {
        /** 小写化后的关键字集合，[caseInsensitive] 为 true 时用。 */
        val lowerKeywords: Set<String> by lazy { keywords.mapTo(HashSet()) { it.lowercase() } }
        val lowerTypes: Set<String> by lazy { types.mapTo(HashSet()) { it.lowercase() } }
    }

    /** 语言别名归一。写 `c++`、`cplusplus`、`cpp` 都认。 */
    private fun canon(language: String): String = when (language.trim().lowercase()
        .removePrefix("language-").removePrefix("lang-")) {
        "kt", "kts", "kotlin" -> "kotlin"
        "js", "mjs", "cjs", "javascript", "node" -> "javascript"
        "ts", "typescript" -> "typescript"
        "py", "python", "python3" -> "python"
        "rb", "ruby" -> "ruby"
        "sh", "bash", "zsh", "shell", "console", "terminal" -> "shell"
        "yml", "yaml" -> "yaml"
        "rs", "rust" -> "rust"
        "go", "golang" -> "go"
        "c++", "cpp", "cc", "cxx", "cplusplus" -> "cpp"
        "c#" , "cs", "csharp", "dotnet" -> "csharp"
        "objc", "objective-c" -> "cpp"
        "html", "htm", "xml", "svg", "xhtml" -> "markup"
        "css", "scss", "less" -> "css"
        "json", "jsonc" -> "json"
        "gradle", "groovy" -> "groovy"
        "sql", "mysql", "postgresql", "sqlite" -> "sql"
        "php" -> "php"
        "swift" -> "swift"
        "lua" -> "lua"
        "dart" -> "dart"
        "toml", "ini", "cfg", "conf", "properties", "env" -> "ini"
        "diff", "patch" -> "diff"
        "md", "markdown", "txt", "text", "plaintext", "" -> ""
        else -> language.trim().lowercase()
    }

    private val COMMON = setOf(
        "true", "false", "null", "nil", "None", "True", "False", "undefined", "NaN"
    )

    /*
     * 有三个语言需要被别的语言继承（TS 继承 JS、C++ 继承 C、C# 独立）。
     * 它们必须在 buildMap 之外先建好 —— 在 buildMap 的 lambda 里回头引用
     * LANGUAGES 会拿到一个还没赋值的 map，直接 NPE。
     */
    private val JS = Lang(
        keywords = COMMON + setOf(
            "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
            "break", "continue", "switch", "case", "default", "new", "delete", "typeof",
            "instanceof", "in", "of", "class", "extends", "super", "this", "try", "catch",
            "finally", "throw", "async", "await", "yield", "import", "export", "from", "as",
            "static", "get", "set", "void"
        ),
        types = setOf("String", "Number", "Boolean", "Object", "Array", "Function", "Promise",
            "Map", "Set", "Date", "RegExp", "Error", "JSON", "Math", "console", "window", "document"),
        quotes = setOf('"', '\'', '`')
    )

    private val C = Lang(
        keywords = COMMON + setOf(
            "auto", "break", "case", "const", "continue", "default", "do", "else", "enum",
            "extern", "for", "goto", "if", "inline", "register", "restrict", "return", "sizeof",
            "static", "struct", "switch", "typedef", "union", "volatile", "while"
        ),
        types = setOf("int", "char", "float", "double", "void", "long", "short", "unsigned",
            "signed", "size_t", "ssize_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "int8_t", "int16_t", "int32_t", "int64_t", "bool", "FILE"),
        meta = setOf("include", "define", "ifdef", "ifndef", "endif", "pragma")
    )

    private val LANGUAGES: Map<String, Lang> = buildMap {
        put("kotlin", Lang(
            keywords = COMMON + setOf(
                "package", "import", "class", "object", "interface", "fun", "val", "var", "typealias",
                "if", "else", "when", "is", "as", "in", "out", "return", "break", "continue", "throw",
                "try", "catch", "finally", "for", "while", "do", "this", "super", "constructor", "init",
                "companion", "data", "sealed", "enum", "abstract", "open", "override", "private",
                "protected", "public", "internal", "inline", "suspend", "operator", "infix", "tailrec",
                "vararg", "lateinit", "const", "by", "where", "reified", "crossinline", "noinline",
                "annotation", "expect", "actual", "get", "set", "field", "it"
            ),
            types = setOf(
                "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String", "Any",
                "Unit", "Nothing", "Array", "List", "MutableList", "Map", "MutableMap", "Set",
                "MutableSet", "Pair", "Triple", "Sequence", "Flow", "Result", "Lazy", "Regex"
            ),
            meta = setOf("Suppress", "Deprecated", "JvmStatic", "JvmField", "Test", "Composable")
        ))

        put("java", Lang(
            keywords = COMMON + setOf(
                "package", "import", "class", "interface", "enum", "record", "extends", "implements",
                "public", "private", "protected", "static", "final", "abstract", "synchronized",
                "volatile", "transient", "native", "strictfp", "sealed", "permits", "new", "return",
                "if", "else", "switch", "case", "default", "break", "continue", "for", "while", "do",
                "try", "catch", "finally", "throw", "throws", "this", "super", "instanceof", "var",
                "void", "yield", "assert"
            ),
            types = setOf(
                "int", "long", "short", "byte", "float", "double", "boolean", "char", "String",
                "Object", "Integer", "Long", "Double", "Boolean", "List", "Map", "Set", "ArrayList",
                "HashMap", "Optional", "Stream", "Exception", "RuntimeException"
            ),
            meta = setOf("Override", "Deprecated", "SuppressWarnings", "FunctionalInterface", "Test")
        ))

        put("python", Lang(
            keywords = setOf(
                "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
                "pass", "import", "from", "as", "try", "except", "finally", "raise", "with", "lambda",
                "yield", "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or",
                "async", "await", "match", "case", "True", "False", "None", "self", "cls"
            ),
            types = setOf("int", "str", "float", "bool", "list", "dict", "tuple", "set", "bytes",
                "Exception", "ValueError", "TypeError", "KeyError", "Any", "Optional", "Union"),
            meta = setOf("__init__", "__main__", "__name__", "__str__", "__repr__"),
            lineComments = listOf("#"),
            blockComment = null,
            tripleQuotes = true
        ))

        put("javascript", JS)

        put("typescript", Lang(
            keywords = JS.keywords + setOf(
                "interface", "type", "enum", "namespace", "declare", "readonly", "public",
                "private", "protected", "abstract", "implements", "satisfies", "keyof", "infer"
            ),
            types = JS.types + setOf("any", "unknown", "never", "string", "number", "boolean",
                "Record", "Partial", "Pick", "Omit"),
            quotes = JS.quotes
        ))

        put("go", Lang(
            keywords = COMMON + setOf(
                "package", "import", "func", "var", "const", "type", "struct", "interface", "map",
                "chan", "go", "defer", "select", "case", "default", "switch", "if", "else", "for",
                "range", "break", "continue", "return", "goto", "fallthrough"
            ),
            types = setOf("int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16",
                "uint32", "uint64", "float32", "float64", "string", "bool", "byte", "rune", "error",
                "any", "nil", "true", "false"),
            quotes = setOf('"', '\'', '`'),
            tripleQuotes = false
        ))

        put("rust", Lang(
            keywords = COMMON + setOf(
                "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "mod",
                "use", "pub", "crate", "super", "self", "Self", "match", "if", "else", "loop",
                "while", "for", "in", "break", "continue", "return", "async", "await", "move",
                "dyn", "where", "unsafe", "ref", "as", "type"
            ),
            types = setOf("i8", "i16", "i32", "i64", "i128", "u8", "u16", "u32", "u64", "u128",
                "f32", "f64", "usize", "isize", "bool", "char", "str", "String", "Vec", "Option",
                "Result", "Box", "Rc", "Arc", "HashMap"),
            meta = setOf("derive", "cfg", "test", "inline")
        ))

        put("c", C)

        put("cpp", Lang(
            keywords = C.keywords + setOf(
                "class", "namespace", "template", "typename", "using", "public", "private",
                "protected", "virtual", "override", "final", "new", "delete", "this", "throw",
                "try", "catch", "constexpr", "nullptr", "noexcept", "explicit", "friend", "operator",
                "concept", "requires", "co_await", "co_return", "co_yield"
            ),
            types = C.types + setOf("string", "vector", "map", "set", "unordered_map",
                "unique_ptr", "shared_ptr", "optional", "pair", "array", "std"),
            meta = C.meta
        ))

        put("csharp", Lang(
            keywords = COMMON + setOf(
                "using", "namespace", "class", "struct", "interface", "enum", "record", "public",
                "private", "protected", "internal", "static", "readonly", "const", "virtual",
                "override", "abstract", "sealed", "async", "await", "var", "new", "return", "if",
                "else", "switch", "case", "default", "for", "foreach", "while", "do", "break",
                "continue", "try", "catch", "finally", "throw", "this", "base", "get", "set",
                "partial", "where", "yield", "in", "out", "ref", "params"
            ),
            types = setOf("int", "long", "short", "byte", "float", "double", "decimal", "bool",
                "char", "string", "object", "dynamic", "void", "Task", "List", "Dictionary",
                "IEnumerable", "Exception", "Action", "Func")
        ))

        put("ruby", Lang(
            keywords = setOf(
                "def", "class", "module", "end", "if", "elsif", "else", "unless", "while", "until",
                "for", "in", "do", "begin", "rescue", "ensure", "raise", "return", "yield", "break",
                "next", "redo", "retry", "self", "nil", "true", "false", "and", "or", "not", "then",
                "require", "require_relative", "attr_accessor", "attr_reader", "attr_writer", "puts"
            ),
            types = setOf("String", "Integer", "Float", "Array", "Hash", "Symbol", "Proc", "Class"),
            lineComments = listOf("#"),
            blockComment = null
        ))

        put("php", Lang(
            keywords = COMMON + setOf(
                "function", "class", "interface", "trait", "extends", "implements", "public",
                "private", "protected", "static", "abstract", "final", "const", "new", "return",
                "if", "else", "elseif", "for", "foreach", "while", "do", "switch", "case",
                "default", "break", "continue", "try", "catch", "finally", "throw", "use",
                "namespace", "require", "include", "echo", "print", "as", "instanceof", "global"
            ),
            types = setOf("int", "float", "string", "bool", "array", "object", "callable", "void",
                "null", "true", "false", "self", "static")
        ))

        put("swift", Lang(
            keywords = COMMON + setOf(
                "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "import",
                "return", "if", "else", "guard", "switch", "case", "default", "for", "in", "while",
                "repeat", "break", "continue", "defer", "do", "try", "catch", "throw", "throws",
                "public", "private", "internal", "fileprivate", "open", "static", "final",
                "mutating", "init", "deinit", "self", "Self", "super", "where", "async", "await",
                "some", "any", "inout", "lazy", "weak", "unowned"
            ),
            types = setOf("Int", "Double", "Float", "String", "Bool", "Character", "Array",
                "Dictionary", "Set", "Optional", "Result", "Error", "Void", "Any")
        ))

        put("lua", Lang(
            keywords = setOf(
                "function", "local", "end", "if", "then", "elseif", "else", "for", "while", "do",
                "repeat", "until", "return", "break", "goto", "in", "and", "or", "not", "nil",
                "true", "false", "require"
            ),
            types = setOf("string", "number", "table", "boolean", "pairs", "ipairs", "print"),
            lineComments = listOf("--"),
            blockComment = "--[[" to "]]"
        ))

        put("dart", Lang(
            keywords = COMMON + setOf(
                "class", "abstract", "extends", "implements", "with", "mixin", "enum", "final",
                "const", "var", "late", "static", "required", "factory", "get", "set", "async",
                "await", "yield", "Future", "Stream", "import", "export", "library", "part",
                "return", "if", "else", "for", "while", "do", "switch", "case", "default", "try",
                "catch", "finally", "throw", "new", "this", "super", "is", "as", "in"
            ),
            types = setOf("int", "double", "num", "String", "bool", "List", "Map", "Set",
                "Object", "dynamic", "void", "Future", "Stream")
        ))

        put("shell", Lang(
            keywords = setOf(
                "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until",
                "case", "esac", "function", "return", "exit", "local", "export", "readonly",
                "source", "alias", "unset", "shift", "eval", "exec", "set", "trap", "wait"
            ),
            types = setOf("echo", "cd", "ls", "cat", "grep", "sed", "awk", "find", "mkdir", "rm",
                "cp", "mv", "chmod", "chown", "curl", "wget", "git", "sudo", "apt", "npm", "node",
                "python", "java", "gradle", "docker", "kubectl"),
            lineComments = listOf("#"),
            blockComment = null,
            quotes = setOf('"', '\'', '`'),
            escape = true
        ))

        put("sql", Lang(
            keywords = setOf(
                "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
                "create", "table", "drop", "alter", "add", "column", "index", "primary", "key",
                "foreign", "references", "join", "inner", "left", "right", "outer", "on", "group",
                "by", "order", "having", "limit", "offset", "as", "and", "or", "not", "null",
                "is", "in", "between", "like", "distinct", "union", "all", "exists", "case", "when",
                "then", "else", "end", "desc", "asc", "count", "sum", "avg", "min", "max"
            ),
            types = setOf("int", "integer", "varchar", "text", "char", "boolean", "date",
                "timestamp", "decimal", "float", "double", "bigint", "serial", "uuid", "json"),
            lineComments = listOf("--"),
            blockComment = "/*" to "*/",
            caseInsensitive = true
        ))

        put("json", Lang(
            keywords = setOf("true", "false", "null"),
            lineComments = emptyList(),
            blockComment = null,
            quotes = setOf('"')
        ))

        put("yaml", Lang(
            keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
            lineComments = listOf("#"),
            blockComment = null,
            quotes = setOf('"', '\'')
        ))

        put("ini", Lang(
            keywords = emptySet(),
            lineComments = listOf("#", ";"),
            blockComment = null,
            quotes = setOf('"', '\'')
        ))

        put("groovy", Lang(
            keywords = COMMON + setOf(
                "def", "class", "interface", "extends", "implements", "public", "private",
                "protected", "static", "final", "return", "if", "else", "for", "while", "switch",
                "case", "default", "try", "catch", "finally", "throw", "new", "this", "super",
                "import", "package", "apply", "plugins", "dependencies", "repositories", "task"
            ),
            types = setOf("String", "int", "long", "boolean", "double", "List", "Map", "Set"),
            quotes = setOf('"', '\'', '/')
        ))

        put("css", Lang(
            keywords = emptySet(),
            types = setOf("important", "inherit", "initial", "unset", "auto", "none", "block",
                "flex", "grid", "absolute", "relative", "fixed", "sticky", "center", "bold"),
            lineComments = emptyList(),
            blockComment = "/*" to "*/",
            quotes = setOf('"', '\''),
            caseInsensitive = true
        ))

        // HTML / XML：靠 < > 分段，这里只把标签名与属性名标出来，够用了
        put("markup", Lang(
            keywords = setOf(
                "html", "head", "body", "div", "span", "p", "a", "img", "ul", "ol", "li", "table",
                "tr", "td", "th", "thead", "tbody", "h1", "h2", "h3", "h4", "h5", "h6", "br",
                "hr", "input", "button", "form", "label", "select", "option", "textarea", "script",
                "style", "link", "meta", "title", "nav", "header", "footer", "section", "article",
                "main", "aside", "svg", "path", "g", "rect", "circle", "defs", "use", "template"
            ),
            lineComments = emptyList(),
            blockComment = "<!--" to "-->",
            quotes = setOf('"', '\''),
            caseInsensitive = true
        ))

        put("diff", Lang(
            keywords = emptySet(),
            lineComments = listOf("+++", "---"),
            blockComment = null,
            quotes = emptySet()
        ))
    }

    private fun resolve(language: String): Lang? {
        val key = canon(language)
        if (key.isEmpty()) return null
        // 未知语言也当成「通用 C 风格」处理：至少字符串与注释能对上色，
        // 比整块灰掉好看，也不会因为不认识就完全没有层次
        return LANGUAGES[key] ?: LANGUAGES["javascript"]
    }

    /** 这个语言名值不值得显示在代码块左上角。 */
    fun label(language: String): String = language.trim().uppercase()
}
