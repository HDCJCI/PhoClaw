#!/usr/bin/env bash
#
# cron 解析器的逻辑自检。
#
# 为什么需要它：cron 的语义细节（日/周的 OR 规则、逐级进位算法）极易写错，
# 而写错的后果是定时任务在错误的时间触发 —— 这种 bug 在真机上可能要等一天才暴露。
#
# 这份脚本在开发期就抓到过一个真实缺陷：5 段表达式（无秒）的秒字段校验被
# `hasSeconds &&` 短路掉了，导致 `0 9 * * *` 从 09:00:01 出发会返回 09:00:02，
# 也就是「9 点之后的同一分钟里反复触发」而不是等到第二天。
#
# 用法：
#   ./tools/verify-cron.sh
#
# 它会用 Kotlin 编译器把 CronExpression.kt 与 CronTest.kt 编到临时目录再跑，
# 编译器和依赖从 Maven Central（阿里云镜像）临时拉取并缓存到 /tmp。
# CronExpression.kt 本身只依赖 java.time，所以不需要 Android Runtime。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/phoclaw-cron-verify"
LIBS="$WORK/libs"

echo "==> 准备工作目录 $WORK"
mkdir -p "$WORK" "$LIBS"

# ------------------------------------------------------------------ 依赖
if [ ! -f "$LIBS/kotlin-compiler-2.0.20.jar" ]; then
  echo "==> 首次运行，拉取 Kotlin 编译器（约 60MB，之后会复用缓存）"
  mkdir -p "$WORK/fetch"
  cat > "$WORK/fetch/settings.gradle.kts" <<'EOF'
rootProject.name = "phoclaw-cron-verify"
EOF
  cat > "$WORK/fetch/build.gradle.kts" <<'EOF'
repositories { maven("https://maven.aliyun.com/repository/public") }
configurations { create("kc") }
dependencies { add("kc", "org.jetbrains.kotlin:kotlin-compiler:2.0.20") }
tasks.register("copyDeps") {
    val c = configurations.getByName("kc")
    doLast { copy { from(c); into(System.getenv("PHOCLAW_LIBS")) } }
}
EOF
  PHOCLAW_LIBS="$LIBS" gradle -p "$WORK/fetch" copyDeps --no-daemon -q
fi

CP="$(find "$LIBS" -name '*.jar' | tr '\n' ':')"
if [ -z "$CP" ]; then
  echo "!! 依赖下载失败" >&2
  exit 1
fi

# ------------------------------------------------------------------ 编译
echo "==> 编译"
rm -rf "$WORK/out"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$CP" \
  -nowarn \
  -d "$WORK/out" \
  "$ROOT/app/src/main/java/com/phoclaw/chat/data/CronExpression.kt" \
  "$ROOT/tools/CronTest.kt"

# ------------------------------------------------------------------ 运行
echo "==> 运行自检"
echo
java -cp "$WORK/out:$CP" com.phoclaw.chat.data.CronTest
