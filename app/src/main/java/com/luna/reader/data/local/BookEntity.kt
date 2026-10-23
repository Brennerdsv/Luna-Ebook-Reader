package com.luna.reader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BookFormat {
    PDF,
    EPUB,
    TXT
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String,
    val format: BookFormat,
    val content: String,
    val totalCharacters: Int = content.length,
    val currentChapterIndex: Int = 0,
    val currentChunkIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val totalChapters: Int = 1,
    val currentProgressPercent: Float = 0f,
    val coverGradientStart: Long = 0xFFFDF5E6,
    val coverGradientEnd: Long = 0xFFE8C5A8,
    val isFavorite: Boolean = false,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val totalListeningTimeSeconds: Long = 0
)
