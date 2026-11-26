# 发布说明（Releases）

这个目录放的是 **GitHub Releases 页面用的正文**，每个版本一份，文件名就是 tag 名：

```
docs/releases/
├── README.md      ← 你正在看的这份：索引 + 发布流程
├── v1.11.0.md     ← tag v1.11.0 的 Release 正文，整段粘进 GitHub 即可
├── v1.10.0.md     ← 上一版
└── v1.9.0.md      ← 再上一版
```

和 `CHANGELOG.md` 的分工：

| 文件 | 给谁看 | 写什么 |
|---|---|---|
| `CHANGELOG.md` | 想查「哪一版改了什么」的人 | 全部 19 个版本，按新增 / 修复 / 变更分类，一条不落 |
| `docs/releases/v1.11.0.md` | 打开 Releases 页面的访客 | 只讲**这一版**：亮点、升级注意、已知限制、校验值 |

Release 正文不复述完整变更列表 —— 那是 CHANGELOG 的活。它回答的是「我该不该升、升了会怎样」。

---

## 当前版本

**v1.11.0** · 2026-11-26 · versionCode 20

> 设置页改为二级菜单：首页只留目录，六个入口各带状态摘要。

| 项 | 值 |
|---|---|
| 正文 | [`v1.11.0.md`](v1.11.0.md) |
| 附件 | `PhoClaw-v1.11.0-debug.apk` |
| 大小 | 19,378,427 字节（18.5 MiB） |
| MD5 | `7109a0972f7feed59b38985132423adc` |
| SHA-256 | `7f647722a36b58160a3954854248d7368d797d2a79a30592ec6e129c2a75d5b6` |

---

## 版本索引

| 版本 | 日期 | 主题 | versionCode | 正文 |
|---|---|---|---|---|
| **1.11.0** | 2026-11-26 | 设置页改为二级菜单 | 20 | [v1.11.0.md](v1.11.0.md) |
| 1.10.0 | 2026-11-26 | 模型档案 + 思考显示 + 本机命令 | 19 | [v1.10.0.md](v1.10.0.md) |
| 1.9.0 | 2026-11-01 | 长期记忆 + 上下文压缩 | 18 | [v1.9.0.md](v1.9.0.md) |
| 1.8.0 | 2026-11-01 | 代码执行 + 提示词自动生成 | 17 | — |
| 1.7.0 | 2026-09-29 | 网页抓取 + HTTP 请求 + 回到底部 | 16 | — |
| 1.6.8 | 2026-09-27 | 任务列表与图片显示修正 | 15 | — |
| 1.6.7 | 2026-09-27 | 链接与图片收尾 | 14 | — |
| 1.6.6 | 2026-09-27 | 补全 Markdown 渲染 | 13 | — |
| 1.6.5 | 2026-09-19 | 修文件后缀被吞 | 12 | — |
| 1.6.4 | 2026-09-19 | 修工作区换不掉 | 11 | — |
| 1.6.3 | — | 修界面被挤出屏幕 | 10 | — |
| 1.6.2 | — | 修底栏与键盘之间的空白 | 9 | — |
| 1.6.1 | — | 修输入法遮挡 | 8 | — |
| 1.6.0 | — | 联网搜索 | 7 | — |
| 1.5.0 | — | 技能 + 自动化 | 6 | — |
| 1.4.0 | — | 支持发送图片和代码文件 | 5 | — |
| 1.3.0 | — | 本地对话存储 | 4 | — |
| 1.2.0 | — | Markdown 渲染 | 3 | — |
| 1.1.0 | — | 文件操作扩展 | 2 | — |
| 1.0.0 | — | 首个可用版本 | 1 | — |

「正文」一列是 `—` 的版本只有 CHANGELOG 条目，没有单独的 Release 文案。1.6.4 之前没有 git 记录，所以日期缺失 —— 不是漏填。

---

## 发一个新版本

### 1. 改版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 21          // 只增不减
versionName = "1.11.1"
```

### 2. 编译并核对

```bash
./gradlew :app:assembleDebug --offline
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | head -2
```

必须看到 `versionCode='21' versionName='1.11.1'` 再往下走。版本号没改对就打包，是最常见的低级错误。

### 3. 改名并算校验值

```bash
cp app/build/outputs/apk/debug/app-debug.apk ../PhoClaw-v1.11.1-debug.apk
md5sum    ../PhoClaw-v1.11.1-debug.apk
sha256sum ../PhoClaw-v1.11.1-debug.apk
```

### 4. 写正文

新建 `docs/releases/v1.11.1.md`，结构照 `v1.11.0.md`：

> 一句话摘要 → 亮点（讲清**为什么这么设计**）→ 修了什么 → 安装说明 → 已知限制 → 校验值

### 5. 更新本文件

改两处：顶部的「当前版本」表格、下面的「版本索引」第一行。

### 6. 打 tag 并建 Release

```bash
git tag -a v1.11.1 -m "v1.11.1"
git push origin main --tags
```

然后在 GitHub 上 **Draft a new release**：选 tag → 标题 `v1.11.1 — 主题` →
正文粘贴 `v1.11.1.md` 全文 → 附件上传 APK → 发布。

### 7. 同步 `CHANGELOG.md`

加一节，按新增 / 修复 / 变更分类。Release 正文写「为什么」，CHANGELOG 写「改了什么」，两者不要互相复制。

---

## 版本号规则

`versionCode` 是 Android 判断「能不能覆盖安装」的唯一依据，**只增不减**；`versionName` 给人看，走语义化版本。

| 位置 | 什么时候动 |
|---|---|
| patch（1.11.**0** → 1.11.**1**） | 修 bug、小调整 |
| minor（1.**11**.0 → 1.**12**.0） | 新增能力，但老用法不变 |
| major（**1**.11.0 → **2**.0.0） | 破坏性变更（改了指令格式、删了功能） |

有一处历史例外：`1.6.8 → 1.7.0` 时 `versionName` **跳过了 1.6.9** 直接进 minor 位，因为那一轮引入了两个全新的联网能力。`versionCode` 是连续的（15 → 16），只是名字跳了。CHANGELOG 开头也记了这件事。

---

## 附件约定

| 项 | 约定 |
|---|---|
| 文件名 | `PhoClaw-v{版本}-debug.apk` |
| 签名 | **debug 签名**，仅供个人安装。不能上架应用商店，也不该发给不特定用户 |
| 上传位置 | Release 附件 + 自建 Worker 的 `public/app.apk`（配套更新 `public/version.json`） |

`version.json` 的格式：

```json
{
  "version": "1.11.0",
  "file": "app.apk",
  "label": "PhoClaw-v1.11.0-debug.apk",
  "notes": "一句话说明"
}
```

App 内的版本检查读的就是它 —— 忘了改的话，用户会一直被告知「已是最新」。

---

## 关于校验值

Release 正文里写 MD5 和 SHA-256，是因为 APK 走的是 HTTP 下载，没有签名校验链能覆盖传输过程。装之前对一下：

```bash
# Linux / macOS
md5sum PhoClaw-v1.10.0-debug.apk
shasum -a 256 PhoClaw-v1.10.0-debug.apk

# Windows PowerShell
Get-FileHash PhoClaw-v1.10.0-debug.apk -Algorithm SHA256
```

对不上就别装 —— 要么下载断了，要么中间被换了。
