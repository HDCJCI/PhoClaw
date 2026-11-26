package com.phoclaw.chat.data

import android.os.Build
import java.io.File

/**
 * 运行环境探测：这台设备能不能跑 shell，是不是鸿蒙。
 *
 * ## 为什么鸿蒙要单独判
 *
 * 鸿蒙（HarmonyOS）在 4.x 及之前兼容 APK，但它自己的沙箱规则和
 * Android 不完全一致：`/system/bin` 里的工具集、SELinux 策略、
 * 甚至 `ProcessBuilder` 的行为都可能不同。同一段命令在 Android 上
 * 稳定返回，在鸿蒙上可能直接抛 `IOException` 或者静默返回空。
 *
 * 与其让用户对着一个看不懂的报错反复试，不如**在入口处就砍掉这个功能**：
 * 界面上不显示开关，模型那边也会被告知「本设备不支持」。
 * 少一个功能，比多一个玄学 bug 好。
 *
 * ## 检测手段为什么是这几个
 *
 * 常见的 `Build.MANUFACTURER == "HUAWEI"` 判法会**误杀**所有华为安卓手机
 * （EMUI），而那些设备的 shell 是完全正常的。所以按「鸿蒙独有的痕迹」来判：
 *
 * 1. `hw_sc.build.os.version` —— 鸿蒙才有的系统属性，EMUI 没有；
 * 2. `ro.build.version.ohos` / `ro.build.harmony.version` —— OpenHarmony 系；
 * 3. `Build.DISPLAY` / `Build.VERSION.RELEASE` 里出现 harmony / ohos；
 * 4. `/system/bin/aa` 与 `/system/bin/bm` 同时存在 —— 鸿蒙的
 *    ability / bundle 管理工具，Android 上叫 `am` / `pm`。
 *
 * 四条里命中任意一条就判为鸿蒙。宁可错判（功能被藏起来，用户还能用 `run`
 * 走远程执行），也不要错放（命令直接崩在用户面前）。
 */
object DeviceEnv {

    /** 是否是鸿蒙设备。首次访问时探测一次并缓存。 */
    val isHarmonyOs: Boolean by lazy { detectHarmonyOs() }

    /**
     * 本机 shell 是否可用。
     *
     * 三个条件都要满足：不是鸿蒙、`/system/bin/sh` 存在且可执行、
     * 且探测命令能真的跑起来（有的定制系统上 `sh` 在位但一执行就被 SELinux 拒）。
     */
    val shellAvailable: Boolean by lazy { !isHarmonyOs && probeShell() }

    /** 给界面显示的一句话环境说明。 */
    val shellUnavailableReason: String?
        get() = when {
            isHarmonyOs ->
                "检测到鸿蒙系统，本机命令执行功能已禁用。鸿蒙的沙箱规则与 Android 不同，" +
                        "执行系统命令容易失败。需要跑代码可以用「代码执行」（远程）。"
            !shellAvailable ->
                "当前系统没有可用的 shell（/system/bin/sh），本机命令执行不可用。"
            else -> null
        }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 反射读系统属性。
     *
     * `android.os.SystemProperties` 是 @hide 类，在 Android 9+ 的 hidden API
     * 限制下不一定能反射成功 —— 所以整段用 runCatching 包着，
     * 读不到就当「不是鸿蒙」，交给其它信号决定。
     */
    private fun sysProp(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")

    private fun detectHarmonyOs(): Boolean {
        // ① 鸿蒙独有的系统属性
        if (sysProp("hw_sc.build.os.version").isNotBlank()) return true
        if (sysProp("ro.build.version.ohos").isNotBlank()) return true
        if (sysProp("ro.build.harmony.version").isNotBlank()) return true

        // ② 版本字符串里的痕迹
        val markers = listOf(
            Build.DISPLAY,
            Build.VERSION.RELEASE,
            System.getProperty("os.version")
        )
        val looksHarmony = markers.any { m ->
            val v = m?.lowercase() ?: return@any false
            v.contains("harmony") || v.contains("ohos")
        }
        if (looksHarmony) return true

        // ③ 鸿蒙的 ability / bundle 管理工具
        return File("/system/bin/aa").exists() && File("/system/bin/bm").exists()
    }

    /**
     * 真跑一条命令来确认 shell 可用。
     *
     * 只看文件存在是不够的：部分精简系统里 `/system/bin/sh` 在，
     * 但执行会被 SELinux 拒绝（`avc: denied`），表现出来就是
     * `IOException: Cannot run program`。与其等用户点了开关再报错，不如现在就试一次。
     */
    private fun probeShell(): Boolean {
        val sh = File("/system/bin/sh")
        if (!sh.exists()) return false
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", "echo ok")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out.trim() == "ok"
        }.getOrDefault(false)
    }
}
