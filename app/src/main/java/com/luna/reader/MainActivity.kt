package com.luna.reader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.luna.reader.data.local.BookEntity
import com.luna.reader.data.local.BookFormat
import com.luna.reader.data.local.LunaDatabase
import com.luna.reader.document.EpubTextExtractor
import com.luna.reader.document.PdfTextExtractor
import com.luna.reader.playback.AudioPrefetchManager
import com.luna.reader.ui.screens.LibraryScreen
import com.luna.reader.ui.screens.ReaderPlayerScreen
import com.luna.reader.ui.theme.LunaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var database: LunaDatabase
    private lateinit var audioManager: AudioPrefetchManager
    private lateinit var pdfExtractor: PdfTextExtractor
    private lateinit var epubExtractor: EpubTextExtractor

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permissão concedida ou negada */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = LunaDatabase.getInstance(this)
        audioManager = AudioPrefetchManager.getInstance(applicationContext)
        pdfExtractor = PdfTextExtractor(this)
        epubExtractor = EpubTextExtractor(this)

        requestNotificationPermission()
        seedSampleBookIfEmpty()

        setContent {
            LunaTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Library) }
                val books by database.bookDao().getAllBooks().collectAsState(initial = emptyList())
                val playbackState by audioManager.uiState.collectAsState()

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (targetState is Screen.Reader) {
                            slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it / 3 } + fadeOut()
                        } else {
                            slideInVertically { -it / 3 } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
                        }
                    },
                    label = "ScreenTransition",
                    modifier = Modifier.fillMaxSize()
                ) { screen ->
                    when (screen) {
                        is Screen.Library -> {
                            LibraryScreen(
                                books = books,
                                playbackState = playbackState,
                                onBookClick = { book ->
                                    if (playbackState.bookId != book.id) {
                                        audioManager.loadDocument(
                                            bookId = book.id,
                                            title = book.title,
                                            author = book.author,
                                            fullText = book.content,
                                            startChapterIndex = book.currentChapterIndex,
                                            startChunkIndex = book.currentChunkIndex,
                                            autoPlay = false
                                        )
                                    }
                                    currentScreen = Screen.Reader
                                },
                                onImportUri = { uri -> importDocument(uri) },
                                onPasteText = { title, author, content ->
                                    saveBook(
                                        title = title,
                                        author = author,
                                        format = BookFormat.TXT,
                                        content = content,
                                        gradientStart = 0xFFFDF5E6,
                                        gradientEnd = 0xFFE8C5A8
                                    )
                                },
                                onToggleFavorite = { book ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        database.bookDao().updateFavorite(book.id, !book.isFavorite)
                                    }
                                },
                                onDeleteBook = { book -> deleteBook(book) },
                                onMiniPlayerClick = { currentScreen = Screen.Reader },
                                onTogglePlayPause = { audioManager.togglePlayPause() }
                            )
                        }

                        is Screen.Reader -> {
                            ReaderPlayerScreen(
                                playbackState = playbackState,
                                onBackClick = { currentScreen = Screen.Library },
                                onTogglePlayPause = { audioManager.togglePlayPause() },
                                onSeekRelative = { seconds -> audioManager.seekRelative(seconds) },
                                onSelectChunk = { index -> audioManager.playChunk(index) },
                                onSelectChapter = { chapterIndex -> audioManager.playChapter(chapterIndex) },
                                onPersistProgress = { audioManager.persistReadingPosition() },
                                onSpeedChange = { speed -> audioManager.setSpeed(speed) },
                                onVoiceChange = { voice -> audioManager.setVoice(voice) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun importDocument(uri: Uri) {
        lifecycleScope.launch {
            try {
                val mimeType = contentResolver.getType(uri) ?: ""
                val fileName = uri.lastPathSegment ?: "Documento"

                val extracted = withContext(Dispatchers.IO) {
                    if (mimeType.contains("epub", ignoreCase = true) || fileName.endsWith(".epub", ignoreCase = true)) {
                        epubExtractor.extractTextFromUri(uri)
                    } else {
                        pdfExtractor.extractTextFromUri(uri)
                    }
                }

                val format = if (mimeType.contains("epub", ignoreCase = true) || fileName.endsWith(".epub", ignoreCase = true)) {
                    BookFormat.EPUB
                } else {
                    BookFormat.PDF
                }

                saveBook(
                    title = extracted.title.ifBlank { fileName },
                    author = extracted.author.ifBlank { "Luna Reader" },
                    format = format,
                    content = extracted.fullText,
                    totalChapters = extracted.chapters.size.coerceAtLeast(1),
                    gradientStart = if (format == BookFormat.PDF) 0xFFE0F2FE else 0xFFFDF5E6,
                    gradientEnd = if (format == BookFormat.PDF) 0xFFBAE6FD else 0xFFFDE68A
                )

                Toast.makeText(this@MainActivity, "Documento importado com sucesso!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Erro ao importar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveBook(
        title: String,
        author: String,
        format: BookFormat,
        content: String,
        totalChapters: Int = 1,
        gradientStart: Long,
        gradientEnd: Long
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            database.bookDao().insertBook(
                BookEntity(
                    title = title,
                    author = author,
                    format = format,
                    content = content,
                    totalChapters = totalChapters,
                    coverGradientStart = gradientStart,
                    coverGradientEnd = gradientEnd
                )
            )
        }
    }

    private fun deleteBook(book: BookEntity) {
        if (audioManager.uiState.value.bookId == book.id) {
            audioManager.stop()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            database.bookDao().deleteBook(book)
            try {
                val bookFileDir = java.io.File(filesDir, "books/${book.id}")
                if (bookFileDir.exists()) {
                    bookFileDir.deleteRecursively()
                }
            } catch (e: Exception) {
                android.util.Log.w("LunaApp", "Falha ao excluir arquivos do livro", e)
            }
        }
        Toast.makeText(this, "\"${book.title}\" removido da biblioteca", Toast.LENGTH_SHORT).show()
    }

    private fun seedSampleBookIfEmpty() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (database.bookDao().getBookCount() == 0) {
                val sampleText = """
                    Capítulo 1: O Essencialista
                    A sabedoria da vida consiste na eliminação das coisas não essenciais. Quando tentamos abraçar tudo e agradar a todos, perdemos o domínio sobre nosso próprio tempo e propósito. O caminho do essencialista não é fazer mais coisas em menos tempo, mas fazer apenas as coisas certas com qualidade e serenidade.
                    
                    Capítulo 2: A Capacidade de Escolha
                    A capacidade de escolher não pode ser retirada ou entregue a outra pessoa; ela só pode ser esquecida. Quando nos esquecemos da nossa capacidade de escolher, aprendemos a ser desamparados. Ao longo do tempo, passamos a acreditar que somos vítimas de exigências alheias, acumulando compromissos automáticos sem questionar seu verdadeiro valor.
                    
                    Capítulo 3: O Poder do Foco
                    O essencialista investe o seu tempo e a sua energia unicamente nas atividades mais significativas, gerando a maior contribuição possível naquilo que realmente importa. Viver de forma deliberada é ter a coragem de dizer não com elegância e clareza para poder dizer sim àquilo que tem significado duradouro.
                """.trimIndent()

                database.bookDao().insertBook(
                    BookEntity(
                        title = "O Essencialismo",
                        author = "Greg McKeown",
                        format = BookFormat.EPUB,
                        content = sampleText,
                        totalChapters = 3,
                        coverGradientStart = 0xFFFDF5E6,
                        coverGradientEnd = 0xFFE8C5A8,
                        isFavorite = true
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        // Persiste a posição de leitura sem destruir a reprodução em segundo plano
        audioManager.persistReadingPosition()
        super.onDestroy()
    }
}

sealed class Screen {
    data object Library : Screen()
    data object Reader : Screen()
}
