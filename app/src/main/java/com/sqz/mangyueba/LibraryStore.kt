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
        if (books.any { it.id == book.id }) return false
        save(books + book)
        return true
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

    fun lastReadBook(): LibraryBook? =
        load().maxByOrNull { it.lastReadAt }?.takeIf { it.lastReadAt > 0L }

    companion object {
        private const val KEY_BOOKS = "books_v1"

        private val bookOrder =
            compareByDescending<LibraryBook> { it.lastReadAt }.thenByDescending { it.addedAt }
    }
}
