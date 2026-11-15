package com.sqz.mangyueba

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

data class EpubPage(val entry: String)

data class EpubMeta(
    val title: String,
    val coverEntry: String?,
    val pages: List<EpubPage>
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

/** 单卷 EPUB：持有一个 ZipFile，按 spine 提取出的图片顺序分页解码。 */
class EpubVolume(
    file: File,
    private val targetWidth: Int,
    private val meta: EpubMeta
) : ComicVolume {

    private val lock = Any()
    private val zip = ZipFile(file)
    private var pages: List<ReaderPage>? = null
    private var entries: List<String> = emptyList()
    private var samples: List<Int> = emptyList()
    private var closed = false

    override fun openPages(): List<ReaderPage> {
        synchronized(lock) {
            pages?.let { return it }
        }
        val result = ArrayList<ReaderPage>(meta.pages.size)
        val pageEntries = ArrayList<String>(meta.pages.size)
        val pageSamples = ArrayList<Int>(meta.pages.size)
        for (page in meta.pages) {
            val entry = zip.getEntry(page.entry) ?: continue
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            } catch (_: Exception) {
                continue
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
            result.add(ReaderPage(bounds.outWidth, bounds.outHeight))
            pageEntries.add(page.entry)
            pageSamples.add(ComicRepository.sampleSizeFor(bounds.outWidth, targetWidth))
        }
        synchronized(lock) {
            entries = pageEntries
            samples = pageSamples
            pages = result
        }
        return result
    }

    override fun decode(index: Int): Bitmap? {
        openPages()
        val entryName: String
        val sample: Int
        synchronized(lock) {
            if (closed) return null
            entryName = entries.getOrNull(index) ?: return null
            sample = samples.getOrNull(index) ?: 1
        }
        return try {
            val entry = zip.getEntry(entryName) ?: return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    override fun decodeCover(): Bitmap? {
        val entryName = meta.coverEntry ?: meta.pages.firstOrNull()?.entry ?: return null
        return try {
            val entry = zip.getEntry(entryName) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = ComicRepository.sampleSizeFor(bounds.outWidth, targetWidth)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                zip.close()
            } catch (_: Exception) {
                // 已经失效的 ZipFile 无需再处理
            }
        }
    }
}
