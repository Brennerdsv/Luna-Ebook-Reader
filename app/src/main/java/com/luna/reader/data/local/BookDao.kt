package com.luna.reader.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE format = :format ORDER BY lastReadTimestamp DESC")
    fun getBooksByFormat(format: BookFormat): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY lastReadTimestamp DESC")
    fun getFavoriteBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun observeBookById(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET currentChunkIndex = :chunkIndex, currentProgressPercent = :progress, lastReadTimestamp = :timestamp WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, chunkIndex: Int, progress: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE books SET currentChapterIndex = :chapterIndex, currentChunkIndex = :chunkIndex, currentPositionMs = :positionMs, currentProgressPercent = :progress, lastReadTimestamp = :timestamp WHERE id = :bookId")
    suspend fun updateReadingPosition(
        bookId: Long,
        chapterIndex: Int,
        chunkIndex: Int,
        positionMs: Long = 0L,
        progress: Float = 0f,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: Long)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int
}
