package com.phoclaw.chat.data

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * cron 表达式解析器（零依赖手写）。
 *
 * 支持两种段数，按字段个数自动识别：
 * - **5 段**：`分 时 日 月 周`  —— 原版 Vixie cron
 * - **6 段**：`秒 分 时 日 月 周` —— Quartz / Spring 风格
 *
 * 每个字段支持：
 * - `*`            任意值
 * - `a-b`          范围
 * - `a,b,c`        列表（可与范围混用，如 `1-5,10`）
 * - `星号/n`        步长
 * - `a/n`          从 a 开始按 n 递增（等价于 `a-max/n`）
 * - `JAN`/`MON`    月份与星期的英文三字母缩写
 *
 * **不支持** Quartz 专有的 `?` `L` `W` `#`。这些字符出现时会**明确报错**而不是
 * 静默忽略 —— 静默接受一个自己实现不了的语义，会让用户以为任务已生效，
 * 实际却永远不触发，比直接报错危险得多。
 *
 * 关于 `?`：它是 Quartz 的「不指定」占位符，语义上等价于 `*`，
 * 所以单独做一次宽容处理（当作 `*`），并在 [CronExpression.warnings] 里提示。
 */
object CronParser {

    /** 解析结果 + 可能存在的宽容提示。 */
    data class Result(
        val expression: CronExpression?,
        val error: String? = null,
        val warnings: List<String> = emptyList()
    ) {
        val isValid: Boolean get() = expression != null
    }

    private val MONTH_NAMES = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )

    private val DOW_NAMES = mapOf(
        "SUN" to 0, "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6
    )

    fun parse(text: String): Result {
        val raw = text.trim()
        if (raw.isEmpty()) return Result(null, "表达式不能为空")

        val fields = raw.split(Regex("\\s+"))
        if (fields.size != 5 && fields.size != 6) {
            return Result(
                null,
                "需要 5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周），当前是 ${fields.size} 段"
            )
        }

        val hasSeconds = fields.size == 6
        val warnings = mutableListOf<String>()

        // 字段位置随段数浮动，用下标变量表达比写死数字清楚
        val secIdx = if (hasSeconds) 0 else -1
        val minIdx = if (hasSeconds) 1 else 0
        val hourIdx = if (hasSeconds) 2 else 1
        val domIdx = if (hasSeconds) 3 else 2
        val monIdx = if (hasSeconds) 4 else 3
        val dowIdx = if (hasSeconds) 5 else 4

        val seconds = if (hasSeconds) {
            parseField(fields[secIdx], 0, 59, null, warnings) ?: return Result(null, "秒字段非法：「${fields[secIdx]}」")
        } else null

        val minutes = parseField(fields[minIdx], 0, 59, null, warnings)
            ?: return Result(null, "分字段非法：「${fields[minIdx]}」")

        val hours = parseField(fields[hourIdx], 0, 23, null, warnings)
            ?: return Result(null, "时字段非法：「${fields[hourIdx]}」")

        val daysOfMonth = parseField(fields[domIdx], 1, 31, null, warnings)
            ?: return Result(null, "日字段非法：「${fields[domIdx]}」")

        val months = parseField(fields[monIdx], 1, 12, MONTH_NAMES, warnings)
            ?: return Result(null, "月字段非法：「${fields[monIdx]}」（可用 1-12 或 JAN-DEC）")

        // 星期：0 和 7 都表示周日，统一归一到 0
        val rawDow = parseField(fields[dowIdx], 0, 7, DOW_NAMES, warnings)
            ?: return Result(null, "周字段非法：「${fields[dowIdx]}」（可用 0-7 或 SUN-SAT）")
        val daysOfWeek = rawDow.map { if (it == 7) 0 else it }.toSet()

        // 判断「是否受限」用于日/周 OR 语义。`?` 被当成 `*`，所以也算不受限
        val domRestricted = !isWildcard(fields[domIdx])
        val dowRestricted = !isWildcard(fields[dowIdx])

        val expression = CronExpression(
            seconds = seconds ?: setOf(0),
            minutes = minutes,
            hours = hours,
            daysOfMonth = daysOfMonth,
            months = months,
            daysOfWeek = daysOfWeek,
            hasSeconds = hasSeconds,
            domRestricted = domRestricted,
            dowRestricted = dowRestricted,
            original = raw,
            warnings = warnings
        )
        return Result(expression, warnings = warnings)
    }

    /** 字段是否为「任意」（`星号`、`星号/n` 之外的空洞写法、或 Quartz 的 `?`）。 */
    private fun isWildcard(field: String): Boolean {
        val f = field.trim()
        return f == "*" || f == "?" || f.startsWith("*/")
    }

    /**
     * 解析单个字段为取值集合。
     *
     * @param names 可选的名称映射（月份 / 星期缩写），解析前把名字替换成数字
     * @return 解析失败返回 null，成功返回集合（可能为空表示无解，由调用方判断）
     */
    private fun parseField(
        field: String,
        min: Int,
        max: Int,
        names: Map<String, Int>?,
        warnings: MutableList<String>
    ): Set<Int>? {
        var text = field.trim()
        if (text.isEmpty()) return null

        // Quartz 的 `?` 等价于 `*`，宽容处理但给出提示
        if (text == "?") {
            warnings += "`?` 已按 `*` 解释"
            text = "*"
        }

        // Quartz 的 L / W / # 我们不支持，明确拒绝
        if (text.any { it == 'L' || it == 'W' || it == '#' }) {
            return null
        }

        // 名称替换：JAN → 1。用词边界避免把 MON 替换进 MONDAY 这种误伤
        names?.forEach { (name, value) ->
            text = text.replace(Regex("(?i)\\b$name\\b"), value.toString())
        }

        val result = mutableSetOf<Int>()

        for (part in text.split(',')) {
            val piece = part.trim()
            if (piece.isEmpty()) return null

            // 拆出可选的步长：`a-b/n`、`星号/n`、`a/n`
            val slash = piece.indexOf('/')
            val rangePart: String
            val step: Int
            if (slash >= 0) {
                rangePart = piece.substring(0, slash).trim()
                step = piece.substring(slash + 1).trim().toIntOrNull() ?: return null
                if (step <= 0) return null
            } else {
                rangePart = piece
                step = 1
            }

            // 把 rangePart 展开成 [from, to]
            val from: Int
            val to: Int
            when {
                rangePart == "*" -> {
                    from = min; to = max
                }
                rangePart.contains('-') -> {
                    val bounds = rangePart.split('-', limit = 2)
                    from = bounds[0].trim().toIntOrNull() ?: return null
                    to = bounds[1].trim().toIntOrNull() ?: return null
                }
                else -> {
                    // 单个值。带步长时 `a/n` 表示从 a 到 max 每 n 一个
                    val single = rangePart.toIntOrNull() ?: return null
                    from = single
                    to = if (slash >= 0) max else single
                }
            }

            if (from < min || from > max || to < min || to > max) return null
            if (from > to) return null

            var v = from
            while (v <= to) {
                result += v
                v += step
            }
        }

        return result
    }
}

/**
 * 解析完成的 cron 表达式。
 *
 * 时间字段都是预计算好的集合，[nextAfter] 直接查集合判定，不需要重复解析字符串。
 */
data class CronExpression(
    val seconds: Set<Int>,
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    val daysOfWeek: Set<Int>,
    /** 是否为 6 段（含秒）表达式。 */
    val hasSeconds: Boolean,
    /** 日字段是否被显式限制（非 `*`）。 */
    val domRestricted: Boolean,
    /** 周字段是否被显式限制（非 `*`）。 */
    val dowRestricted: Boolean,
    val original: String,
    val warnings: List<String> = emptyList()
) {

    /**
     * 求 [from] 之后的下一个触发时刻。
     *
     * **日与周同时被限制时取 OR**（Vixie cron 标准语义）：
     * `0 0 1 * 1` 表示「每月 1 号 **或** 每周一」，而不是两者同时满足。
     * 这是最容易搞错的一处，`man 5 crontab` 里有明确说明。
     *
     * 算法不是逐秒递增（`0 0 1 1 *` 逐秒找要算几千万次），而是
     * **按 月→日→时→分→秒 逐级向上对齐到下一个合法值**。
     * 每级最多进位一次，几十次循环就收敛了。
     *
     * @return 下一个触发时刻；表达式永不触发（如 `0 0 30 2 *`）时返回 null
     */
    fun nextAfter(from: LocalDateTime): LocalDateTime? {
        // 搜索上限 4 年。既兜住 2 月 30 日这类无解组合，
        // 也避免在极端表达式上死循环
        val limit = from.plusYears(4)

        // 从下一秒开始 —— nextAfter 的语义是「严格晚于 from」。
        // 秒归零不够：`from` 本身带毫秒时，（比如 09:00:00.500）
        // 直接 plusSeconds(1) 会得到 09:00:01.500，需要先剥掉纳秒
        var t = from.withNano(0).plusSeconds(1)

        // 循环次数的硬上限。正常表达式几十次内必定收敛；
        // 真出现病态输入时宁可返回 null 也不要卡死调用方（前台服务里卡死 = ANR）
        var guard = 0

        while (t.isBefore(limit)) {
            if (++guard > MAX_ITERATIONS) return null

            // -------- 月
            if (t.monthValue !in months) {
                // 跳到下个月 1 号 0 点 0 分 0 秒
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay()
                continue
            }

            // -------- 日（含 OR 语义）
            if (!matchesDay(t)) {
                // 跳到次日 0 点，重新从月日判起
                t = t.toLocalDate().plusDays(1).atStartOfDay()
                continue
            }

            // -------- 时
            if (t.hour !in hours) {
                // 跳到下一个整点，分秒归零。
                // 若跨到第二天，直接把时间设成次日 0 点即可 —— 下一轮循环的
                // 「日」判断会重新校验，不需要在这里特殊处理
                val next = t.plusHours(1).withMinute(0).withSecond(0).withNano(0)
                t = if (next.toLocalDate() != t.toLocalDate()) next.toLocalDate().atStartOfDay()
                else next
                continue
            }

            // -------- 分
            if (t.minute !in minutes) {
                // 跳到下一分钟，秒归零。跨小时时退回整点，交给上面重新判
                val next = t.plusMinutes(1).withSecond(0).withNano(0)
                t = if (next.toLocalDate() != t.toLocalDate()) next.toLocalDate().atStartOfDay()
                else next
                continue
            }

            // -------- 秒
            //
            // 这段必须**无条件**执行，不能加 `hasSeconds &&` 的门。
            // 5 段表达式的 seconds 恒为 {0}，但 t 的秒未必是 0 ——
            // 比如 `0 9 * * *` 从 09:00:01 出发，分和时都命中，
            // 若不在这里把秒推进到合法值，就会一路每秒 +1 地空转到上限，
            // 或者错误地把 09:00:02 当成命中结果返回。
            if (t.second !in seconds) {
                val next = t.plusSeconds(1).withNano(0)
                // 跨分钟了就归零秒，让上面的「分」判断接手
                t = if (next.minute != t.minute) next.withSecond(0) else next
                continue
            }

            // 逐级全部命中，就是它了
            return t
        }
        return null
    }

    /**
     * 判断某天是否命中「日 / 月 / 周」的组合条件。
     *
     * 三个分支严格对应 Vixie cron 的 OR 规则：
     * - 两者都限制 → OR
     * - 只限制一个 → 用被限制的那个
     * - 都不限制 → 恒真
     */
    private fun matchesDay(t: LocalDateTime): Boolean {
        val domHit = t.dayOfMonth in daysOfMonth
        val dowValue = t.dayOfWeek.toCronValue()
        val dowHit = dowValue in daysOfWeek

        return when {
            domRestricted && dowRestricted -> domHit || dowHit
            domRestricted -> domHit
            dowRestricted -> dowHit
            else -> true
        }
    }

    /**
     * 连续求未来 [count] 次触发时刻，供界面预览。
     *
     * 每次都在上一次结果之后再找，所以 6 段表达式不会重复返回同一秒。
     */
    fun upcoming(from: LocalDateTime = LocalDateTime.now(), count: Int = 3): List<LocalDateTime> {
        val list = mutableListOf<LocalDateTime>()
        var cursor = from
        repeat(count) {
            val next = nextAfter(cursor) ?: return list
            list += next
            cursor = next
        }
        return list
    }

    /**
     * 人类可读的简短描述，放在任务卡片上。
     *
     * 只覆盖最常见的几种，其余原样返回表达式 —— 硬凑一句话描述
     * 反而容易说错，不如让用户看表达式本身。
     */
    fun describe(): String {
        val s = if (hasSeconds) seconds.sorted() else listOf(0)
        // 只处理「单一秒值」的情况，其余一律退回展示原表达式
        if (hasSeconds && s.size != 1) return original
        if (s.firstOrNull() != 0) return original

        val min = minutes.sorted()
        val hour = hours.sorted()

        // 每分钟
        if (!domRestricted && !dowRestricted && min.size == 60 && hour.size == 24) {
            return if (hasSeconds) "每 $original" else "每分钟"
        }

        // 每小时的第 n 分
        if (min.size == 1 && hour.size == 24 && !domRestricted && !dowRestricted) {
            return "每小时的第 ${min[0]} 分"
        }

        // 每天固定时刻 —— 最实用的一档
        if (min.size == 1 && hour.size == 1 && !domRestricted && !dowRestricted) {
            return "每天 %02d:%02d".format(hour[0], min[0])
        }

        // 工作日固定时刻
        if (min.size == 1 && hour.size == 1 && !domRestricted &&
            daysOfWeek == setOf(1, 2, 3, 4, 5)
        ) {
            return "工作日 %02d:%02d".format(hour[0], min[0])
        }

        // 每周固定星期几固定时刻
        if (min.size == 1 && hour.size == 1 && !domRestricted && daysOfWeek.size == 1) {
            val day = DAY_LABELS[daysOfWeek.first()] ?: return original
            return "每周$day %02d:%02d".format(hour[0], min[0])
        }

        // 每月固定日期固定时刻
        if (min.size == 1 && hour.size == 1 && domRestricted && !dowRestricted && daysOfMonth.size == 1) {
            return "每月 ${daysOfMonth.first()} 日 %02d:%02d".format(hour[0], min[0])
        }

        return original
    }
}

private fun DayOfWeek.toCronValue(): Int = when (this) {
    DayOfWeek.SUNDAY -> 0
    DayOfWeek.MONDAY -> 1
    DayOfWeek.TUESDAY -> 2
    DayOfWeek.WEDNESDAY -> 3
    DayOfWeek.THURSDAY -> 4
    DayOfWeek.FRIDAY -> 5
    DayOfWeek.SATURDAY -> 6
}

private val DAY_LABELS = mapOf(
    0 to "日", 1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六"
)

/**
 * `nextAfter` 递进循环的硬上限。
 *
 * 正常情况下按「月→日→时→分→秒」逐级对齐，几十次必然收敛。
 * 这个上限是防御性的：万一有没考虑到的输入组合导致不收敛，
 * 返回 null（= 不触发）远好过在前台服务里死循环 —— 那会直接 ANR。
 */
private const val MAX_ITERATIONS = 1000