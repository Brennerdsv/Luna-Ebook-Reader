package com.luna.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.reader.R
import com.luna.reader.data.local.BookEntity
import com.luna.reader.data.local.BookFormat
import com.luna.reader.playback.PlaybackUiState
import com.luna.reader.ui.theme.*

enum class LibraryFilterCategory(val label: String) {
    TODOS("Todos"),
    PDFS("PDFs"),
    EPUBS("EPUBs"),
    FAVORITOS("Favoritos")
}

@Composable
fun LibraryScreen(
    books: List<BookEntity>,
    playbackState: PlaybackUiState,
    onBookClick: (BookEntity) -> Unit,
    onImportUri: (Uri) -> Unit,
    onPasteText: (title: String, author: String, text: String) -> Unit,
    onToggleFavorite: (BookEntity) -> Unit,
    onDeleteBook: (BookEntity) -> Unit,
    onMiniPlayerClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(LibraryFilterCategory.TODOS) }
    var showFabMenu by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<BookEntity?>(null) }

    // Filtragem local
    val filteredBooks = remember(books, searchQuery, selectedCategory) {
        books.filter { book ->
            val matchesSearch = searchQuery.isBlank() ||
                    book.title.contains(searchQuery, ignoreCase = true) ||
                    book.author.contains(searchQuery, ignoreCase = true)

            val matchesCategory = when (selectedCategory) {
                LibraryFilterCategory.TODOS -> true
                LibraryFilterCategory.PDFS -> book.format == BookFormat.PDF
                LibraryFilterCategory.EPUBS -> book.format == BookFormat.EPUB
                LibraryFilterCategory.FAVORITOS -> book.isFavorite
            }

            matchesSearch && matchesCategory
        }
    }

    // Seletor de arquivo do sistema (PDF e EPUB)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onImportUri(it) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LunaSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header TopBar com logo cachorrinho Luna
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.luna_puppy),
                        contentDescription = "Luna Puppy Logo",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Column {
                        Text(
                            text = "Luna",
                            style = MaterialTheme.typography.headlineSmall,
                            color = LunaPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Biblioteca",
                            style = MaterialTheme.typography.labelSmall,
                            color = LunaOnSurfaceVariant
                        )
                    }
                }
            }

            // Sub-cabeçalho: Minha Biblioteca
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Minha Biblioteca",
                        style = MaterialTheme.typography.headlineLarge,
                        color = LunaPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(LunaSecondary)
                        )
                        Text(
                            text = "${filteredBooks.size} itens disponíveis",
                            style = MaterialTheme.typography.labelSmall,
                            color = LunaOnSurfaceVariant
                        )
                    }
                }
            }

            // Campo de busca fluido estilo M3
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Pesquisar livros, PDFs, EPUBs...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LunaOutline
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = LunaOnSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LunaSurfaceContainerLowest,
                    unfocusedContainerColor = LunaSurfaceContainerLow,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )

            // Chips / Filtros Estilo Pílula (Sem a categoria "Livros")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryFilterCategory.entries.forEach { category ->
                    val isSelected = category == selectedCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LunaPrimaryContainer,
                            selectedLabelColor = Color.White,
                            containerColor = LunaSurfaceContainerLowest,
                            labelColor = LunaOnSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color.Transparent
                        )
                    )
                }
            }

            // Grade de livros (2 colunas)
            if (filteredBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            tint = LunaOutlineVariant,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Nenhum documento encontrado",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LunaOnSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = if (playbackState.bookId != -1L) 180.dp else 120.dp)
                ) {
                    items(filteredBooks, key = { it.id }) { book ->
                        BookGridCard(
                            book = book,
                            onClick = { onBookClick(book) },
                            onToggleFavorite = { onToggleFavorite(book) },
                            onDeleteClick = { bookToDelete = book }
                        )
                    }
                }
            }
        }

        // MiniPlayer flutuante na base da tela
        AnimatedVisibility(
            visible = playbackState.bookId != -1L,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            MiniPlayerBar(
                playbackState = playbackState,
                onClick = onMiniPlayerClick,
                onTogglePlayPause = onTogglePlayPause
            )
        }

        // FAB expansível para adicionar documento ou colar texto, posicionado ACIMA do MiniPlayer
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = if (playbackState.bookId != -1L) 96.dp else 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedVisibility(visible = showFabMenu) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                showFabMenu = false
                                showPasteDialog = true
                            },
                            containerColor = LunaSurfaceContainerLowest,
                            contentColor = LunaPrimary,
                            shape = RoundedCornerShape(24.dp),
                            icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                            text = { Text("Colar Texto", style = MaterialTheme.typography.labelMedium) }
                        )
                        ExtendedFloatingActionButton(
                            onClick = {
                                showFabMenu = false
                                documentPickerLauncher.launch(
                                    arrayOf("application/pdf", "application/epub+zip", "text/plain")
                                )
                            },
                            containerColor = LunaPrimaryContainer,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(24.dp),
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("Importar PDF / EPUB", style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = LunaPrimary,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Adicionar à biblioteca"
                    )
                }
            }
        }
    }

    // Diálogo de confirmação de exclusão de documento
    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Excluir documento?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = LunaPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Tem certeza de que deseja remover este item da sua biblioteca? O progresso será perdido.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LunaOnSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = book
                        bookToDelete = null
                        onDeleteBook(target)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Excluir", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { bookToDelete = null },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancelar", color = LunaPrimary)
                }
            },
            containerColor = LunaSurfaceContainerLowest,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Modal de colagem de texto
    if (showPasteDialog) {
        PasteTextDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { title, author, text ->
                showPasteDialog = false
                onPasteText(title, author, text)
            }
        )
    }
}

@Composable
fun BookGridCard(
    book: BookEntity,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LunaSurfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            // Capa gradiente estilo editorial
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(book.coverGradientStart),
                                Color(book.coverGradientEnd)
                            )
                        )
                    )
            ) {
                // Barra superior: Formato à esquerda e Ações à direita, totalmente contidas no card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Badge de Formato
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.92f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = book.format.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = LunaPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Ações: Favoritar e Menu de Opções com botões compactos de 32dp
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Botão de Favoritar compacto
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.22f))
                                .clickable(onClick = onToggleFavorite),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (book.isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                                contentDescription = "Favorito",
                                tint = if (book.isFavorite) Color(0xFFFFD700) else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Botão de Opções (...) compacto
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.22f))
                                    .clickable { showMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Mais opções",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(LunaSurfaceContainerLowest)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (book.isFavorite) "Remover dos Favoritos" else "Adicionar aos Favoritos",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (book.isFavorite) Icons.Outlined.StarOutline else Icons.Default.Star,
                                            contentDescription = null,
                                            tint = LunaTertiary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onToggleFavorite()
                                    }
                                )

                                HorizontalDivider(color = LunaOutlineVariant.copy(alpha = 0.3f))

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Excluir",
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Excluir",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onDeleteClick()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Informações do livro
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LunaPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progresso de leitura
                LinearProgressIndicator(
                    progress = { book.currentProgressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = LunaSecondary,
                    trackColor = LunaSurfaceContainerHigh
                )
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = LunaPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(LunaSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = playbackState.bookTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playbackState.bookAuthor.ifBlank { "Leitura neural em reprodução" },
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaOnPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            ) {
                if (playbackState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LunaPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = LunaPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun PasteTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, text: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Colar Texto ou Artigo",
                style = MaterialTheme.typography.headlineSmall,
                color = LunaPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título do Documento") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Autor (Opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Conteúdo do Texto") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isNotBlank()) {
                        val safeTitle = title.ifBlank { "Texto Colado" }
                        val safeAuthor = author.ifBlank { "Você" }
                        onConfirm(safeTitle, safeAuthor, content)
                    }
                },
                enabled = content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = LunaPrimary)
            ) {
                Text("Adicionar à Biblioteca")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = LunaOnSurfaceVariant)
            }
        },
        containerColor = LunaSurfaceContainerLowest,
        shape = RoundedCornerShape(24.dp)
    )
}
