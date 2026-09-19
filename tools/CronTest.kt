package com.phoclaw.chat.data

import java.time.LocalDateTime

/**
 * cron 解析器的独立验证程序。
 *
 * 不进 Android 工程，因为这只是开发期的自检 —— 但它验证的是整个
 * 自动化功能里最不能出错的一段逻辑：算错一次，用户的定时任务就会在
 * 错误的时间触发，而且很难被发现。
 *
 * 断言全部基于手算的期望值，不用「跑一遍看看输出对不对」这种自我印证。
 */
object CronTest {

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        // ---------------- 基础解析

        expectValid("0 9 * * *", "每天 9 点")
        expectValid("0 9 * * 1-5", "工作日 9 点")
        expectValid("*/30 * * * *", "每 30 分钟")
        expectValid("0 0 1 * *", "每月 1 号")
        expectValid("30 9 * * 1,3,5", "周一三五 9:30")
        expectValid("0 8-18/2 * * *", "8 点到 18 点每 2 小时")
        expectValid("0 0 1 JAN *", "每年 1 月 1 日，月份用名字")
        expectValid("0 0 * * SUN", "每周日，星期用名字")
        expectValid("0 0 0 1 1 *", "6 段：每年 1 月 1 日 00:00:00")
        expectValid("*/10 * * * * *", "6 段：每 10 秒")

        // ---------------- 非法输入必须报错

        expectInvalid("", "空表达式")
        expectInvalid("0 9 * *", "只有 4 段")
        expectInvalid("0 9 * * * * *", "有 7 段")
        expectInvalid("60 9 * * *", "分钟越界 60")
        expectInvalid("0 24 * * *", "小时越界 24")
        expectInvalid("0 0 32 * *", "日越界 32")
        expectInvalid("0 0 * 13 *", "月越界 13")
        expectInvalid("0 0 * * 8", "周越界 8")
        expectInvalid("0 0 L * *", "Quartz 的 L 不支持")
        expectInvalid("0 0 15W * *", "Quartz 的 W 不支持")
        expectInvalid("0 0 * * 1#2", "Quartz 的 # 不支持")
        expectInvalid("abc * * * *", "非数字")
        expectInvalid("5-1 * * * *", "范围反了")

        // ---------------- nextAfter 核心算法

        // 每天 9 点：从 8:59:59 出发应该是当天 9:00
        expectNext("0 9 * * *", "2026-09-16T08:59:59", "2026-09-16T09:00")
        // 从 9:00:01 出发应该是第二天 9:00
        expectNext("0 9 * * *", "2026-09-16T09:00:01", "2026-09-17T09:00")
        // 刚好在 9:00:00 时，nextAfter 语义是「严格晚于」，所以是明天
        expectNext("0 9 * * *", "2026-09-16T09:00:00", "2026-09-17T09:00")

        // 复合条件必须逐级进位：9 月 16 日是周三，
        // 工作日 9 点从周三 10:00 出发 → 周四 9:00
        expectNext("0 9 * * 1-5", "2026-09-16T10:00", "2026-09-17T09:00")
        // 周五 10:00 出发 → 下周一 9:00（要跨过整个周末）
        expectNext("0 9 * * 1-5", "2026-09-18T10:00", "2026-09-21T09:00")

        // 每月 1 号 0 点：9 月 16 日出发 → 10 月 1 日
        expectNext("0 0 1 * *", "2026-09-16T21:15", "2026-10-01T00:00")

        // 每年 1 月 1 日：这是逐秒遍历会算几千万次的最坏情况
        expectNext("0 0 1 1 *", "2026-09-16T21:15", "2027-01-01T00:00")

        // 每 30 分钟：9:15 出发 → 9:30
        expectNext("*/30 * * * *", "2026-09-16T09:15", "2026-09-16T09:30")
        expectNext("*/30 * * * *", "2026-09-16T09:30", "2026-09-16T10:00")

        // 6 段带秒：从 09:15:30 出发 → 09:20:00
        expectNext("0 */5 * * * *", "2026-09-16T09:15:30", "2026-09-16T09:20:00")
        // 每 10 秒
        expectNext("*/10 * * * * *", "2026-09-16T09:15:03", "2026-09-16T09:15:10")

        // 月份名字
        expectNext("0 0 1 JAN *", "2026-09-16T21:15", "2027-01-01T00:00")
        // 星期名字：每周日（2026-09-20 是周日）
        expectNext("0 12 * * SUN", "2026-09-16T21:15", "2026-09-20T12:00")

        // ---------------- 日/周 OR 语义（Vixie cron 标准）

        // `0 0 1 * 1` = 每月 1 号 **或** 每周一
        // 2026-09-16 是周三 → 下一个周一是 9-21，下一个 1 号是 10-01
        // 所以答案应该是 9-21（更早的那个）
        expectNext("0 0 1 * 1", "2026-09-16T21:15", "2026-09-21T00:00")
        // 从 9-22 出发 → 下一个是 10-01（10月1日是周四，比下周一 9-28 晚，
        // 但等等：9-28 是周一，比 10-01 早，所以应该是 9-28）
        expectNext("0 0 1 * 1", "2026-09-22T12:00", "2026-09-28T00:00")

        // 如果日和周都是 `*`，应该每天触发
        expectNext("0 3 * * *", "2026-09-16T21:15", "2026-09-17T03:00")

        // ---------------- 无解表达式

        expectNever("0 0 30 2 *", "2 月 30 日不存在")

        // ---------------- describe() 的人类可读描述

        expectDescribe("0 9 * * *", "每天 09:00")
        expectDescribe("0 8 * * 1-5", "工作日 08:00")
        expectDescribe("0 * * * *", "每小时的第 0 分")

        // ---------------- upcoming() 连续预览

        val expr = CronParser.parse("0 9 * * *").expression
        if (expr != null) {
            val list = expr.upcoming(LocalDateTime.parse("2026-09-16T08:00"), 3)
            val expected = listOf(
                LocalDateTime.parse("2026-09-16T09:00"),
                LocalDateTime.parse("2026-09-17T09:00"),
                LocalDateTime.parse("2026-09-18T09:00")
            )
            assertEq("upcoming 连续三次", expected, list)
        } else {
            fail("upcoming 测试无法解析表达式")
        }

        // ---------------- `?` 的宽容处理

        val q = CronParser.parse("0 6 ? * *")
        assertTrue("`?` 应当被接受并当作 `*`", q.isValid)
        assertTrue("`?` 应当产生 warning", q.warnings.isNotEmpty())

        println()
        println("========================================")
        println("通过 $passed 项，失败 $failed 项")
        println("========================================")
        if (failed > 0) System.exit(1)
    }

    // ------------------------------------------------------------ 断言工具

    private fun expectValid(cron: String, label: String) {
        val r = CronParser.parse(cron)
        when {
            !r.isValid -> fail("[$label] 「$cron」应当合法，却报错：${r.error}")
            else -> pass("[$label] 「$cron」解析成功")
        }
    }

    private fun expectInvalid(cron: String, label: String) {
        val r = CronParser.parse(cron)
        when {
            r.isValid -> fail("[$label] 「$cron」应当报错，却解析成功了")
            else -> pass("[$label] 「$cron」正确报错：${r.error}")
        }
    }

    private fun expectNext(cron: String, from: String, expected: String) {
        val expr = CronParser.parse(cron).expression
        if (expr == null) {
            fail("「$cron」解析失败，无法测 nextAfter")
            return
        }
        val actual = expr.nextAfter(LocalDateTime.parse(from))
        val want = LocalDateTime.parse(expected)
        if (actual == want) {
            pass("「$cron」从 $from → $actual")
        } else {
            fail("「$cron」从 $from 期望 $want，实际 $actual")
        }
    }

    private fun expectNever(cron: String, label: String) {
        val expr = CronParser.parse(cron).expression
        if (expr == null) {
            // 解析阶段就报错也算正确处理
            pass("[$label] 「$cron」在解析阶段即被拒绝")
            return
        }
        val next = expr.nextAfter(LocalDateTime.parse("2026-09-16T00:00"))
        if (next == null) {
            pass("[$label] 「$cron」正确返回「永不触发」")
        } else {
            fail("[$label] 「$cron」应当永不触发，却算出了 $next")
        }
    }

    private fun expectDescribe(cron: String, expected: String) {
        val expr = CronParser.parse(cron).expression
        if (expr == null) {
            fail("「$cron」解析失败，无法测 describe")
            return
        }
        val actual = expr.describe()
        if (actual == expected) {
            pass("「$cron」描述为「$actual」")
        } else {
            fail("「$cron」描述期望「$expected」，实际「$actual」")
        }
    }

    private fun assertEq(label: String, expected: Any?, actual: Any?) {
        if (expected == actual) pass("$label → $actual")
        else fail("$label 期望 $expected，实际 $actual")
    }

    private fun assertTrue(label: String, cond: Boolean) {
        if (cond) pass(label) else fail(label)
    }

    private fun pass(msg: String) {
        passed++
        println("  ✓ $msg")
    }

    private fun fail(msg: String) {
        failed++
        println("  ✗ $msg")
    }
}
