package com.sqz.mangyueba

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

object SafUtils {

    fun persistReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 某些文档提供方不支持持久授权，本次会话内仍可读取
        }
    }

    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    fun treeDisplayName(context: Context, treeUri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            displayName(context, docUri)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 枚举文件夹下一层的所有受支持漫画文件，按文件名自然排序。
     * 同名不同格式（如 xxx.mobi / xxx.pdf）只保留一个，优先 EPUB > PDF > MOBI，
     * 这样同一部漫画不会被当成多卷重复加入。
     */
    fun listComicFiles(context: Context, treeUri: Uri): List<Uri> {
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return emptyList()
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val entries = ArrayList<FoundFile>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val format = ComicFormat.fromFileName(name) ?: continue
                    entries.add(FoundFile(docId, name, format))
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        if (entries.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<FoundFile>>()
        for (entry in entries) {
            grouped.getOrPut(ComicNaming.baseName(entry.name)) { ArrayList() }.add(entry)
        }
        val selected = grouped.values.mapNotNull { group ->
            group.minWithOrNull(
                compareBy<FoundFile> { it.format.ordinal }.thenBy { it.name.lowercase() }
            )
        }
        val sorted = selected.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        return sorted.map { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.docId) }
    }

    private class FoundFile(val docId: String, val name: String, val format: ComicFormat)

    fun naturalCompare(a: String, b: String): Int {
        val sa = a.lowercase()
        val sb = b.lowercase()
        var i = 0
        var j = 0
        while (i < sa.length && j < sb.length) {
            val ca = sa[i]
            val cb = sb[j]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = i
                while (endA < sa.length && sa[endA].isDigit()) endA++
                var endB = j
                while (endB < sb.length && sb[endB].isDigit()) endB++
                val numA = sa.substring(i, endA).trimStart('0').ifEmpty { "0" }
                val numB = sb.substring(j, endB).trimStart('0').ifEmpty { "0" }
                val cmp = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (sa.length - i) - (sb.length - j)
    }
}
