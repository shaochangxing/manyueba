package com.sqz.mangyueba

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 单卷 MOBI / AZW / AZW3：解析 PalmDB 记录，把内嵌图片按记录顺序作为阅读页。
 * 漫画 MOBI 的页面就是图片记录，因此不需要处理文本排版。
 */
class MobiVolume(file: File, private val targetWidth: Int) : ComicVolume {

    private class ImagePage(
        val offset: Long,
        val length: Int,
        val width: Int,
        val height: Int,
        val sampleSize: Int
    )

    private class Parsed(
        val offsets: LongArray,
        val recordCount: Int,
        val fileLength: Long,
        val firstImage: Int,
        val coverImage: ImagePage?
    )

    private val lock = Any()
    private val raf = RandomAccessFile(file, "r")
    private val parsed: Parsed
    private var pages: List<ImagePage>? = null
    private var closed = false

    init {
        try {
            parsed = parse()
        } catch (e: Exception) {
            try {
                raf.close()
            } catch (_: Exception) {
                // 解析失败时关闭句柄
            }
            throw e
        }
    }

    private fun parse(): Parsed {
        val fileLength = raf.length()
        if (fileLength < 86) throw IOException("MOBI 文件不完整")
        val header = ByteArray(78)
        raf.readFully(header)
        if (ascii(header, 60, 4) != "BOOK") throw IOException("不是 MOBI 文件")
        val recordCount = u16(header, 76)
        if (recordCount <= 0) throw IOException("MOBI 记录为空")

        val info = ByteArray(recordCount * 8)
        raf.readFully(info)
        val offsets = LongArray(recordCount) { u32(info, it * 8) }

        val record0Length = ((offsets.getOrNull(1) ?: fileLength) - offsets[0]).coerceIn(1L, 64L * 1024L).toInt()
        raf.seek(offsets[0])
        val record0 = ByteArray(record0Length)
        raf.readFully(record0)

        val encryption = u16(record0, 12)
        if (encryption != 0) throw IOException("受 DRM 保护的 MOBI 无法读取")

        var firstImage = 1 + u16(record0, 8)
        var coverRecord = -1
        if (record0.size >= 16 + 96 && ascii(record0, 16, 4) == "MOBI") {
            val candidate = u32(record0, 16 + 0x5C).toInt()
            if (candidate in 1 until recordCount) firstImage = candidate
            val mobiHeaderLength = u32(record0, 20).toInt()
            val exthStart = 16 + mobiHeaderLength
            if (mobiHeaderLength > 0 && exthStart + 12 <= record0.size &&
                ascii(record0, exthStart, 4) == "EXTH"
            ) {
                val count = u32(record0, exthStart + 8).toInt()
                var pos = exthStart + 12
                var i = 0
                while (i < count && pos + 8 <= record0.size) {
                    val type = u32(record0, pos).toInt()
                    val length = u32(record0, pos + 4).toInt()
                    if (length < 8 || pos + length > record0.size) break
                    // EXTH 201：封面图片相对 firstImage 的记录偏移
                    if (type == 201 && length >= 12) {
                        coverRecord = firstImage + u32(record0, pos + 8).toInt()
                    }
                    pos += length
                    i++
                }
            }
        }

        // 封面单独解析，书架显示封面时不必扫描整本书的图片记录
        val coverImage = if (coverRecord in 0 until recordCount) {
            val start = offsets[coverRecord]
            val end = if (coverRecord + 1 < recordCount) offsets[coverRecord + 1] else fileLength
            if (end > start) readImagePage(start, (end - start).toInt()) else null
        } else {
            null
        }

        return Parsed(offsets, recordCount, fileLength, firstImage, coverImage)
    }

    private fun ensurePages(): List<ImagePage> {
        pages?.let { return it }
        val result = ArrayList<ImagePage>()
        for (index in parsed.firstImage until parsed.recordCount) {
            val start = parsed.offsets[index]
            val end = if (index + 1 < parsed.recordCount) parsed.offsets[index + 1] else parsed.fileLength
            if (end <= start) continue
            val image = readImagePage(start, (end - start).toInt()) ?: continue
            result.add(image)
        }
        pages = result
        return result
    }

    /** 先用记录开头判断格式并取尺寸，少数头部信息靠后的图片再退回整条记录。 */
    private fun readImagePage(offset: Long, length: Int): ImagePage? {
        if (length < 16) return null
        val probeLength = minOf(length, 128 * 1024)
        var data = readRange(offset, probeLength)
        if (!isImage(data)) return null
        var bounds = decodeBounds(data)
        if (bounds == null && probeLength < length) {
            data = readRange(offset, length)
            bounds = decodeBounds(data)
        }
        val size = bounds ?: return null
        return ImagePage(
            offset = offset,
            length = length,
            width = size.first,
            height = size.second,
            sampleSize = ComicRepository.sampleSizeFor(size.first, targetWidth)
        )
    }

    override fun openPages(): List<ReaderPage> = synchronized(lock) {
        ensurePages().map { ReaderPage(it.width, it.height) }
    }

    override fun decode(index: Int): Bitmap? {
        val image = synchronized(lock) {
            if (closed) return null
            ensurePages().getOrNull(index) ?: return null
        }
        return decodeImage(image)
    }

    override fun decodeCover(): Bitmap? =
        parsed.coverImage?.let { decodeImage(it) } ?: decode(0)

    private fun decodeImage(image: ImagePage): Bitmap? {
        val data = try {
            synchronized(lock) {
                if (closed) return null
                readRange(image.offset, image.length)
            }
        } catch (_: Exception) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = image.sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                raf.close()
            } catch (_: Exception) {
                // 已关闭时忽略
            }
        }
    }

    private fun readRange(offset: Long, length: Int): ByteArray {
        val data = ByteArray(length)
        raf.seek(offset)
        raf.readFully(data)
        return data
    }

    private fun decodeBounds(data: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    private fun isImage(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val jpeg = (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
        val png = (data[0].toInt() and 0xFF) == 0x89 && ascii(data, 1, 3) == "PNG"
        val gif = ascii(data, 0, 4) == "GIF8"
        val bmp = data[0].toInt() == 'B'.code && data[1].toInt() == 'M'.code
        val webp = ascii(data, 0, 4) == "RIFF" && ascii(data, 8, 4) == "WEBP"
        return jpeg || png || gif || bmp || webp
    }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > data.size) return ""
        return String(data, offset, length, Charsets.US_ASCII)
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
