package com.sqz.mangyueba

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ShelfBackground = Color(0xFF0D0D0F)
private val CoverPlaceholder = Color(0xFF1A1A1E)

@Composable
fun ShelfScreen(
    books: List<LibraryBook>,
    store: LibraryStore,
    repository: EpubRepository,
    targetWidth: Int,
    onOpenBook: (LibraryBook) -> Unit,
    onLibraryChanged: (List<LibraryBook>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val addFolder: (Uri) -> Unit = { treeUri ->
        scope.launch {
            adding = true
            try {
                SafUtils.persistReadPermission(context, treeUri)
                val files = withContext(Dispatchers.IO) { SafUtils.listEpubFiles(context, treeUri) }
                if (files.isEmpty()) {
                    showToast("该文件夹中没有 EPUB 文件")
                } else {
                    withContext(Dispatchers.IO) { repository.parse(files.first()) }
                    val folderName = SafUtils.treeDisplayName(context, treeUri) ?: "漫画文件夹"
                    val added = store.add(
                        LibraryBook(
                            id = "folder:$treeUri",
                            title = folderName,
                            uris = files.map { it.toString() },
                            volumeIndex = 0,
                            pageIndex = 0,
                            lastReadAt = 0L,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                    if (!added) showToast("这个文件夹已在书柜中")
                    onLibraryChanged(store.load())
                }
            } catch (_: Exception) {
                showToast("无法读取该文件夹")
            } finally {
                adding = false
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(addFolder) }

    Box(Modifier.fillMaxSize().background(ShelfBackground)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "漫阅吧",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 14.dp)
            )
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有漫画，点击右下角 + 添加",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCell(
                            book = book,
                            repository = repository,
                            targetWidth = (targetWidth / 3).coerceAtLeast(180),
                            onClick = { onOpenBook(book) }
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(22.dp)) {
            FloatingActionButton(
                onClick = { folderLauncher.launch(null) },
                shape = CircleShape,
                containerColor = Color(0xFFEDEDF0),
                contentColor = Color(0xFF0D0D0F)
            ) {
                Text(text = "+", fontSize = 30.sp, fontWeight = FontWeight.Light)
            }
        }

        if (adding) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun BookCell(
    book: LibraryBook,
    repository: EpubRepository,
    targetWidth: Int,
    onClick: () -> Unit
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(6.dp))
                .background(CoverPlaceholder)
        ) {
            BookCover(book, repository, targetWidth)
        }
        Text(
            text = book.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

@Composable
private fun BookCover(book: LibraryBook, repository: EpubRepository, targetWidth: Int) {
    val coverUri = book.coverUri
    var bitmap by remember(coverUri) {
        mutableStateOf(coverUri?.let { CoverBitmapCache.get(it) })
    }

    LaunchedEffect(coverUri) {
        if (coverUri == null) return@LaunchedEffect
        val cached = CoverBitmapCache.get(coverUri)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { repository.loadCover(Uri.parse(coverUri), targetWidth) }.getOrNull()
        }
        if (loaded != null) {
            CoverBitmapCache.put(coverUri, loaded)
            bitmap = loaded
        }
    }

    val image: Bitmap? = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
