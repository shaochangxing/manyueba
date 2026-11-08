package com.sqz.mangyueba

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LibraryBook(
    val id: String,
    val title: String,
    val uris: List<String>,
    val volumeIndex: Int,
    val pageIndex: Int,
    val lastReadAt: Long,
    val addedAt: Long
) {
    val coverUri: String?
        get() = uris.getOrNull(volumeIndex) ?: uris.firstOrNull()
}

class LibraryStore(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun load(): List<LibraryBook> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val books = ArrayList<LibraryBook>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                // 旧版本可能存在单文件书，直接忽略，避免打开时崩溃
                if (o.optString("type") != "folder") continue
                val urisJson = o.optJSONArray("uris") ?: JSONArray()
                val uris = ArrayList<String>(urisJson.length())
                for (j in 0 until urisJson.length()) uris.add(urisJson.getString(j))
                if (uris.isEmpty()) continue
                books.add(
                    LibraryBook(
                        id = o.getString("id"),
                        title = o.optString("title", "未命名"),
                        uris = uris,
                        volumeIndex = o.optInt("volumeIndex", 0),
                        pageIndex = o.optInt("pageIndex", 0),
                        lastReadAt = o.optLong("lastReadAt", 0L),
                        addedAt = o.optLong("addedAt", 0L)
                    )
                )
            }
            books.sortedWith(bookOrder)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(books: List<LibraryBook>) {
        val array = JSONArray()
        books.forEach { b ->
            array.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("type", "folder")
                    put("title", b.title)
                    put("uris", JSONArray(b.uris))
                    put("volumeIndex", b.volumeIndex)
                    put("pageIndex", b.pageIndex)
                    put("lastReadAt", b.lastReadAt)
                    put("addedAt", b.addedAt)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }

    fun add(book: LibraryBook): Boolean {
        val books = load()
        // 手动重新选入视为重新加入：先清掉这些文件的删除记录，避免重扫时继续被跳过
        clearRemoved(book.uris)
        if (books.any { it.id == book.id }) return false
        save(books + book)
        return true
    }

    /**
     * 仅从书库移除该条目，不删除磁盘上的 EPUB 原文件；以后若要物理删除文件，集中在这里扩展。
     * 同时记录被移除的 document uri，重扫文件夹时跳过它们，避免被当成新增卷自动补回。
     */
    fun remove(id: String): Boolean {
        val books = load()
        val index = books.indexOfFirst { it.id == id }
        if (index < 0) return false
        val removed = books[index]
        save(books.toMutableList().also { it.removeAt(index) })
        markRemoved(removed.uris)
        return true
    }

    /** 已被用户删除的 epub document uri 集合；只有用户手动重新添加时才会被清除。 */
    fun removedUris(): Set<String> {
        val raw = prefs.getString(KEY_REMOVED_URIS, null) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            val result = LinkedHashSet<String>(array.length())
            for (i in 0 until array.length()) result.add(array.getString(i))
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun markRemoved(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val merged = LinkedHashSet(removedUris())
        merged.addAll(uris)
        prefs.edit().putString(KEY_REMOVED_URIS, JSONArray(merged).toString()).apply()
    }

    private fun clearRemoved(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val current = removedUris()
        if (current.isEmpty()) return
        val remaining = current - uris.toSet()
        if (remaining.size == current.size) return
        prefs.edit().putString(KEY_REMOVED_URIS, JSONArray(remaining.toList()).toString()).apply()
    }

    fun updateProgress(id: String, volumeIndex: Int, pageIndex: Int) {
        val books = load()
        val index = books.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = books[index].copy(
            volumeIndex = volumeIndex,
            pageIndex = pageIndex,
            lastReadAt = System.currentTimeMillis()
        )
        save(books.toMutableList().also { it[index] = updated })
    }

    /**
     * 用重新扫描到的卷列表替换书的 uris；卷列表未变化时不写盘。
     * 若当前阅读卷仍存在，按 uri 定位新下标并保留页码，避免中间插入新卷导致进度错位。
     */
    fun updateVolumes(id: String, uris: List<String>): LibraryBook? {
        val books = load()
        val index = books.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = books[index]
        if (old.uris == uris) return old
        val currentUri = old.uris.getOrNull(old.volumeIndex)
        val updated = if (currentUri != null && uris.indexOf(currentUri) >= 0) {
            old.copy(uris = uris, volumeIndex = uris.indexOf(currentUri))
        } else {
            old.copy(
                uris = uris,
                volumeIndex = old.volumeIndex.coerceIn(0, uris.lastIndex.coerceAtLeast(0)),
                pageIndex = 0
            )
        }
        save(books.toMutableList().also { it[index] = updated })
        return updated
    }

    fun lastReadBook(): LibraryBook? =
        load().maxByOrNull { it.lastReadAt }?.takeIf { it.lastReadAt > 0L }

    companion object {
        private const val KEY_BOOKS = "books_v1"
        private const val KEY_REMOVED_URIS = "removed_uris_v1"

        private val bookOrder =
            compareByDescending<LibraryBook> { it.lastReadAt }.thenByDescending { it.addedAt }
    }
}
