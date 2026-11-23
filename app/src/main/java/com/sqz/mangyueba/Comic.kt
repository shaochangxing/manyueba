package com.sqz.mangyueba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    // 目录条目名称的提取规则，按优先级依次尝试
    private val DiNumbered = Regex("第\\s*([0-9一二三四五六七八九十]{1,7})\\s*(卷|話|话|章|冊|册)")
    private val NumberBeforeUnit = Regex("(\\d{1,4}(?:-\\d{1,4})?)\\s*(卷|話|话|章|冊|册)")
    private val NumberAfterUnit = Regex("(卷|話|话|章|冊|册)\\s*第?\\s*(\\d{1,4}(?:-\\d{1,4})?)")
    private val AnyNumber = Regex("\\d{1,4}(?:-\\d{1,4})?")
    private val Spaces = Regex("\\s+")
    private val TrailingSeparators = Regex("[\\s\\-－_—・~～]+")

    /**
     * 目录条目名称：从文件名提取“01卷 / 卷01 / 話001-010 / 第3章 / 01”这类编号；
     * 提取不到时退化为清理掉括号来源信息后的文件名前 12 字。
     */
    fun volumeLabel(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        // 去掉括号组（含全角）与方括号组，避免作者名、z-library 来源信息等干扰
        val cleaned = base
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("（[^）]*）"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
        // 全角数字转半角，便于匹配
        val net = cleaned.map { ch ->
            if (ch in '０'..'９') ('0' + (ch - '０')) else ch
        }.joinToString("").trim()

        DiNumbered.find(net)?.let { return it.value.replace(Spaces, "") }
        NumberBeforeUnit.find(net)?.let { return it.value.replace(Spaces, "") }
        NumberAfterUnit.find(net)?.let { return it.value.replace(Spaces, "") }
        AnyNumber.findAll(net).lastOrNull()?.let { return it.value }
        val fallback = TrailingSeparators.replace(net, " ").trim()
        return if (fallback.isEmpty()) {
            base.trim().take(12).ifEmpty { fileName.trim().ifEmpty { "未命名" } }
        } else {
            fallback.take(12)
        }
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
    private val coverDir = File(appContext.cacheDir, "covers").apply { mkdirs() }
    private val epubMetaCache = object : LruCache<String, EpubMeta>(8) {}
    private val nameCache = ConcurrentHashMap<String, String>()
    private val copyLocks = ConcurrentHashMap<String, Any>()
    private val prefetching = ConcurrentHashMap<String, Boolean>()
    private val coverPreloading = ConcurrentHashMap<String, Boolean>()
    private val pageCountCache = ConcurrentHashMap<String, Int>()

    // 目录页数的磁盘缓存：uri -> 总页数，跨进程重启复用，避免每次打开都逐卷解析
    private val tocFile = File(appContext.cacheDir, "toc_pages.json")
    private val tocStore = JSONObject()
    private val tocLock = Any()
    @Volatile private var tocLoaded = false

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** 书柜封面：先查磁盘缓存，未命中再解码并写盘，返回书柜时无需重复解析原文件。 */
    fun loadCover(uri: Uri, targetWidth: Int): Bitmap? {
        val key = uri.toString()
        diskCover(key, targetWidth)?.let { return it }
        val bitmap = try {
            openVolume(uri, targetWidth).use { it.decodeCover() }
        } catch (_: Exception) {
            null
        } ?: return null
        writeCover(key, bitmap)
        return bitmap
    }

    /**
     * 后台预热书柜封面到内存缓存；冷启动直接进阅读页时提前调用，
     * 返回书柜时所有封面首帧即可显示，不再出现占位块闪烁。
     */
    fun preloadCovers(uris: List<String>, targetWidth: Int) {
        for (uriString in uris) {
            if (CoverBitmapCache.get(uriString) != null) continue
            if (coverPreloading.putIfAbsent(uriString, true) != null) continue
            preloadScope.launch {
                try {
                    loadCover(Uri.parse(uriString), targetWidth)
                        ?.let { CoverBitmapCache.put(uriString, it) }
                } catch (_: Exception) {
                    // 单个封面预热失败不影响其它封面
                } finally {
                    coverPreloading.remove(uriString)
                }
            }
        }
    }

    /**
     * 目录用：统计一个卷的总页数；文件不可读或 DRM 受限时返回 null。
     * 结果进程内缓存 + 磁盘缓存（toc_pages.json），同一会话即时返回，
     * 重启 App 后首次打开目录也无需重新解析。
     */
    suspend fun pageCount(uri: Uri, targetWidth: Int): Int? {
        val key = uri.toString()
        pageCountCache[key]?.let { return it }
        synchronized(tocLock) {
            ensureTocLoaded()
            val disk = tocStore.optInt(key, -1)
            if (disk >= 0) {
                pageCountCache[key] = disk
                return disk
            }
        }
        val count = withContext(Dispatchers.IO) {
            try {
                openVolume(uri, targetWidth).use { it.openPages().size }
            } catch (_: Exception) {
                null
            }
        }
        if (count != null) {
            pageCountCache[key] = count
            synchronized(tocLock) {
                tocStore.put(key, count)
                persistTocLocked()
            }
        }
        return count
    }

    /** 首次访问时读取磁盘缓存；损坏则直接重建。 */
    private fun ensureTocLoaded() {
        if (tocLoaded) return
        try {
            tocFile.takeIf { it.exists() }?.bufferedReader()?.use { reader ->
                val obj = JSONObject(reader.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    tocStore.put(key, obj.optInt(key, -1))
                }
            }
        } catch (_: Exception) {
            // 缓存损坏时清空重建，不影响功能
        }
        tocLoaded = true
    }

    /** 写盘：临时文件 + 原子替换，崩溃不留半截文件。 */
    private fun persistTocLocked() {
        try {
            val tmp = File(tocFile.parentFile, tocFile.name + ".tmp")
            tmp.writeText(tocStore.toString())
            if (tocFile.exists()) tocFile.delete()
            if (!tmp.renameTo(tocFile)) {
                tmp.copyTo(tocFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 写盘失败只影响下次启动的缓存命中
        }
    }

    private fun coverFile(key: String): File = File(coverDir, hash(key) + ".jpg")

    /** 从磁盘封面按目标宽度采样解码；文件损坏时删除并返回 null，走完整解码路径。 */
    private fun diskCover(key: String, targetWidth: Int): Bitmap? {
        val file = coverFile(key)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                file.delete()
                return null
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Exception) {
            null
        }
    }

    /** 封面写盘；失败只影响下次的磁盘命中，不影响本次显示。 */
    private fun writeCover(key: String, bitmap: Bitmap) {
        try {
            val tmp = File(coverDir, hash(key) + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val target = coverFile(key)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 写盘失败不影响封面显示
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
