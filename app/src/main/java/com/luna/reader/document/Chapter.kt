package com.luna.reader.document

/**
 * Representa um capítulo estruturado de um livro ou documento.
 */
data class Chapter(
    val index: Int,
    val title: String,
    val content: String = "",
    val chunks: List<TextChunk> = emptyList(),
    val startChunkGlobalIndex: Int = 0
) {
    val totalChunks: Int
        get() = chunks.size
}
