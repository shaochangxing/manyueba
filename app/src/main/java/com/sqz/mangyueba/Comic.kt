package com.sqz.mangyueba

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 阅读器里的一页：只需要宽高，位图在真正显示时按目标宽度解码。 */
data class ReaderPage(val width: Int, val height: Int)

/** 支持的漫画格式；同名多格式并存时按声明顺序优先（EPUB > PDF > MOBI）。 */
enum class ComicFormat(vararg val extensions: String) {
    EPUB("epub"),
    PDF("pdf"),
    MOBI("mobi", "azw", "azw3");

    val primaryExtension: String get() = extensions.first()

    companion object {
        fun fromFileName(name: String): ComicFormat? {
            val lower = name.trim().lowercase()
            return entries.firstOrNull { format -> format.extensions.any { lower.endsWith(".$it") } }
        }
    }
}

object ComicNaming {

    /** 去掉扩展名后的名称，用于把同名不同格式的文件识别为同一部漫画。 */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val raw = if (dot > 0) fileName.substring(0, dot) else fileName
        return raw.trim().lowercase()
    }
}

/** 单卷漫画的读取入口，EPUB / PDF / MOBI 各自实现。 */
interface ComicVolume : AutoCloseable {

    /** 页尺寸列表，用于阅读页排版；实现内部会缓存结果。 */
    fun openPages(): List<ReaderPage>

    /** 按 [openPages] 返回列表的下标解码整页。 */
    fun decode(index: Int): Bitmap?

    /** 书柜封面。 */
    fun decodeCover(): Bitmap?

    override fun close()
}

/**
 * 本地副本与解析入口：把 SAF 文件复制到缓存目录，再按扩展名分派给
 * EPUB / PDF / MOBI 的读取实现。
 */
class ComicRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "comic").apply { mkdirs() }
    private val epubMetaCache = object : LruCache<String, EpubMeta>(8) {}
    private val nameCache = ConcurrentHashMap<String, String>()
    private val copyLocks = ConcurrentHashMap<String, Any>()
    private val prefetching = ConcurrentHashMap<String, Boolean>()

    /** SAF 文件显示名；查询结果按 uri 缓存，找不到时退化为 uri 末段。 */
    fun displayName(uri: Uri): String {
        val key = uri.toString()
        nameCache[key]?.let { return it }
        val name = SafUtils.displayName(appContext, uri)
            ?: Uri.decode(uri.lastPathSegment ?: "")
        if (name.isNotEmpty()) nameCache[key] = name
        return name
    }

    fun formatOf(uri: Uri): ComicFormat =
        ComicFormat.fromFileName(displayName(uri))
            ?: ComicFormat.fromFileName(Uri.decode(uri.toString()))
            ?: ComicFormat.EPUB

    fun localFile(uri: Uri): File {
        val key = uri.toString()
        val name = hash(key) + "." + formatOf(uri).primaryExtension
        val target = File(cacheDir, name)
        if (target.exists() && target.length() > 0) return target
        val lock = copyLocks.getOrPut(name) { Any() }
        synchronized(lock) {
            if (target.exists() && target.length() > 0) return target
            val tmp = File(cacheDir, "$name.tmp")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("无法读取所选文件")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return target
    }

    fun openVolume(uri: Uri, targetWidth: Int): ComicVolume {
        val file = localFile(uri)
        return when (formatOf(uri)) {
            ComicFormat.EPUB -> EpubVolume(file, targetWidth, epubMeta(uri))
            ComicFormat.PDF -> PdfVolume(file, targetWidth)
            ComicFormat.MOBI -> MobiVolume(file, targetWidth)
        }
    }

    fun epubMeta(uri: Uri): EpubMeta {
        val key = uri.toString()
        epubMetaCache.get(key)?.let { return it }
        val meta = EpubParser.parse(localFile(uri))
        epubMetaCache.put(key, meta)
        return meta
    }

    fun loadCover(uri: Uri, targetWidth: Int): Bitmap? {
        val volume = try {
            openVolume(uri, targetWidth)
        } catch (_: Exception) {
            return null
        }
        return try {
            volume.decodeCover()
        } catch (_: Exception) {
            null
        } finally {
            try {
                volume.close()
            } catch (_: Exception) {
                // 关闭失败不影响封面显示
            }
        }
    }

    /** 提前解码相邻卷的第一页，结果写入全局位图缓存。 */
    fun prefetchFirstPage(uri: Uri, targetWidth: Int, scope: CoroutineScope) {
        val key = cacheKey(uri, 0)
        if (ComicBitmapCache.get(key) != null) return
        if (prefetching.putIfAbsent(key, true) != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val volume = openVolume(uri, targetWidth)
                try {
                    volume.decode(0)?.let { ComicBitmapCache.put(key, it) }
                } finally {
                    try {
                        volume.close()
                    } catch (_: Exception) {
                        // 忽略关闭失败
                    }
                }
            } catch (_: Exception) {
                // 相邻卷暂时不可读时忽略，不影响当前阅读
            } finally {
                prefetching.remove(key)
            }
        }
    }

    companion object {
        fun cacheKey(uri: Uri, page: Int): String = "$uri#$page"

        fun sampleSizeFor(width: Int, targetWidth: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= targetWidth) sample *= 2
            return sample
        }

        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}

object ComicBitmapCache {
    private val maxSize = (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

object CoverBitmapCache {
    private val maxSize = (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
