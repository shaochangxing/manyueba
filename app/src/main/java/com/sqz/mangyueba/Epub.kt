package com.sqz.mangyueba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

data class EpubPage(val entry: String)

data class EpubMeta(
    val title: String,
    val coverEntry: String?,
    val pages: List<EpubPage>
)

data class ReaderPage(
    val entry: String,
    val width: Int,
    val height: Int,
    val sampleSize: Int
)

/** 解析 EPUB 的 container.xml → OPF → spine，并按 spine 顺序提取图片页和封面。 */
object EpubParser {

    private val imageRegex = Regex(
        """<(?:img|image)\b[^>]*\b(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    fun parse(file: File): EpubMeta {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip)
            val opf = readOpf(zip, opfPath)
            val opfDir = opfPath.substringBeforeLast('/', "")

            val pages = ArrayList<EpubPage>()
            for (idref in opf.spine) {
                val item = opf.items[idref] ?: continue
                if (item.mediaType.startsWith("image/")) {
                    val path = resolve(opfDir, item.href)
                    if (entryExists(zip, path)) pages.add(EpubPage(path))
                } else if (item.mediaType.contains("xhtml") || item.mediaType.contains("html")) {
                    val pagePath = resolve(opfDir, item.href)
                    val html = readEntryText(zip, pagePath) ?: continue
                    val src = imageRegex.find(html)?.groupValues?.get(1) ?: continue
                    val imagePath = resolve(pagePath.substringBeforeLast('/', ""), src)
                    if (entryExists(zip, imagePath) && isImage(imagePath)) {
                        pages.add(EpubPage(imagePath))
                    }
                }
            }

            // 极少数 EPUB 的 spine 不含图片，退化为 manifest 中的图片顺序
            if (pages.isEmpty()) {
                opf.items.values
                    .filter { it.mediaType.startsWith("image/") }
                    .sortedBy { naturalKey(it.href) }
                    .forEach { item ->
                        val path = resolve(opfDir, item.href)
                        if (entryExists(zip, path)) pages.add(EpubPage(path))
                    }
            }

            val cover = findCover(zip, opf, opfDir) ?: pages.firstOrNull()?.entry
            return EpubMeta(title = opf.title, coverEntry = cover, pages = pages)
        }
    }

    private data class Item(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class Opf(
        val title: String,
        val items: Map<String, Item>,
        val spine: List<String>,
        val coverId: String?,
        val guideCoverHref: String?
    )

    private fun findOpfPath(zip: ZipFile): String {
        val container = readEntryText(zip, "META-INF/container.xml")
        if (container != null) {
            val parser = newParser(container.reader())
            var path: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && localName(parser.name) == "rootfile") {
                    path = parser.getAttributeValue(null, "full-path")
                }
                event = parser.next()
            }
            if (!path.isNullOrBlank()) return normalize(path)
        }
        return zip.entries().toList().firstOrNull { it.name.endsWith(".opf", true) }?.name
            ?: throw IOException("EPUB 缺少 OPF 文件")
    }

    private fun readOpf(zip: ZipFile, opfPath: String): Opf {
        val text = readEntryText(zip, opfPath) ?: throw IOException("无法读取 OPF")
        val parser = newParser(text.reader())
        val items = LinkedHashMap<String, Item>()
        val spine = ArrayList<String>()
        var title = ""
        var coverId: String? = null
        var guideCover: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (localName(parser.name)) {
                    "title" -> title = parser.nextText().trim()
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        val properties = parser.getAttributeValue(null, "properties") ?: ""
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            items[id] = Item(id, href, mediaType, properties)
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    "meta" -> {
                        if (parser.getAttributeValue(null, "name") == "cover") {
                            coverId = parser.getAttributeValue(null, "content")
                        }
                    }
                    "reference" -> {
                        if (parser.getAttributeValue(null, "type") == "cover") {
                            guideCover = parser.getAttributeValue(null, "href")
                        }
                    }
                }
            }
            event = parser.next()
        }
        return Opf(title, items, spine, coverId, guideCover)
    }

    private fun findCover(zip: ZipFile, opf: Opf, opfDir: String): String? {
        val candidates = ArrayList<String>()
        opf.coverId?.let { opf.items[it]?.href }?.let { candidates.add(it) }
        opf.items.values.filter { it.properties.contains("cover-image") }.forEach { candidates.add(it.href) }
        opf.guideCoverHref?.let { candidates.add(it) }
        for (href in candidates) {
            val path = resolve(opfDir, href)
            if (entryExists(zip, path) && isImage(path)) return path
        }
        return null
    }

    private fun newParser(reader: java.io.Reader): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)
        return parser
    }

    private fun localName(name: String?): String = name?.substringAfter(':') ?: ""

    private fun readEntryText(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: zip.getEntry(normalize(name)) ?: return null
        return try {
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun entryExists(zip: ZipFile, path: String): Boolean =
        zip.getEntry(path) != null || zip.getEntry(normalize(path)) != null

    private fun isImage(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
    }

    private fun resolve(baseDir: String, href: String): String {
        var value = href.substringBefore('#').substringBefore('?')
        value = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
        if (value.startsWith("/")) return normalize(value.removePrefix("/"))
        return normalize(if (baseDir.isEmpty()) value else "$baseDir/$value")
    }

    private fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    private fun naturalKey(value: String): String =
        value.lowercase().replace(Regex("(\\d+)"), "00000000$1")
}

/** EPUB 文件的本地副本、解析结果与封面解码。 */
class EpubRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "epub").apply { mkdirs() }
    private val parsedCache = object : LruCache<String, EpubMeta>(8) {}
    private val copyLocks = ConcurrentHashMap<String, Any>()
    private val prefetching = ConcurrentHashMap<String, Boolean>()

    fun parse(uri: Uri): EpubMeta {
        val key = uri.toString()
        parsedCache.get(key)?.let { return it }
        val meta = EpubParser.parse(localFile(uri))
        parsedCache.put(key, meta)
        return meta
    }

    fun localFile(uri: Uri): File {
        val key = uri.toString()
        val dir = cacheDir
        val name = hash(key) + ".epub"
        val target = File(dir, name)
        if (target.exists() && target.length() > 0) return target
        val lock = copyLocks.getOrPut(name) { Any() }
        synchronized(lock) {
            if (target.exists() && target.length() > 0) return target
            val tmp = File(dir, "$name.tmp")
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

    fun loadCover(uri: Uri, targetWidth: Int): Bitmap? {
        val meta = parse(uri)
        val entryName = meta.coverEntry ?: meta.pages.firstOrNull()?.entry ?: return null
        val file = localFile(uri)
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                decodeSampled(zip, entry, targetWidth)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 提前解析并解码相邻卷的第一页，结果写入全局位图缓存。 */
    fun prefetchFirstPage(uri: Uri, targetWidth: Int, scope: CoroutineScope) {
        val key = cacheKey(uri, 0)
        if (ComicBitmapCache.get(key) != null) return
        if (prefetching.putIfAbsent(key, true) != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val meta = parse(uri)
                val entryName = meta.pages.firstOrNull()?.entry ?: meta.coverEntry ?: return@launch
                ZipFile(localFile(uri)).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return@launch
                    decodeSampled(zip, entry, targetWidth)?.let { ComicBitmapCache.put(key, it) }
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

        fun decodeSampled(zip: ZipFile, entry: java.util.zip.ZipEntry, targetWidth: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
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
