package com.sqz.mangyueba

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** 单卷 PDF：用系统 PdfRenderer 把每一页渲染成图片，不依赖第三方库。 */
class PdfVolume(file: File, private val targetWidth: Int) : ComicVolume {

    private val lock = Any()
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private var pages: List<ReaderPage>? = null
    private var closed = false

    override fun openPages(): List<ReaderPage> = synchronized(lock) {
        pages ?: run {
            val result = ArrayList<ReaderPage>(renderer.pageCount)
            for (index in 0 until renderer.pageCount) {
                val page = renderer.openPage(index)
                try {
                    result.add(ReaderPage(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1)))
                } finally {
                    page.close()
                }
            }
            pages = result
            result
        }
    }

    override fun decode(index: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        if (index < 0 || index >= renderer.pageCount) return null
        val page = try {
            renderer.openPage(index)
        } catch (_: Exception) {
            return null
        }
        try {
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)
            // 按屏幕宽度渲染，和 EPUB 的采样解码保持相近的内存占用
            val width = targetWidth.coerceAtLeast(1)
            val height = (pageHeight.toLong() * width / pageWidth).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            page.close()
        }
    }

    override fun decodeCover(): Bitmap? = decode(0)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                renderer.close()
            } catch (_: Exception) {
                // 已关闭时忽略
            }
            try {
                descriptor.close()
            } catch (_: Exception) {
                // 已关闭时忽略
            }
        }
    }
}
