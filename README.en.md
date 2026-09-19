# PhoClaw

An Android app that runs on your phone, holds a conversation, and reads/writes files in your workspace directly.

**Current version: v1.6.4** (versionCode 11)

[中文](README.md) · **English**

> It contains **no hardware connectivity, no Bluetooth/Wi-Fi provisioning, no component
> control** — just two things: **writing code** and **chatting**.

---

## What it is

A complete Kotlin + Jetpack Compose Android project. Once installed:

1. Enter your own API key (DeepSeek / OpenAI / Qwen / any OpenAI-compatible endpoint)
2. Pick a folder as your **workspace** via the system directory picker
3. Start chatting — the AI can **genuinely read and write files in that folder**

The AI manipulates files through a conventional code-block format:

````
```phoclaw:tree .
```

```phoclaw:read src/main.js
```

```phoclaw:write src/main.js
console.log("hello");
```

```phoclaw:copy src/a.js -> src/b.js
```
````

The app parses these commands, executes them against the real filesystem, and feeds the
results back to the model so it can decide its next step (up to 8 rounds, to prevent
infinite loops).

> The legacy `espclaw:` prefix still works, so formats in older conversations won't break.

---

## Supported commands

| Command | Purpose |
|---|---|
| `list <dir>` | List files and subdirectories |
| `tree <dir>` | Recursively show the directory tree (3 levels, max 300 entries) |
| `read <file>` | Read a file in full (refuses above 512 KB to avoid blowing up the context) |
| `write <file>` | Write a file, overwriting existing content; parent directories are created |
| `append <file>` | Append to the end of a file |
| `search <keyword> [dir]` | Search **workspace file contents**, returns `file:line: content` |
| `websearch <keyword>` | **Search the internet**, returns a summary and snippets with source links |
| `info <path>` | Show size, type, modification time |
| `mkdir <dir>` | Create a directory |
| `copy <src> -> <dst>` | Copy a file or directory (recursive for directories) |
| `move <src> -> <dst>` | Move or rename |
| `delete <path>` | Delete a file or directory |

**Aliases**: `ls`=`list`, `cp`=`copy`, `mv`=`move`, `rm`/`del`=`delete`, `md`=`mkdir`,
`grep`/`find`=`search`, `stat`=`info`.
`websearch` also accepts `web_search` / `wsearch` / `webs` / `netsearch`.
`copy` / `move` arguments may also be space-separated: `cp a.js b.js`.

> ⚠️ `search` and `websearch` are **not the same thing**: the former searches file contents
> inside your workspace, the latter searches the internet. Putting `search` in a skill
> allowlist only grants local file search — it does **not** let the AI go online.

### Destructive operations require confirmation

`delete` and `move` are irreversible, so **in chat** a confirmation dialog appears before
execution. The command runs only if you tap "Allow"; tapping "Decline" skips it and tells
the model what happened. The model cannot bypass this step.

**In scheduled tasks** (unattended, with no dialog to tap) this is instead governed by a
per-task **"allow destructive operations"** switch: when off, such commands are always
refused and the refusal is written back to the log so the model knows what happened and can
try another approach — rather than waiting forever on a dialog no one will ever answer.

### Safety rails

- Per-file read limit of **512 KB**; binary files and files over 1 MB are skipped during search
- Directory trees recurse at most 3 levels / 300 entries, so huge repos don't flood the context
- `..` path traversal is forbidden; copying or moving a directory into its own subdirectory is
  refused (that would recurse forever)
- Any path containing `..` is rejected outright

---

## Features

| Feature | Implementation |
|---|---|
| Streaming chat | OkHttp SSE, character-by-character rendering, interruptible at any time |
| Markdown rendering | Hand-written two-level parser (block + inline); code blocks and tables scroll horizontally; zero new dependencies |
| **Attachments** | Images and code/text files via the SAF picker, **no storage permission needed**; attachments-only messages and multi-select supported |
| **Local conversation storage** | Conversations auto-save to the app's private directory and resume on relaunch; history screen to view, switch, and delete conversations |
| **Skill import** | Import local `.md` / `.json`; a skill = prompt + command allowlist; once active it's injected into the system message and out-of-scope commands are intercepted |
| **Scheduled tasks** | Title / summary / natural-language prompt / cron expression; runs unattended in the background and notifies on completion |
| **Web search** | With a Tavily key, the AI can call `websearch` on its own to verify current facts and cite sources; works in both chat and scheduled tasks |
| Workspace read/write | SAF (`OpenDocumentTree`), **no storage permission**, supports subdirectories and auto-creates parents |
| Workspace switching | Change the directory anytime from Settings or the Files screen; resets to the new workspace root |
| File operations | 12 commands: read / write / append / list / tree / search / websearch / info / mkdir / copy / move / delete |
| Destructive-operation guard | In chat, `delete` / `move` prompt for confirmation; in scheduled tasks, a per-task switch pre-authorizes them |
| Credential security | `EncryptedSharedPreferences` + Android Keystore; keys are never stored in plaintext |
| Multi-backend | Any OpenAI-compatible endpoint; Base URL and model name are configurable |
| Base URL correction | Automatically detects and strips `/v1/chat/completions`, with a live red warning |
| Workspace browser | Built-in file browser for directory trees and file contents |
| Keyboard handling | The input field rises above the IME and the content area follows; scrolls to the newest message the moment the keyboard opens |
| Theming | Material 3 with dynamic color (Android 12+) |

### What Markdown is rendered

| Syntax | Result |
|---|---|
| `# Heading` – `##### Heading` | Five heading levels with increasing size and weight |
| `**bold**` / `*italic*` / `~~strike~~` | Inline styles |
| `` `inline code` `` | Monospace font, light background, rounded corners |
| ` ```lang ` code block | Monospace, dark background, border, language label in the corner, **horizontally scrollable** |
| `- item` / `1. item` | Unordered/ordered lists; ordered lists are auto-numbered |
| `> quote` | Left rule with italic text |
| `\| a \| b \|` | Table with content-adaptive column widths, **horizontally scrollable** |
| `---` | Horizontal rule |
| `[text](url)` | Underlined text in the accent color |

> **Markdown is not rendered while streaming**: output is shown as plain text while the AI
> is generating and rendered once the whole reply finishes. Otherwise unclosed ` ``` ` would
> make the layout jump around mid-stream.

---

## Skills

A skill is a **prompt** plus a **command allowlist**. Import a local file from the Skills
screen; two formats are supported.

### Format 1: Markdown + frontmatter (recommended)

```markdown
---
name: Code Review
description: Review code against team conventions
actions: read, list, tree, search
---

You are a strict code reviewer. Focus on:
1. Null pointers and array bounds
2. Unhandled exceptions
3. Whether naming follows team conventions
```

Omitting `actions` means no command restriction. **An invalid action name is a hard error**,
never silently ignored — silently dropping it would make the allowlist stricter (or looser)
than you think, and either way you'd misjudge the safety boundary.

### Format 2: Plain JSON

```json
{
  "name": "Code Review",
  "description": "Review code against team conventions",
  "allowedActions": ["read", "list", "tree", "search"],
  "prompt": "You are a strict code reviewer..."
}
```

A `.md` file without frontmatter also imports fine: the filename becomes the name, the whole
body becomes the prompt, and no commands are restricted.

### How the allowlist works

- Once a skill is **active**, its prompt is appended to the system message and the allowlist
  filters every command before execution
- When multiple skills declare allowlists they are **unioned** (you may use any of them); if
  none declare one, nothing is restricted
- Intercepted commands **write "declined" back to the log** for the model to see. Silent
  dropping is not an option — with no feedback the model would retry the same command until
  all 8 rounds are exhausted
- The allowlist is a **narrowing of capability**: a skill cannot grant the model anything
  beyond the existing 12 commands. Even with a skill from an untrusted source, the worst case
  is a misled model — never a spontaneously granted delete permission

> With no skill active, the system prompt is byte-for-byte what you wrote in Settings —
> behavior is completely unchanged.

---

## Web search

The AI's **knowledge has a cutoff date**. Ask it "what's in the news today" or "what's the
latest DeepSeek version" and it can only guess from memory — confidently, even when wrong.
Web search fixes this.

### Enabling it

1. Sign up at [app.tavily.com](https://app.tavily.com) and grab a free key (looks like `tvly-xxxxx`)
2. Paste it into **Settings → Web Search** in PhoClaw and tap "Save search settings"
3. Tap "Test search" to confirm it works

> The "Get a free Tavily key" link opens in your **system browser**, not an embedded WebView.
> Embedding one would mean maintaining a whole page and having you type a third-party
> account password inside the app, which is worse for security, not better.

### Its shape: a new command, not automatic search

`websearch` is a command the **model decides when to call**, exactly like `read` / `write`.
The AI searches when a question involves current information; for ordinary conversation,
coding, or arithmetic it answers directly.

**Why not search on every message:**

| | Automatic search | On-demand (this approach) |
|---|---|---|
| Latency | Every message waits for a search round-trip | Waits only when needed |
| Cost | Burns search quota on every sentence | Burns it only when genuinely useful |
| Quality | Irrelevant results **interfere** with the model | The model chooses the keywords |

### What a search returns

Tavily is a search API designed for LLMs — it returns **already-extracted page snippets**
rather than HTML, so no scraping is needed on our side:

```
===== Web search results: latest Kotlin version 2026 =====

[Summary]
Kotlin 2.2.0 is the current stable release...

[1] Kotlin Releases
Source: https://kotlinlang.org/docs/releases.html
Kotlin 2.2.0 released...
===== End of search results =====
```

**URLs are deliberately included**: the model can cite sources and you can verify them
yourself. Integrated summaries aren't always reliable — links are what let you check.

### Context budget control

| Item | Value | Why |
|---|---|---|
| Results returned | 5 by default (up to 20) | More rarely helps the model yet eats significant context |
| Per-result body | Truncated to 1200 characters | Tavily's `content` is sometimes a whole article; five 5000-char bodies would consume most of the budget |
| Read timeout | 45 s | Search is a one-shot synchronous request (not streaming), but advanced depth on slow sites gets killed by shorter values |

### What happens without a key

**It never fails silently.** Three distinct messages are fed back so the model knows what to do:

| Situation | What the model receives |
|---|---|
| Empty key | "Web search is not enabled... tell the user they need to configure the key and **answer from your existing knowledge instead; do not retry the search**" |
| Search failed | "Web search failed: <reason>. You may retry once with a more specific keyword, or answer from existing knowledge" |

> The "retry once" wording is intentional — without stating a limit, the model would retry the
> same command until all 8 rounds are exhausted. It's the same principle as writing "declined"
> back when a skill allowlist intercepts a command: **interception must be visible to the model**.

HTTP errors get actionable explanations too: 401/403 means a wrong or expired key, 429 means
rate limiting or quota, 432/433 means usage limits, and 5xx is a server-side problem.

### Works in chat and in scheduled tasks

`CommandDispatcher` is the **same implementation** shared by foreground chat and the
background `HeadlessTurnRunner`. The AI in a scheduled task can search too — for example,
"every morning at 9, search today's tech news and summarize it."

> The dispatcher reads the current key **live** on each construction rather than caching it.
> Otherwise changing the key in Settings wouldn't take effect until you restarted the app.

### Security

- The **API key is encrypted at rest**, same as the LLM key, via `EncryptedSharedPreferences` + Keystore
- Web search is **read-only** and never uploads workspace files — only the keywords the model writes are sent
- `websearch` in a skill allowlist is **independent of** `search`. To keep a skill offline, just omit it

---

## Scheduled tasks

Create a task from the Automation screen with four fields:

| Field | Description |
|---|---|
| Title | The name shown in the list, and the title of the execution-record conversation |
| Summary | One-line description (optional) |
| Prompt | Natural language; sent to the AI verbatim when the time comes |
| cron expression | 5-field or 6-field, auto-detected |

When the time comes, PhoClaw runs a full conversation loop **unattended in the background**
(ask → call tools → feed results back → continue, up to 8 rounds) and sends a notification
on completion. Tapping it jumps straight to that task's execution record.

### cron support

```
5 fields:  min hour dom month dow      e.g.  0 9 * * 1-5     weekdays at 9:00
6 fields:  sec min hour dom month dow  e.g.  0 */5 * * * *   every 5 minutes
```

Each field supports `*`, `a-b`, `a,b,c`, `*` `/n` steps, and `a/n`; months and weekdays accept
three-letter English abbreviations like `JAN` / `MON`.

Quartz-specific `?` `L` `W` `#` are **not supported**. They produce an explicit error rather
than being ignored — silently accepting a semantic you can't actually implement would leave
you believing a task is live while it never fires. `?` is the sole exception: it means exactly
the same as `*`, so it's treated as such with a UI hint.

> **When both day-of-month and day-of-week are specified, it's an OR** (standard Vixie cron
> semantics). `0 0 1 * 1` means "the 1st of the month **or** every Monday", not both. This is
> the single easiest thing to get wrong.

The editor **computes the next three trigger times live**. cron is abstract; one glance
tells you if you got it wrong.

### Handling destructive operations

Nobody can tap a confirmation dialog during a background run, so `delete` / `move` are
governed by the per-task **"allow destructive operations"** switch. Off by default:

- **Off**: the AI is told "this operation is forbidden in automated tasks" and can continue
  another way, instead of waiting forever on a dialog no one will answer
- **On**: it executes directly. Ticking this box **is a pre-authorization** — equivalent to
  "permanently approve deletes/moves within this task" — which is why the UI shows a red warning

### Scheduling and permissions

| Item | Detail |
|---|---|
| Trigger | `AlarmManager.setExactAndAllowWhileIdle`, wakes on time even in Doze |
| Exact-alarm permission | Declares both `USE_EXACT_ALARM` (auto-granted on Android 13+) and `SCHEDULE_EXACT_ALARM` (needs manual approval on 12+) |
| If denied | **Automatically degrades** to an inexact alarm; the task still runs, at worst a dozen minutes late, and the UI shows an "inexact mode" badge. The feature never becomes outright unusable |
| Notification permission | Requested on Android 13+ when you first create a task; if declined the task still runs, you just don't get the result notification |
| After reboot | `BootReceiver` rebuilds all alarms on boot / time change / timezone change / app upgrade |
| After "Force stop" | A force-stopped app receives no broadcasts at all. PhoClaw re-syncs on **every cold start** — the only available recovery point |

> ⚠️ Some Chinese OEM ROMs aggressively kill background processes. If a task doesn't fire, add
> PhoClaw to the battery-optimization allowlist in system settings. The app deliberately
> doesn't deep-link you there — app stores classify that as solicitation.

### Why a foreground service instead of WorkManager

WorkManager's minimum periodic interval is 15 minutes and it gets batched/delayed under Doze.
The requirement here is "as close to on-time as possible", hence exact alarms.

`BroadcastReceiver.onReceive` only gets about 10 seconds, while a single task can involve 8
tool calls — easily 30+ seconds, which would be killed as an ANR. So a foreground service
carries the execution, at the cost of a persistent, non-dismissible notification.

### Execution records

Each task is **bound to one conversation, overwritten in place**, rather than creating a new
conversation per run. An hourly task would otherwise generate 24 conversations a day, drowning
the history screen so thoroughly you'd never find your actual chats again.

---

## Attachments

The **"+"** button at the left of the input bar lets you pick images or code/text files. You
can multi-select, and you can send attachments without typing anything.

### Images and text take two entirely different paths

| | Images | Code / text files |
|---|---|---|
| How they're sent | Base64 over multimodal `image_url` | Full contents inlined into context |
| Persistent? | **No**, current turn only | **Yes**, included in every subsequent turn |
| Why | Images are bulky; resending each turn bloats the request fast | You want the AI to reference this code across many turns |

Images in past messages are not resent — only a `[image xxx.png]` placeholder remains, to
preserve the sense of position.

### ⚠️ The "model supports vision" toggle

**In Settings, off by default.** That default is a deliberate conservative choice.

The multimodal protocol requires the message `content` to be an array, and backends without
vision support (older Ollama, some Chinese gateways) **return 400 immediately** when they
receive an array. So:

| Toggle | Behavior |
|---|---|
| **Off** (default) | Images are described in text only. The model knows "the user sent an image" but can't see it. **No backend will error because of it** |
| **On** | Images are sent as Base64 via `image_url` and the model genuinely "sees" them |

Turn it on for vision-capable models like `gpt-4o`, `qwen-vl-max`, or `glm-4v`; keep it off
for text-only models like `deepseek-chat`.

### Images are compressed

| Parameter | Value | Why |
|---|---|---|
| Long-edge cap | 1568 px | The practical processing resolution of mainstream vision models; anything larger wastes bandwidth and tokens |
| Quality | JPEG 85 | Text in code screenshots stays legible |
| Trigger threshold | Above 1 MB or over the long-edge cap | Smaller images aren't re-encoded, since re-encoding a small image often makes it *bigger* |

After compression you'll see something like "compressed 4.8MB → 420KB".

> The pipeline always follows "read bounds → sampled decode → precise scale" and **never
> decodes an image at full size** — decoding a 8000×6000 image in full needs 190 MB and would
> OOM immediately.

### Size limits

| Item | Limit |
|---|---|
| Single image (original) | 12 MB |
| Single text file | 256 KB |
| Inlined text per turn | 200 KB (excess is truncated and clearly marked) |
| Attachments per send | 6 |
| Total `files/attachments/` | 200 MB soft cap; pruned from oldest down to 150 MB when exceeded |

### Where files live

Copied into the app's private directory `files/attachments/` with random 16-hex-character
filenames (no original name mixed in, avoiding Chinese characters, emoji, over-long names, and
path separators). Conversation JSON stores only references, **never Base64** — otherwise a
multi-MB image would make every conversation read/write sluggish.

Cleanup timing:

- Delete a conversation → its referenced attachments are deleted too
- Clear all history → the whole attachment directory is emptied
- Cold start / backgrounding → orphan scan (skipping files modified within the last 5 minutes,
  so in-flight sends aren't deleted by mistake)

> If an attachment has been cleaned up, the history entry shows an "image unavailable"
> placeholder — it **never crashes**.

### Can't select certain code files?

The picker already accepts any file type (`.kt` / `.py` / `.gradle` often have no MIME type
the system recognizes, and tightening this would gray them out). The real content filter lives
in the read layer: **files containing NUL bytes are classified as binary and refused**, so
archives never get handed to the model as text.

---

## Conversation storage

Conversations **save automatically** — there's no save button. Reopening the app picks up
where you left off.

### Where they live

In the app's private directory `files/conversations/`, one JSON file per conversation:

```
files/conversations/
├── index.json          Index: id / title / time / message count only
├── a1b2c3d4e5f6.json   All messages of one conversation
└── ...
```

- **The list screen reads only `index.json`**, instead of parsing every conversation body
- **Writes use "temp file + atomic rename"**: write `.json.tmp`, then rename. If the process is
  killed mid-write, you lose at most that one update and never end up with a half-corrupt file
- Kept in the app's private directory, invisible to other apps and file managers, and removed
  on uninstall

### When writes happen

| Moment | Behavior |
|---|---|
| A turn completes | Written immediately (messages are complete at this point) |
| Request errors out | Written immediately (you asked half a question; it should survive a reopen) |
| User taps stop | Written immediately, preserving whatever was generated |
| Switching to a new / opening a past conversation | The current one is saved first |
| App backgrounded | Forced flush |
| During streaming | **1.2 s debounce**: token deltas arrive every few dozen milliseconds, so write once things quiet down |

> **Partial content is not written mid-stream**, to avoid persisting half-finished replies.

### How conversation titles are derived

From the first line of the first user message, with whitespace collapsed and truncated to 24
characters, ending in `…` if longer.

No model call is used to summarize titles — that would add an API request and a wait to every
single message, and local truncation is good enough.

### History screen

Reached via the clock icon in the chat top bar. Entries are newest-first and show
"title + time + message count", with times rendered as human text ("Today 14:30 / Yesterday
09:12 / 3 days ago / Mar 5").

- Tap an entry → load that conversation and continue
- Tap the trash icon on the right → double-confirm, then delete
- Overflow menu in the top bar → clear all history (also double-confirmed)

> Deleting a conversation **does not touch workspace files** — only the chat log.

---

## Building

```bash
# Requires JDK 17+ and the Android SDK (platform 34 / build-tools 34.0.0)
# First, copy local.properties.example to local.properties and fill in your SDK path
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The artifact lands in `app/build/outputs/apk/debug/app-debug.apk` (about 19 MB).

With Android Studio: just `Open` this directory, wait for Gradle Sync, and hit Run.

> This project has been verified to compile under **Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.20 +
> JDK 20**, producing `app-debug.apk` with package `com.phoclaw.chat`, minSdk 26 / targetSdk 34.

### Requirements

| Item | Version |
|---|---|
| JDK | 17 or newer |
| Gradle | 8.9 (wrapper included) |
| AGP | 8.5.2 |
| Kotlin | 2.0.20 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |

> **Network note for China**: `gradle/wrapper/gradle-wrapper.properties` points at a Tencent
> Cloud mirror by default. If your network reaches the official source directly, switch it back
> to `https://services.gradle.org/distributions/gradle-8.9-bin.zip`. Dependency repositories in
> `settings.gradle.kts` are configured with both the Aliyun mirror and official sources.

---

## Configuring the API

Fill in three fields on the Settings screen:

| Field | Description | Example |
|---|---|---|
| API Key | Your secret key | `sk-xxxxxx` |
| Base URL | **Host only**, no endpoint path | `https://api.deepseek.com` |
| Model | The model identifier from your provider | `deepseek-chat` |

To use web search, add a fourth field: the **Tavily API key** (see the "Web search" section
above). Leaving it empty doesn't affect anything else.

### ⚠️ The most common Base URL mistake

**Do not** paste the full endpoint URL from the docs:

| | Value |
|---|---|
| ✅ Correct | `https://api.deepseek.com` |
| ❌ Wrong | `https://api.deepseek.com/v1/chat/completions` |
| ❌ Wrong | `https://api.deepseek.com/v1` |

The app shows a **live red warning** as you type and **automatically strips** the redundant
path on save. So even if you paste it wrong it still works — but it's better to fill it in
correctly.

### Provider reference

| Service | Base URL | Example model |
|---|---|---|
| DeepSeek (default) | `https://api.deepseek.com` | `deepseek-chat` |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` |
| Alibaba Qwen | `https://dashscope.aliyuncs.com/compatible-mode` | `qwen-plus` |
| Moonshot | `https://api.moonshot.cn` | `moonshot-v1-8k` |

For a local Ollama, set the Base URL to `http://<your-ip>:11434` and change
`android:usesCleartextTraffic` to `true` in `AndroidManifest.xml` (plain HTTP needs it; keep it
`false` for public services).

---

## Code structure

```
app/src/main/java/com/phoclaw/chat/
├── MainActivity.kt          Entry point + bottom navigation + routing + background flush
├── PhoClawApp.kt            Application + cold-start alarm resync
├── MainViewModel.kt         Core state machine: chat loop, command execution, context feedback, storage
├── ChatScreen.kt            Chat UI
├── HistoryScreen.kt         Past-conversation list
├── SkillsScreen.kt          Skill import and management
├── AutomationScreen.kt      Scheduled-task list + editor (with live cron preview)
├── SettingsScreen.kt        Settings (API key / Base URL / model / Tavily key / workspace / prompt)
├── WorkspaceScreen.kt       Workspace file browser
├── auto/
│   ├── AlarmScheduler.kt    cron → AlarmManager exact scheduling + permission checks
│   ├── AutomationService.kt Foreground service carrying long-running execution
│   ├── AutomationReceiver.kt Alarm callback
│   ├── BootReceiver.kt      Rebuilds alarms on boot / time change / upgrade
│   └── Notifications.kt     Notification channel and "running" / "finished" notifications
├── data/
│   ├── CredentialStore.kt   Encrypted credential storage + default system prompt + search key
│   ├── ConversationStore.kt Local conversation persistence (JSON + index)
│   ├── Attachment.kt        Attachment data model (persisted entity + in-memory pending state)
│   ├── AttachmentRepository.kt  Attachment read/compress/persist/cleanup
│   ├── WorkspaceRepository.kt   SAF file I/O (with path traversal protection)
│   ├── CommandDispatcher.kt Command executor (shared by foreground and background)
│   ├── HeadlessTurnRunner.kt Unattended conversation loop (UI-independent)
│   ├── PromptComposer.kt    System prompt assembly + skill allowlist union
│   ├── Skill.kt             / SkillParser.kt  / SkillStore.kt     Skills
│   ├── AutomationTask.kt    / AutomationStore.kt                  Scheduled tasks
│   ├── CronExpression.kt    Hand-written cron parser (5- / 6-field)
│   ├── TavilyClient.kt      Web search client
│   └── LlmClient.kt         OpenAI-compatible client (SSE streaming + multimodal)
├── util/
│   ├── CommandParser.kt     Parses phoclaw command blocks
│   ├── MarkdownParser.kt    Two-level Markdown parsing (Block / Span)
│   └── BaseUrlHint.kt       Base URL validation and normalization
├── ui/
│   ├── MarkdownText.kt      Markdown Compose renderer
│   └── theme/Theme.kt       Material 3 theme
```

### How the chat loop runs

```
User input
  ↓
streamingCompletion()  →  SSE renders character by character, updating one assistant bubble in place
  ↓
CommandParser.parse()  →  extracts command blocks; the UI shows only a summary
  ↓
finalizeAssistant()    →  reuses that bubble, replacing it with the command-free body
  ↓
executeCommands()      →  delete/move prompt first; everything else runs directly
  ↓
Logs fed back as internal messages →  back to streamingCompletion()
  ↓
Model emits no further commands → done (at most 8 rounds)
```

`streamingCompletion()` returns `(text, messageIndex)` and `finalizeAssistant()` reuses that
index directly — which is why replies **never appear as duplicate bubbles**.

---

## Security notes

- **The workspace is the permission boundary.** The app can only access the directory you
  explicitly granted through the system picker.
- **Path traversal protection.** `WorkspaceRepository.sanitize()` rejects any path containing `..`.
- **Encrypted credentials.** API keys are stored encrypted via Keystore, with `allowBackup=false`
  so system backups can't carry them off.
- **Conversations are stored in plaintext.** Chat logs live in the app's private directory
  **unencrypted** — they aren't as sensitive as API keys, and encrypting them would prevent you
  from exporting your own data. But note: whatever you've discussed stays on the phone until you
  delete it.
- **The AI has write access — be careful.** The model can overwrite files inside the workspace.
  Don't put important data there.

---

## Known limitations

- Single-conversation context: past conversations can be switched freely, but they are **never
  merged** into one context
- Image recognition depends on the model supporting vision and requires enabling the "model
  supports vision" toggle manually
- Web search requires your own Tavily API key; the free tier has a monthly call cap
- No voice, video, or other modalities
- No hardware connectivity, MQTT, serial, or any component-related functionality — by design

---

## Icon

Taken from the OpenClaw official repository at `apps/android/app/src/main/res/`; the mascot is
**Molty**. The adaptive icon background uses the official brand color Claw Red `#ff5a50`.

---

## License

MIT
