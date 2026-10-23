package com.luna.reader.document

/**
 * Representa um bloco de texto otimizado para síntese neural contínua.
 */
data class TextChunk(
    val index: Int,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val text: String,
    val startCharIndex: Int = 0,
    val endCharIndex: Int = 0
)

/**
 * Utilitário para particionamento inteligente de textos longos.
 * Divide textos em blocos de 150 a 450 caracteres, respeitando fronteiras de pontuação
 * (como '.', '!', '?', ';', '\n') para síntese neural fluida e sem cortes no meio de frases.
 */
object DocumentChunker {

    private const val MIN_CHUNK_SIZE = 150
    private const val MAX_CHUNK_SIZE = 450

    private val CHAPTER_REGEX = Regex(
        """(?im)^(?:cap[íi]tulo\s+[0-9ivxlcdm]+|parte\s+[0-9ivxlcdm]+|se[çc][ãa]o\s+[0-9ivxlcdm]+|pr[óo]logo|ep[íi]logo|introdu[çc][ãa]o|conclus[ãa]o|chapter\s+[0-9ivxlcdm]+)\b.*$"""
    )

    /**
     * Quebra o texto em blocos contínuos.
     */
    fun chunkText(
        rawText: String,
        chapterIndex: Int = 0,
        chapterTitle: String = "",
        startGlobalIndex: Int = 0
    ): List<TextChunk> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chunks = mutableListOf<TextChunk>()
        var currentIndex = 0
        val length = trimmed.length
        var chunkCounter = startGlobalIndex

        while (currentIndex < length) {
            val remaining = length - currentIndex
            if (remaining <= MAX_CHUNK_SIZE) {
                val chunkText = trimmed.substring(currentIndex).trim()
                if (chunkText.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            index = chunkCounter++,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            text = chunkText,
                            startCharIndex = currentIndex,
                            endCharIndex = length
                        )
                    )
                }
                break
            }

            val searchWindowStart = currentIndex + MIN_CHUNK_SIZE
            val searchWindowEnd = (currentIndex + MAX_CHUNK_SIZE).coerceAtMost(length)
            val window = trimmed.substring(searchWindowStart, searchWindowEnd)

            var bestBreakOffset = -1

            val sentenceEndIndex = window.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (sentenceEndIndex != -1) {
                bestBreakOffset = searchWindowStart + sentenceEndIndex + 1
            } else {
                val clauseBreakIndex = window.lastIndexOfAny(charArrayOf(';', ':', '\n'))
                if (clauseBreakIndex != -1) {
                    bestBreakOffset = searchWindowStart + clauseBreakIndex + 1
                } else {
                    val commaIndex = window.lastIndexOf(',')
                    if (commaIndex != -1) {
                        bestBreakOffset = searchWindowStart + commaIndex + 1
                    } else {
                        val spaceIndex = window.lastIndexOf(' ')
                        if (spaceIndex != -1) {
                            bestBreakOffset = searchWindowStart + spaceIndex + 1
                        }
                    }
                }
            }

            val splitEnd = if (bestBreakOffset != -1) bestBreakOffset else searchWindowEnd
            val chunkStr = trimmed.substring(currentIndex, splitEnd).trim()

            if (chunkStr.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        index = chunkCounter++,
                        chapterIndex = chapterIndex,
                        chapterTitle = chapterTitle,
                        text = chunkStr,
                        startCharIndex = currentIndex,
                        endCharIndex = splitEnd
                    )
                )
            }

            currentIndex = splitEnd
            while (currentIndex < length && trimmed[currentIndex].isWhitespace()) {
                currentIndex++
            }
        }

        return chunks
    }

    /**
     * Detecta capítulos via expressões regulares heurísticas para arquivos PDF,
     * textos colados ou e-books sem sumário explícito.
     */
    fun splitIntoChapters(rawText: String): List<Chapter> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val matches = CHAPTER_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty() || matches.size == 1 && matches.first().range.first > 500) {
            // Nenhum capítulo formal detectado; cria capítulo único
            val chunks = chunkText(trimmed, chapterIndex = 0, chapterTitle = "Texto Principal", startGlobalIndex = 0)
            return listOf(
                Chapter(
                    index = 0,
                    title = "Texto Principal",
                    content = trimmed,
                    chunks = chunks,
                    startChunkGlobalIndex = 0
                )
            )
        }

        val chapters = mutableListOf<Chapter>()
        var globalChunkCounter = 0

        // Se houver prólogo ou texto anterior ao primeiro capítulo
        val firstMatch = matches.first()
        if (firstMatch.range.first > 0) {
            val introText = trimmed.substring(0, firstMatch.range.first).trim()
            if (introText.length > 50) {
                val introChunks = chunkText(introText, chapterIndex = 0, chapterTitle = "Introdução / Início", startGlobalIndex = globalChunkCounter)
                chapters.add(
                    Chapter(
                        index = 0,
                        title = "Introdução / Início",
                        content = introText,
                        chunks = introChunks,
                        startChunkGlobalIndex = globalChunkCounter
                    )
                )
                globalChunkCounter += introChunks.size
            }
        }

        for (i in matches.indices) {
            val match = matches[i]
            val chapterTitle = match.value.trim()
            val contentStart = match.range.last + 1
            val contentEnd = if (i + 1 < matches.size) matches[i + 1].range.first else trimmed.length
            val chapterContent = if (contentStart < contentEnd) {
                trimmed.substring(contentStart, contentEnd).trim()
            } else {
                ""
            }

            val chapterIndex = chapters.size
            val chunks = chunkText(chapterContent, chapterIndex = chapterIndex, chapterTitle = chapterTitle, startGlobalIndex = globalChunkCounter)

            chapters.add(
                Chapter(
                    index = chapterIndex,
                    title = chapterTitle,
                    content = chapterContent,
                    chunks = chunks,
                    startChunkGlobalIndex = globalChunkCounter
                )
            )
            globalChunkCounter += chunks.size
        }

        return chapters
    }
}
