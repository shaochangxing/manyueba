package com.sqz.mangyueba

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max

@Composable
fun ReaderScreen(
    book: LibraryBook,
    repository: EpubRepository,
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
            Toast.makeText(context, "无法打开该 EPUB", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    BackHandler { onBack() }

    val loaded = pages
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
                onProgress = onProgress,
                onBack = onBack,
                onNextVolume = {
                    val next = volumeIndex + 1
                    if (next <= book.uris.lastIndex) {
                        Log.i("MangYueBa", "switch volume ${volumeIndex} -> $next")
                        startPage = 0
                        volumeIndex = next
                        onProgress(next, 0)
                    }
                }
            )
        }
    }
}

@Composable
private fun ReaderPager(
    book: LibraryBook,
    repository: EpubRepository,
    scope: CoroutineScope,
    session: VolumeSession,
    volumeUri: Uri,
    volumeIndex: Int,
    pages: List<ReaderPage>,
    startPage: Int,
    targetWidth: Int,
    onProgress: (volume: Int, page: Int) -> Unit,
    onBack: () -> Unit,
    onNextVolume: () -> Unit
) {
    val listState = remember(volumeUri) {
        LazyListState(
            firstVisibleItemIndex = startPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        )
    }
    var scale by remember(volumeUri) { mutableFloatStateOf(1f) }
    var offset by remember(volumeUri) { mutableStateOf(Offset.Zero) }
    val zoomed = scale > 1.001f

    val currentProgress by rememberUpdatedState(onProgress)
    val currentBack by rememberUpdatedState(onBack)

    fun leaveReader() {
        currentProgress(volumeIndex, listState.firstVisibleItemIndex)
        currentBack()
    }

    BackHandler { leaveReader() }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> onProgress(volumeIndex, index) }
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
            .collect { (first, last) ->
                val targets = listOf(first - 1, last + 1, last + 2, first - 2)
                    .filter { it in pages.indices }
                if (targets.isNotEmpty()) {
                    scope.launch {
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
                    onTap = {
                        scale = 1f
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(volumeUri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 4f)
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

/** 单卷阅读会话：持有 ZipFile、页尺寸、位图解码与并发去重。 */
class VolumeSession(
    private val repository: EpubRepository,
    val uri: Uri,
    private val targetWidth: Int,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var zip: ZipFile? = null
    private var pages: List<ReaderPage> = emptyList()
    private val jobs = HashMap<Int, Deferred<Bitmap?>>()
    private var closed = false

    suspend fun open(): List<ReaderPage> = withContext(Dispatchers.IO) {
        val meta = repository.parse(uri)
        val zipFile = ZipFile(repository.localFile(uri))
        try {
            val result = ArrayList<ReaderPage>(meta.pages.size)
            for (page in meta.pages) {
                val entry = zipFile.getEntry(page.entry) ?: continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    zipFile.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                } catch (_: Exception) {
                    continue
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                result.add(
                    ReaderPage(
                        entry = page.entry,
                        width = bounds.outWidth,
                        height = bounds.outHeight,
                        sampleSize = EpubRepository.sampleSizeFor(bounds.outWidth, targetWidth)
                    )
                )
            }
            synchronized(lock) {
                if (closed) {
                    zipFile.close()
                    emptyList()
                } else {
                    zip = zipFile
                    pages = result
                    Log.i("MangYueBa", "opened $uri pages=${result.size}")
                    result
                }
            }
        } catch (e: Exception) {
            zipFile.close()
            throw e
        }
    }

    fun cacheKey(index: Int): String = EpubRepository.cacheKey(uri, index)

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

    private fun decode(index: Int): Bitmap? = synchronized(lock) {
        val zipFile = zip ?: return null
        val page = pages.getOrNull(index) ?: return null
        val entry = zipFile.getEntry(page.entry) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = page.sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        try {
            zipFile.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        val pending: List<Deferred<Bitmap?>>
        synchronized(lock) {
            closed = true
            zip?.close()
            zip = null
            pending = jobs.values.toList()
            jobs.clear()
        }
        pending.forEach { it.cancel() }
    }
}
