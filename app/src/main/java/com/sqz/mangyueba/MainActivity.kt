package com.sqz.mangyueba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
                            books = store.load()
                            activeBookId = book.id
                        },
                        onLibraryChanged = { updated -> books = updated }
                    )
                }
            }
        }
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
