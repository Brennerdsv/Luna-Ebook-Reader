package com.luna.reader.document

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Extrator de texto para documentos PDF com limpeza editorial.
 * Remove quebras de linha duras, hifens órfãos no fim de linhas e normaliza espaçamento.
 */
class PdfTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "PdfTextExtractor"
    }

    suspend fun extractTextFromUri(uri: Uri): ExtractedDocument = withContext(Dispatchers.IO) {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        requireNotNull(inputStream) { "Não foi possível abrir o arquivo PDF a partir da URI fornecida." }
        extractText(inputStream)
    }

    suspend fun extractTextFromFile(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        file.inputStream().use { inputStream ->
            extractText(inputStream)
        }
    }

    private fun extractText(inputStream: InputStream): ExtractedDocument {
        return try {
            val document = PDDocument.load(inputStream)
            val totalPages = document.numberOfPages
            val stripper = PDFTextStripper()

            val pagesText = mutableListOf<String>()
            val fullTextBuilder = StringBuilder()

            for (page in 1..totalPages) {
                stripper.startPage = page
                stripper.endPage = page
                val rawPageText = stripper.getText(document)
                val cleanedText = cleanEditorialText(rawPageText)
                if (cleanedText.isNotBlank()) {
                    pagesText.add(cleanedText)
                    fullTextBuilder.append(cleanedText).append("\n\n")
                }
            }

            document.close()

            val fullText = fullTextBuilder.toString().trim()
            val chapters = DocumentChunker.splitIntoChapters(fullText)

            ExtractedDocument(
                title = "Documento PDF",
                author = "Autor Desconhecido",
                fullText = fullText,
                chapters = chapters,
                pages = pagesText,
                pageCount = totalPages
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair texto do PDF", e)
            throw e
        }
    }

    /**
     * Normaliza hifenizações de fim de linha (ex: "consis-\ntente" -> "consistente"),
     * remove quebras de linha duras dentro do mesmo parágrafo e consolida múltiplos espaços.
     */
    private fun cleanEditorialText(rawText: String): String {
        return rawText
            // Junta palavras hifenizadas no final de linha
            .replace(Regex("(\\p{L}+)-\\r?\\n(\\p{L}+)"), "$1$2")
            // Converte quebras de linha simples dentro de frases para espaço
            .replace(Regex("(?<!\\n)\\r?\\n(?!\\n)"), " ")
            // Limpa múltiplos espaços
            .replace(Regex("[ \\t]+"), " ")
            // Normaliza quebras de parágrafo duplas
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

data class ExtractedDocument(
    val title: String,
    val author: String,
    val fullText: String,
    val chapters: List<Chapter> = emptyList(),
    val pages: List<String> = emptyList(),
    val pageCount: Int = 1
)
