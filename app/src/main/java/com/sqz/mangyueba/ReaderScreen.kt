package com.sqz.mangyueba

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** 缩放超过该比例才接管单指拖动；轻微误触的缩放不应让列表无法滚动。 */
private const val ZoomLockThreshold = 1.05f

@Composable
fun ReaderScreen(
    book: LibraryBook,
    repository: ComicRepository,
    targetWidth: Int,
    onProgress: (volume: Int, page: Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var volumeIndex by rememberSaveable(book.id) {
        mutableStateOf(book.volumeIndex.coerceIn(0, book.uris.lastIndex))
    }
    var startPage by rememberSaveable(book.id) { mutableStateOf(book.pageIndex) }

    val volumeUriString = book.uris[volumeIndex.coerceIn(0, book.uris.lastIndex)]
    val volumeUri = remember(volumeUriString) { Uri.parse(volumeUriString) }
    val session = remember(volumeUri) {
        VolumeSession(repository, volumeUri, targetWidth, scope)
    }
    var pages by remember(session) { mutableStateOf<List<ReaderPage>?>(null) }
    var failed by remember(session) { mutableStateOf(false) }

    // 章节目录状态：开关、各卷名称、各卷总页数、各卷已读页（0 基）
    var tocOpen by rememberSaveable(book.id) { mutableStateOf(false) }
    var labels by remember(book.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var pageCounts by remember(book.id) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var pageMemory by remember(book.id) { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    LaunchedEffect(session) {
        try {
            pages = session.open()
        } catch (_: Exception) {
            failed = true
        }
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(failed) {
        if (failed) {
            Toast.makeText(context, "无法打开该漫画", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    // 后台准备目录：条目名称 + 每卷总页数（解析失败或 DRM 受限的卷显示 "?"）
    LaunchedEffect(book.id) {
        labels = withContext(Dispatchers.IO) {
            book.uris.mapIndexed { index, uriString ->
                index to ComicNaming.volumeLabel(repository.displayName(Uri.parse(uriString)))
            }
        }.toMap()
        book.uris.forEachIndexed { index, uriString ->
            if (pageCounts[index] == null) {
                repository.pageCount(Uri.parse(uriString), targetWidth)?.let { count ->
                    pageCounts = pageCounts + (index to count)
                }
            }
        }
    }

    // 持久化进度（恢复滚动位置用）；目录“已读”单独按看到的最深一页记录
    val recordProgress: (Int, Int) -> Unit = { volume, page ->
        onProgress(volume, page)
    }

    BackHandler { onBack() }

    val loaded = pages
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (loaded == null) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        } else {
            key(volumeUri) {
                ReaderPager(
                    book = book,
                    repository = repository,
                    scope = scope,
                    session = session,
                    volumeUri = volumeUri,
                    volumeIndex = volumeIndex,
                    pages = loaded,
                    startPage = startPage,
                    targetWidth = targetWidth,
                    onProgress = recordProgress,
                    onTocPage = { volume, page ->
                        pageMemory = pageMemory + (volume to page)
                    },
                    onBack = onBack,
                    onToggleToc = { tocOpen = !tocOpen },
                    onNextVolume = {
                        val next = volumeIndex + 1
                        if (next <= book.uris.lastIndex) {
                            Log.i("MangYueBa", "switch volume ${volumeIndex} -> $next")
                            startPage = 0
                            volumeIndex = next
                            pageMemory = pageMemory + (next to 0)
                            onProgress(next, 0)
                        }
                    }
                )
            }
        }
        if (tocOpen) {
            TocOverlay(
                entries = book.uris.indices.map { index ->
                    TocEntry(index, labels[index] ?: "第${index + 1}卷")
                },
                pageCounts = pageCounts,
                pageMemory = pageMemory,
                currentIndex = volumeIndex,
                fallbackPage = book.pageIndex,
                onSelect = { index ->
                    tocOpen = false
                    if (index != volumeIndex) {
                        val target = (pageMemory[index] ?: 0)
                            .coerceIn(0, ((pageCounts[index] ?: 1) - 1).coerceAtLeast(0))
                        startPage = target
                        volumeIndex = index
                        pageMemory = pageMemory + (index to target)
                        onProgress(index, target)
                    }
                },
                onDismiss = { tocOpen = false }
            )
        }
    }

    // 目录打开时返回键先收起目录（后注册的 BackHandler 优先）
    if (tocOpen) {
        BackHandler { tocOpen = false }
    }
}

@Composable
private fun ReaderPager(
    book: LibraryBook,
    repository: ComicRepository,
    scope: CoroutineScope,
    session: VolumeSession,
    volumeUri: Uri,
    volumeIndex: Int,
    pages: List<ReaderPage>,
    startPage: Int,
    targetWidth: Int,
    onProgress: (volume: Int, page: Int) -> Unit,
    onTocPage: (volume: Int, page: Int) -> Unit,
    onBack: () -> Unit,
    onToggleToc: () -> Unit,
    onNextVolume: () -> Unit
) {
    val listState = remember(volumeUri) {
        LazyListState(
            firstVisibleItemIndex = startPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        )
    }
    var scale by remember(volumeUri) { mutableFloatStateOf(1f) }
    var offset by remember(volumeUri) { mutableStateOf(Offset.Zero) }
    val zoomed = scale > ZoomLockThreshold

    val currentProgress by rememberUpdatedState(onProgress)
    val currentTocPage by rememberUpdatedState(onTocPage)
    val currentBack by rememberUpdatedState(onBack)

    fun leaveReader() {
        currentProgress(volumeIndex, listState.firstVisibleItemIndex)
        currentBack()
    }

    BackHandler { leaveReader() }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            listState.firstVisibleItemIndex to (info.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .collect { (first, lastVisible) ->
                // 写 SharedPreferences 会解析/序列化整库 JSON，不能放在滚动期间的主线程上
                withContext(Dispatchers.IO) { currentProgress(volumeIndex, first) }
                // 目录“已读”按看到的最深一页记录，读到卷尾时正好等于总页数
                currentTocPage(volumeIndex, maxOf(first, lastVisible))
            }
    }

    var userScrolled by remember(volumeUri) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) userScrolled = true
        }
    }

    // 预载上下相邻页，并在接近卷尾时预载下一卷首页
    LaunchedEffect(listState, pages, session) {
        snapshotFlow {
            val info = listState.layoutInfo
            listState.firstVisibleItemIndex to (info.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                // 只预载紧邻的下一页/上一页；滚动中大量并行解码会挤占主线程的 CPU 时间
                val targets = listOf(first - 1, last + 1)
                    .filter { it in pages.indices }
                if (targets.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        targets.forEach { session.decodeAsync(it) }
                    }
                }
                if (first >= pages.size - 4) {
                    book.uris.getOrNull(volumeIndex + 1)?.let { next ->
                        repository.prefetchFirstPage(Uri.parse(next), targetWidth, scope)
                    }
                }
            }
    }

    // 读完当前卷最后一页后自动进入下一卷
    LaunchedEffect(listState, pages, volumeIndex) {
        if (volumeIndex >= book.uris.lastIndex || pages.size <= 1) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && last.index == pages.lastIndex &&
                last.offset + last.size <= info.viewportEndOffset + 1
        }
            .distinctUntilChanged()
            .collect { atEnd ->
                if (atEnd && (userScrolled || pages.size <= 3)) {
                    delay(900)
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    if (last != null && last.index == pages.lastIndex &&
                        last.offset + last.size <= info.viewportEndOffset + 1
                    ) {
                        onNextVolume()
                    }
                }
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(volumeUri) {
                detectTapGestures(
                    onTap = { position ->
                        // 轻点左侧 1/4 唤出/收起章节目录；其余区域轻点还原缩放
                        if (position.x < size.width / 4f) {
                            onToggleToc()
                        } else {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    }
                )
            }
            .pointerInput(volumeUri) {
                // 只有双指，或已经放大到一定比例时，才接管拖动/缩放。
                // detectTransformGestures 在单指也会消费事件，会和 LazyColumn 的滚动抢手势，
                // 斜向滑动时会出现"滑不动"；这里显式避免单指抢占。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var transformActive = false
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        if (!transformActive) {
                            transformActive =
                                event.changes.count { it.pressed } >= 2 ||
                                    scale > ZoomLockThreshold
                        }
                        if (transformActive) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val pan = event.calculatePan()
                            if (newScale <= 1.001f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = newScale
                                val maxX = (newScale - 1f) * size.width / 2f
                                val maxY = (newScale - 1f) * size.height / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            }
                            event.changes.forEach { it.consume() }
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }
            .pointerInput(zoomed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var last = down.position
                    var maxPointers = 1
                    while (true) {
                        val event = awaitPointerEvent()
                        maxPointers = max(maxPointers, event.changes.count { it.pressed })
                        event.changes.firstOrNull { it.id == down.id }?.let { change ->
                            val delta = change.position - last
                            dx += delta.x
                            dy += delta.y
                            last = change.position
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (!zoomed && maxPointers == 1 && dx < -80.dp.toPx() &&
                        abs(dx) > abs(dy) * 1.4f
                    ) {
                        leaveReader()
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
            userScrollEnabled = !zoomed
        ) {
            itemsIndexed(pages, key = { index, _ -> index }) { index, page ->
                PageItem(session = session, page = page, index = index)
            }
        }
    }
}

@Composable
private fun PageItem(session: VolumeSession, page: ReaderPage, index: Int) {
    val cacheKey = session.cacheKey(index)
    var bitmap by remember(cacheKey) { mutableStateOf(ComicBitmapCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            val loaded = session.decodeAsync(index)
            if (loaded != null) bitmap = loaded
        }
    }
    val image = bitmap
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(page.width.toFloat() / page.height.toFloat())
            .background(Color(0xFF101012))
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.Medium
            )
        }
    }
}

/** 单卷阅读会话：持有 ComicVolume、页尺寸、位图解码与并发去重。 */
class VolumeSession(
    private val repository: ComicRepository,
    val uri: Uri,
    private val targetWidth: Int,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var volume: ComicVolume? = null
    private val jobs = HashMap<Int, Deferred<Bitmap?>>()
    private var closed = false

    suspend fun open(): List<ReaderPage> = withContext(Dispatchers.IO) {
        val opened = repository.openVolume(uri, targetWidth)
        val result = try {
            opened.openPages()
        } catch (e: Exception) {
            opened.close()
            throw e
        }
        synchronized(lock) {
            if (closed) {
                opened.close()
                emptyList()
            } else {
                volume = opened
                Log.i("MangYueBa", "opened $uri pages=${result.size}")
                result
            }
        }
    }

    fun cacheKey(index: Int): String = ComicRepository.cacheKey(uri, index)

    suspend fun decodeAsync(index: Int): Bitmap? {
        val key = cacheKey(index)
        ComicBitmapCache.get(key)?.let { return it }
        val job = synchronized(lock) {
            if (closed) return null
            jobs[index] ?: scope.async(Dispatchers.IO) {
                ComicBitmapCache.get(key) ?: decode(index)?.also { ComicBitmapCache.put(key, it) }
            }.also { deferred ->
                jobs[index] = deferred
                deferred.invokeOnCompletion { synchronized(lock) { jobs.remove(index) } }
            }
        }
        return job.await()
    }

    private fun decode(index: Int): Bitmap? {
        // 只在锁内取出当前卷，具体解码由各格式实现自行处理并发
        val current = synchronized(lock) {
            if (closed) return null
            volume ?: return null
        }
        return current.decode(index)
    }

    fun close() {
        val pending: List<Deferred<Bitmap?>>
        val current: ComicVolume?
        synchronized(lock) {
            closed = true
            current = volume
            volume = null
            pending = jobs.values.toList()
            jobs.clear()
        }
        pending.forEach { it.cancel() }
        try {
            current?.close()
        } catch (_: Exception) {
            // 关闭失败不影响返回书柜
        }
    }
}

private data class TocEntry(val index: Int, val label: String)

/** 左侧章节目录浮层：条目为卷名 + “已读/总页”；点遮罩收起，点条目跳卷。 */
@Composable
private fun TocOverlay(
    entries: List<TocEntry>,
    pageCounts: Map<Int, Int>,
    pageMemory: Map<Int, Int>,
    currentIndex: Int,
    fallbackPage: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = remember {
        LazyListState(
            firstVisibleItemIndex = currentIndex.coerceIn(0, (entries.lastIndex).coerceAtLeast(0))
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                // 只在未被子层（列表条目/滚动）消费时视为轻点收起；列表内滑动由列表自己处理
                detectTapGestures { onDismiss() }
            }
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.72f)
                .background(Color(0xFF17171A))
        ) {
            Text(
                text = "目录",
                color = Color(0xFF9A9AA2),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF2A2A30)))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(entries, key = { _, entry -> entry.index }) { _, entry ->
                    val isCurrent = entry.index == currentIndex
                    val total = pageCounts[entry.index]
                    val read = when {
                        total == null -> null
                        entry.index < currentIndex -> total
                        entry.index == currentIndex ->
                            ((pageMemory[entry.index] ?: fallbackPage) + 1).coerceIn(1, total)
                        else -> pageMemory[entry.index]?.plus(1) ?: 0
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry.index) }
                            .padding(horizontal = 16.dp, vertical = 13.dp)
                    ) {
                        Text(
                            text = entry.label,
                            color = if (isCurrent) Color(0xFFFA6658) else Color(0xFFDDDDE2),
                            fontSize = 15.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (read == null) "?" else "$read/$total",
                            color = Color(0xFF7C7C84),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
