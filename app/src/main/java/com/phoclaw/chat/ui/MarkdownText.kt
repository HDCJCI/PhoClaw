package com.phoclaw.chat.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.util.MarkdownParser
import com.phoclaw.chat.util.MarkdownParser.Block
import com.phoclaw.chat.util.MarkdownParser.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 Markdown 文本渲染成 Compose 组件。
 *
 * 针对聊天气泡做了尺寸适配：字号偏小、间距紧凑、代码块横向可滚动。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    val parsed = remember(markdown) { MarkdownParser.parseWithRefs(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parsed.blocks.forEach { block -> BlockView(block, parsed.refs, fontSize) }
    }
}

@Composable
private fun BlockView(
    block: Block,
    refs: Map<String, String>,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    when (block) {
        is Block.Paragraph -> {
            // 整段就是一个图片时，直接渲染图片而不是「图 + 灰字占位」
            val spans = remember(block.text, refs) { MarkdownParser.parseInline(block.text, refs) }
            val onlyImage = spans.singleOrNull() as? Span.Image
            if (onlyImage != null) {
                MarkdownImage(onlyImage.url, onlyImage.alt)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = inline(block.text, refs),
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.45f
                    )
                    // 段落里混排的图片（文字 + 图片同行）单独放在下方展示
                    spans.filterIsInstance<Span.Image>().forEach { MarkdownImage(it.url, it.alt) }
                }
            }
        }

        is Block.Heading -> {
            val size = when (block.level) {
                1 -> fontSize * 1.35f
                2 -> fontSize * 1.2f
                else -> fontSize * 1.08f
            }
            Text(
                text = inline(block.text, refs),
                fontSize = size,
                fontWeight = FontWeight.Bold,
                lineHeight = size * 1.4f
            )
        }

        is Block.CodeBlock -> {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(9.dp)) {
                    if (block.language.isNotBlank()) {
                        Text(
                            block.language.uppercase(),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize * 0.85f,
                        lineHeight = fontSize * 1.25f,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth()
                    )
                }
            }
        }

        is Block.ListItem -> Row(Modifier.fillMaxWidth()) {
            Text(
                text = if (block.ordered) "${block.index}." else "•",
                fontSize = fontSize,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(22.dp)
            )
            Text(
                text = inline(block.text, refs),
                fontSize = fontSize,
                lineHeight = fontSize * 1.45f,
                modifier = Modifier.weight(1f)
            )
        }

        is Block.TaskItem -> Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 用 ☑ / ☐ 两个字符当勾选框：零依赖，各平台字体都自带
            Text(
                text = if (block.checked) "☑" else "☐",
                fontSize = fontSize,
                color = if (block.checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
            Text(
                text = inline(block.text, refs),
                fontSize = fontSize,
                lineHeight = fontSize * 1.45f,
                // 已完成的项加删除线 + 淡化，一眼能看出进度
                textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant
                else Color.Unspecified,
                modifier = Modifier.weight(1f)
            )
        }

        is Block.Quote -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 18.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(2.dp)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text, refs),
                fontSize = fontSize * 0.95f,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                lineHeight = fontSize * 1.4f,
                modifier = Modifier.weight(1f)
            )
        }

        is Block.Table -> MarkdownTable(block, refs, fontSize)

        Block.Divider -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/** 表格：列宽按内容自适应，整体可横向滚动。 */
@Composable
private fun MarkdownTable(
    table: Block.Table,
    refs: Map<String, String>,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val colCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    // 估算每列宽度：取该列所有单元格的字符数上限
    val widths = (0 until colCount).map { c ->
        val headerLen = table.header.getOrNull(c)?.length ?: 0
        val bodyMax = table.rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
        maxOf(headerLen, bodyMax).coerceIn(6, 24)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            // 表头
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                table.header.forEachIndexed { i, cell ->
                    TableCell(
                        text = cell,
                        widthChars = widths.getOrElse(i) { 8 },
                        fontSize = fontSize,
                        bold = true,
                        refs = refs
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            // 数据行
            table.rows.forEach { row ->
                Row {
                    (0 until colCount).forEach { i ->
                        TableCell(
                            text = row.getOrNull(i).orEmpty(),
                            widthChars = widths.getOrElse(i) { 8 },
                            fontSize = fontSize,
                            bold = false,
                            refs = refs
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    widthChars: Int,
    fontSize: androidx.compose.ui.unit.TextUnit,
    bold: Boolean,
    refs: Map<String, String> = emptyMap()
) {
    Text(
        text = inline(text, refs),
        fontSize = fontSize * 0.9f,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = fontSize * 1.3f,
        modifier = Modifier
            .width((widthChars * 8).dp)
            .padding(horizontal = 7.dp, vertical = 5.dp)
    )
}

/** 把行内片段组合成 AnnotatedString。
 *
 * 链接用 Compose 的 [withLink] 注册真实可点击区域 —— 只给 SpanStyle 上色
 * 的话文字看着像链接但点不动。点击后交给系统浏览器打开。 */
@Composable
private fun inline(text: String, refs: Map<String, String> = emptyMap()): AnnotatedString {
    val spans = remember(text, refs) { MarkdownParser.parseInline(text, refs) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary

    return remember(spans, codeBg, linkColor) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span) {
                    is Span.Plain -> append(span.text)

                    is Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(span.text)
                    }

                    is Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(span.text)
                    }

                    is Span.BoldItalic -> withStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    ) { append(span.text) }

                    is Span.Strikethrough -> withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough)
                    ) { append(span.text) }

                    is Span.Underline -> withStyle(
                        SpanStyle(textDecoration = TextDecoration.Underline)
                    ) { append(span.text) }

                    is Span.Code -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg,
                            fontSize = 13.sp
                        )
                    ) { append(span.text) }

                    is Span.Link -> withLink(
                        LinkAnnotation.Url(
                            url = span.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) { append(span.text) }

                    // 图片单独由 MarkdownImage 渲染，这里只保留 alt 文字占位，
                    // 避免同一个位置既画图又画字
                    is Span.Image -> append(span.alt)
                }
            }
        }
    }
}

// ==================================================================== 图片渲染

/**
 * Markdown 图片。
 *
 * 为什么不引 Coil：本项目在离线环境构建，加新依赖会导致构建失败。
 * 这里用已有的 OkHttp 下载字节流，交给 BitmapFactory 解码，
 * 结果缓存在一个简单的内存 LRU 里 —— 图片数量不多，够用。
 *
 * 只支持 http(s) 网络图片；其他协议显示占位提示。
 */
@Composable
private fun MarkdownImage(url: String, alt: String) {
    val context = LocalContext.current
    val isRemote = url.startsWith("http://") || url.startsWith("https://")

    if (!isRemote) {
        // 本地路径目前读不了（工作区文件不在 App 可直接访问的沙箱里），
        // 把原地址显示出来，用户至少知道这里本该有张图
        ImagePlaceholder(
            alt = alt.ifBlank { "图片" },
            hint = "本地地址暂不支持显示"
        )
        return
    }

    var loading by remember(url) { mutableStateOf(true) }
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        val cached = ImageCache.get(url)
        if (cached != null) {
            bitmap = cached; loading = false
        } else {
            when (val r = ImageLoader.load(url)) {
                is ImageLoader.Result.Ok -> bitmap = r.bitmap
                is ImageLoader.Result.Fail -> error = r.reason
            }
            loading = false
        }
    }

    when {
        loading -> ImagePlaceholder(alt = alt.ifBlank { "图片" }, hint = "加载中…")

        error != null -> ImagePlaceholder(
            alt = alt.ifBlank { "图片" },
            hint = "$error · 点此用浏览器打开",
            // 失败时给个可点的入口，用户能自己去浏览器看
            onClick = { openUrl(context, url) }
        )

        else -> {
            val bmp = bitmap ?: return
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = alt.ifBlank { "图片" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp)
                        .clickable { openUrl(context, url) },
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

/** 图片占位框：显示 alt 文字与状态说明，[onClick] 非空时可点。 */
@Composable
private fun ImagePlaceholder(alt: String, hint: String, onClick: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 14.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "🖼", fontSize = 22.sp)
            Text(
                text = alt,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = hint,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** 交给系统打开链接。 */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        // 没有浏览器或链接无法解析时静默失败，不打断阅读
    }
}

/** 图片内存缓存。上限 24 张，按插入顺序淘汰最早的。 */
private object ImageCache {
    private const val MAX = 24
    private val map = object : LinkedHashMap<String, android.graphics.Bitmap>(MAX, 0.75f, false) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?
        ) = size > MAX
    }

    @Synchronized
    fun get(key: String): android.graphics.Bitmap? = map[key]

    @Synchronized
    fun put(key: String, value: android.graphics.Bitmap) {
        map[key] = value
    }
}

/** 用 OkHttp 拉取图片并解码。
 *
 * 返回带失败原因的结果，而不是裸 null —— 加载不出来时用户能一眼看出
 * 是网络不通、404、还是解码失败，省得干瞪眼。 */
private object ImageLoader {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            // 跟随重定向，很多图床会用 302 跳转
            .followRedirects(true)
            .build()
    }

    /** 单张图最大 12 MB，防止把内存吃爆。 */
    private const val MAX_BYTES = 12 * 1024 * 1024

    sealed interface Result {
        data class Ok(val bitmap: android.graphics.Bitmap) : Result
        data class Fail(val reason: String) : Result
    }

    suspend fun load(url: String): Result = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                // 部分图床会校验 Referer / UA，带上更稳
                .header("User-Agent", "Mozilla/5.0 (Android) PhoClaw")
                .header("Accept", "image/*,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.Fail("服务器返回 ${resp.code}")

                val body = resp.body ?: return@withContext Result.Fail("响应为空")
                if (body.contentLength() > MAX_BYTES) return@withContext Result.Fail("图片超过 12 MB")
                val bytes = body.bytes()
                if (bytes.isEmpty()) return@withContext Result.Fail("内容为空")
                if (bytes.size > MAX_BYTES) return@withContext Result.Fail("图片超过 12 MB")

                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext Result.Fail("不是可识别的图片格式")

                ImageCache.put(url, bmp)
                Result.Ok(bmp)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.Fail("连接超时")
        } catch (e: java.net.UnknownHostException) {
            Result.Fail("域名解析失败")
        } catch (e: javax.net.ssl.SSLException) {
            Result.Fail("HTTPS 握手失败")
        } catch (e: java.io.IOException) {
            Result.Fail("网络错误")
        } catch (e: Exception) {
            Result.Fail("加载失败")
        }
    }
}
