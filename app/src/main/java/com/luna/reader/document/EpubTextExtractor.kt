package com.luna.reader.document

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Extrator nativo para arquivos EPUB com parsing completo da Tabela de Conteúdos (TOC / toc.ncx / nav.xhtml).
 * Descompacta o arquivo ZIP, resolve a ordem do Spine, extrai os capítulos estruturados
 * e gera os blocos editoriais prontos para reprodução neural contínua.
 */
class EpubTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "EpubTextExtractor"
    }

    suspend fun extractTextFromUri(uri: Uri): ExtractedDocument = withContext(Dispatchers.IO) {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        requireNotNull(inputStream) { "Não foi possível abrir o arquivo EPUB a partir da URI." }
        extractFromStream(inputStream)
    }

    suspend fun extractTextFromFile(file: File): ExtractedDocument = withContext(Dispatchers.IO) {
        file.inputStream().use { inputStream ->
            extractFromStream(inputStream)
        }
    }

    private fun extractFromStream(inputStream: InputStream): ExtractedDocument {
        val entriesMap = mutableMapOf<String, ByteArray>()
        var detectedTitle: String? = null
        var detectedAuthor: String? = null

        // 1. Carrega todas as entradas relevantes do ZIP na memória
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    if (name.endsWith(".xhtml", ignoreCase = true) ||
                        name.endsWith(".html", ignoreCase = true) ||
                        name.endsWith(".opf", ignoreCase = true) ||
                        name.endsWith(".ncx", ignoreCase = true) ||
                        name.endsWith(".xml", ignoreCase = true)
                    ) {
                        entriesMap[name] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 2. Determina o caminho do arquivo .opf
        var opfPath = entriesMap.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        val containerEntry = entriesMap["META-INF/container.xml"]
        if (containerEntry != null) {
            try {
                val containerDoc = Jsoup.parse(String(containerEntry, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                val rootfile = containerDoc.select("rootfile[full-path]").first()?.attr("full-path")
                if (!rootfile.isNullOrBlank() && entriesMap.containsKey(rootfile)) {
                    opfPath = rootfile
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ler META-INF/container.xml", e)
            }
        }

        val opfDir = if (opfPath != null && opfPath.contains("/")) {
            opfPath.substringBeforeLast("/") + "/"
        } else {
            ""
        }

        // Mapa de TOC: Nome do arquivo (ou src) -> Título do capítulo
        val tocTitlesByHref = mutableMapOf<String, String>()

        // 3. Lê metadados, manifesto e spine do OPF
        val spineHrefs = mutableListOf<String>()
        if (opfPath != null && entriesMap.containsKey(opfPath)) {
            try {
                val opfContent = String(entriesMap[opfPath]!!, Charsets.UTF_8)
                val opfDoc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())

                detectedTitle = opfDoc.select("dc|title, title").first()?.text()
                detectedAuthor = opfDoc.select("dc|creator, creator").first()?.text()

                val manifestMap = mutableMapOf<String, String>()
                opfDoc.select("manifest > item").forEach { item ->
                    val id = item.attr("id")
                    val href = item.attr("href")
                    manifestMap[id] = href
                }

                opfDoc.select("spine > itemref").forEach { itemref ->
                    val idref = itemref.attr("idref")
                    val href = manifestMap[idref]
                    if (!href.isNullOrBlank()) {
                        val fullPath = normalizeZipPath(opfDir + href)
                        spineHrefs.add(fullPath)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao processar OPF", e)
            }
        }

        // 4. Parsing da Tabela de Conteúdos (toc.ncx)
        val ncxEntry = entriesMap.entries.find { it.key.endsWith(".ncx", ignoreCase = true) }
        if (ncxEntry != null) {
            try {
                val ncxDoc = Jsoup.parse(String(ncxEntry.value, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                ncxDoc.select("navPoint").forEach { point ->
                    val label = point.select("navLabel > text").first()?.text()?.trim()
                    val src = point.select("content").first()?.attr("src")?.substringBefore("#")
                    if (!label.isNullOrBlank() && !src.isNullOrBlank()) {
                        val fullPath = normalizeZipPath(opfDir + src)
                        tocTitlesByHref[fullPath] = label
                        tocTitlesByHref[src] = label
                        tocTitlesByHref[src.substringAfterLast("/")] = label
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao processar toc.ncx", e)
            }
        }

        // 5. Ordem dos arquivos a processar
        val orderedFiles = if (spineHrefs.isNotEmpty()) {
            spineHrefs.filter { entriesMap.containsKey(it) }
        } else {
            entriesMap.keys.filter {
                it.endsWith(".xhtml", ignoreCase = true) || it.endsWith(".html", ignoreCase = true)
            }.sorted()
        }

        val chapters = mutableListOf<Chapter>()
        val chaptersText = mutableListOf<String>()
        val fullBookBuilder = StringBuilder()
        var globalChunkCounter = 0

        for (filePath in orderedFiles) {
            val contentBytes = entriesMap[filePath] ?: continue
            val html = String(contentBytes, Charsets.UTF_8)
            val doc = Jsoup.parse(html)

            // Detecta título da página/capítulo
            val fileName = filePath.substringAfterLast("/")
            var chapterTitle = tocTitlesByHref[filePath]
                ?: tocTitlesByHref[fileName]
                ?: doc.select("h1, h2, h3, title").first()?.text()?.trim()

            // Remove tags não textuais
            doc.select("script, style, noscript, nav").remove()
            val cleanText = doc.body().text().trim()

            // Ignora capas ou páginas sem conteúdo relevante
            if (cleanText.length < 40) continue

            if (chapterTitle.isNullOrBlank() || chapterTitle.length > 80) {
                chapterTitle = "Capítulo ${chapters.size + 1}"
            }

            chaptersText.add(cleanText)
            fullBookBuilder.append(chapterTitle).append("\n\n").append(cleanText).append("\n\n")

            val chapterIndex = chapters.size
            val chunks = DocumentChunker.chunkText(
                rawText = cleanText,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                startGlobalIndex = globalChunkCounter
            )

            chapters.add(
                Chapter(
                    index = chapterIndex,
                    title = chapterTitle,
                    content = cleanText,
                    chunks = chunks,
                    startChunkGlobalIndex = globalChunkCounter
                )
            )
            globalChunkCounter += chunks.size
        }

        // Fallback caso a divisão de capítulos do EPUB tenha falhado
        val finalChapters = if (chapters.isNotEmpty()) {
            chapters
        } else {
            DocumentChunker.splitIntoChapters(fullBookBuilder.toString())
        }

        return ExtractedDocument(
            title = detectedTitle ?: "Livro EPUB",
            author = detectedAuthor ?: "Autor Desconhecido",
            fullText = fullBookBuilder.toString().trim(),
            chapters = finalChapters,
            pages = chaptersText,
            pageCount = finalChapters.size
        )
    }

    private fun normalizeZipPath(path: String): String {
        val parts = path.split("/")
        val stack = mutableListOf<String>()
        for (part in parts) {
            if (part == "." || part.isEmpty()) continue
            if (part == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(part)
            }
        }
        return stack.joinToString("/")
    }
}
