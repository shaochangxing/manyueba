package com.sqz.mangyueba

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var store: LibraryStore
    private lateinit var repository: EpubRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = LibraryStore(this)
        repository = EpubRepository(this)
        val screenWidth = resources.displayMetrics.widthPixels

        setContent {
            MangYueBaTheme {
                var books by remember { mutableStateOf(store.load()) }
                var activeBookId by rememberSaveable { mutableStateOf(store.lastReadBook()?.id) }
                val activeBook = books.firstOrNull { it.id == activeBookId }

                // 进入/打开漫画时重扫文件夹：只列目录条目，已有卷继续复用解析缓存
                LaunchedEffect(activeBookId) {
                    val book = activeBook ?: return@LaunchedEffect
                    refreshVolumes(book)?.let { books = it }
                }

                if (activeBook != null) {
                    ReaderScreen(
                        book = activeBook,
                        repository = repository,
                        targetWidth = screenWidth,
                        onProgress = { volume, page ->
                            store.updateProgress(activeBook.id, volume, page)
                        },
                        onBack = {
                            books = store.load()
                            activeBookId = null
                        }
                    )
                } else {
                    ShelfScreen(
                        books = books,
                        store = store,
                        repository = repository,
                        targetWidth = screenWidth,
                        onOpenBook = { book ->
                            store.updateProgress(book.id, book.volumeIndex, book.pageIndex)
                            activeBookId = book.id
                        },
                        onLibraryChanged = { updated -> books = updated }
                    )
                }
            }
        }
    }

    /** 读出文件夹当前所有 epub 的 document uri，按自然序返回；读取失败或为空时返回 null，避免清空已有卷。 */
    private fun scanVolumes(book: LibraryBook): List<String>? {
        if (!book.id.startsWith("folder:")) return null
        val treeUri = Uri.parse(book.id.removePrefix("folder:"))
        val files = SafUtils.listEpubFiles(this, treeUri).map { it.toString() }
        return files.ifEmpty { null }
    }

    /** 目录内容未变化时返回 null；有变化则写回书库并返回刷新后的书库。 */
    private suspend fun refreshVolumes(book: LibraryBook): List<LibraryBook>? {
        val scanned = withContext(Dispatchers.IO) { scanVolumes(book) } ?: return null
        // 跳过用户已删除的 epub，避免重扫时把它们当成新增卷补回
        val removed = withContext(Dispatchers.IO) { store.removedUris() }
        val effective = if (removed.isEmpty()) scanned else scanned.filterNot { it in removed }
        // 过滤后为空或与现有卷一致时不写回，避免误清空书库与删除记录
        if (effective.isEmpty() || effective == book.uris) return null
        withContext(Dispatchers.IO) { store.updateVolumes(book.id, effective) } ?: return null
        return withContext(Dispatchers.IO) { store.load() }
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDEDF0),
    onPrimary = Color(0xFF101012),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFE9E9EC),
    surface = Color(0xFF151517),
    onSurface = Color(0xFFE9E9EC),
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Color(0xFFB9B9BE)
)

@Composable
fun MangYueBaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else DarkColors,
        content = content
    )
}
