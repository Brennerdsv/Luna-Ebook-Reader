package com.luna.reader.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.reader.tts.AudioSynthesisRouter
import com.luna.reader.tts.AvailableVoice
import com.luna.reader.tts.PiperVoiceManager
import com.luna.reader.tts.VoiceEngine
import com.luna.reader.ui.theme.*
import kotlinx.coroutines.launch

enum class VoiceFilterTab(val title: String) {
    ALL("Todas"),
    OFFLINE("Uso Offline"),
    ONLINE("Microsoft Online")
}

/**
 * ModalBottomSheet com arquitetura híbrida de áudio:
 * 1. Seção "Uso Offline": Gerenciamento da voz Faber (download sob demanda, ativação, exclusão de modelo para liberar ~60 MB).
 * 2. Seção "Vozes Microsoft": Streaming sob demanda via WebSockets das vozes Francisca, Antônio, Brenda, Donato e Thalita com validação de conectividade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceManagerBottomSheet(
    currentVoice: AvailableVoice,
    onSelectVoice: (AvailableVoice) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioRouter = remember { AudioSynthesisRouter.getInstance(context) }

    var selectedTab by remember { mutableStateOf(VoiceFilterTab.ALL) }
    var isNetworkAvailable by remember { mutableStateOf(audioRouter.isNetworkAvailable()) }

    // Estado da voz Faber (Offline)
    var isFaberInstalled by remember { mutableStateOf(PiperVoiceManager.isVoiceReady(context, AvailableVoice.FABER)) }
    var isFaberDownloading by remember { mutableStateOf(false) }
    var faberDownloadProgress by remember { mutableFloatStateOf(0f) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Separação das vozes
    val offlineVoice = AvailableVoice.FABER
    val onlineVoices = remember {
        AvailableVoice.entries.filter { it.engine is VoiceEngine.OnlineEdge }
    }

    // Atualiza status de rede ao abrir
    LaunchedEffect(Unit) {
        isNetworkAvailable = audioRouter.isNetworkAvailable()
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = LunaSurfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Cabeçalho Principal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(LunaPrimaryFixed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = LunaPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Narradores Luna (Híbrido)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LunaPrimary
                    )
                    Text(
                        text = "Motor Offline (Sherpa/Piper) e Online (Microsoft Edge)",
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant
                    )
                }

                // Indicador de status de conexão
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isNetworkAvailable) LunaMint else LunaHighlightParagraph
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isNetworkAvailable) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = if (isNetworkAvailable) LunaMintAccent else LunaTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isNetworkAvailable) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isNetworkAvailable) LunaPrimary else LunaTertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Seletor de Abas / Filtros
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                VoiceFilterTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = VoiceFilterTab.entries.size),
                        onClick = { selectedTab = tab },
                        selected = selectedTab == tab,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = LunaPrimary,
                            activeContentColor = Color.White,
                            inactiveContainerColor = LunaSurfaceContainerLow,
                            inactiveContentColor = LunaPrimary
                        )
                    ) {
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Conteúdo Rolável
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // ==========================================
                // SEÇÃO 1: USO OFFLINE (FABER)
                // ==========================================
                if (selectedTab == VoiceFilterTab.ALL || selectedTab == VoiceFilterTab.OFFLINE) {
                    item {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OfflinePin,
                                    contentDescription = null,
                                    tint = LunaPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Uso Offline (Sem Internet)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LunaPrimary
                                )
                            }
                            Text(
                                text = "Processado localmente no aparelho via Sherpa-ONNX. Baixe uma vez e use para sempre.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LunaOnSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OfflineVoiceCard(
                                voice = offlineVoice,
                                isSelected = currentVoice == offlineVoice,
                                isInstalled = isFaberInstalled,
                                isDownloading = isFaberDownloading,
                                downloadProgress = faberDownloadProgress,
                                onSelect = {
                                    if (isFaberInstalled) {
                                        onSelectVoice(offlineVoice)
                                        onDismissRequest()
                                    }
                                },
                                onStartDownload = {
                                    if (!isFaberDownloading && !isFaberInstalled) {
                                        isFaberDownloading = true
                                        faberDownloadProgress = 0.05f
                                        scope.launch {
                                            val success = PiperVoiceManager.downloadVoice(context, offlineVoice) { progress ->
                                                faberDownloadProgress = progress
                                            }
                                            isFaberDownloading = false
                                            if (success) {
                                                isFaberInstalled = true
                                                onSelectVoice(offlineVoice)
                                                onDismissRequest()
                                            }
                                        }
                                    }
                                },
                                onDelete = {
                                    showDeleteConfirmation = true
                                }
                            )
                        }
                    }
                }

                // ==========================================
                // SEÇÃO 2: VOZES MICROSOFT (ONLINE)
                // ==========================================
                if (selectedTab == VoiceFilterTab.ALL || selectedTab == VoiceFilterTab.ONLINE) {
                    item {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = LunaSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Vozes Microsoft (Requer Internet)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LunaPrimary
                                )
                            }
                            Text(
                                text = "Síntese neural de alta fidelidade em nuvem (Edge-TTS). Não ocupa espaço no aparelho.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LunaOnSurfaceVariant
                            )

                            // Alerta se offline
                            if (!isNetworkAvailable) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = LunaHighlightParagraph),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WifiOff,
                                            contentDescription = null,
                                            tint = LunaTertiary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Aparelho desconectado",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = LunaTertiary
                                            )
                                            Text(
                                                text = "Conecte-se à internet para usar vozes Microsoft ou alterne para a voz offline Faber.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = LunaOnSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(onlineVoices, key = { it.id }) { voice ->
                        val isSelected = currentVoice == voice
                        val onlineEngine = voice.engine as VoiceEngine.OnlineEdge

                        OnlineVoiceCard(
                            voice = voice,
                            gender = onlineEngine.gender,
                            isSelected = isSelected,
                            isNetworkAvailable = isNetworkAvailable,
                            onSelect = {
                                onSelectVoice(voice)
                                onDismissRequest()
                            }
                        )
                    }
                }
            }
        }
    }

    // Diálogo de confirmação para exclusão de arquivos da voz Faber
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(text = "Excluir voz Faber?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = "Isso removerá os arquivos do modelo (~60 MB) do seu aparelho. Você precisará baixá-los novamente para usar a leitura sem internet.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PiperVoiceManager.deleteVoice(context, AvailableVoice.FABER)
                        isFaberInstalled = false
                        showDeleteConfirmation = false
                    }
                ) {
                    Text(text = "Excluir e liberar espaço", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(text = "Cancelar")
                }
            }
        )
    }
}

/**
 * Card dedicado para a voz Offline Faber com status de download e gerenciamento de armazenamento.
 */
@Composable
private fun OfflineVoiceCard(
    voice: AvailableVoice,
    isSelected: Boolean,
    isInstalled: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onSelect: () -> Unit,
    onStartDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor = if (isSelected) LunaHighlightParagraph else LunaSurfaceContainerLow
    val borderColor = if (isSelected) LunaPrimary else LunaOutlineVariant.copy(alpha = 0.4f)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = isInstalled, onClick = onSelect)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicador de seleção
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) LunaPrimary else LunaSurfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selecionado",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = LunaOnSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Detalhes da voz
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = voice.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) LunaPrimary else LunaOnSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isInstalled) LunaMint else LunaSurfaceContainerHighest
                        ) {
                            Text(
                                text = if (isInstalled) "Baixado (~60 MB)" else "Requer download",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isInstalled) LunaPrimary else LunaOnSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = voice.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Ações da voz offline
                if (isDownloading) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = LunaPrimary
                    )
                } else if (isInstalled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isSelected) {
                            FilledTonalButton(
                                onClick = onSelect,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Ativar", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Excluir modelo",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onStartDownload,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LunaPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Baixar (~60 MB)", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Barra de Progresso quando estiver baixando
            AnimatedVisibility(visible = isDownloading) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = LunaPrimary,
                        trackColor = LunaSurfaceContainerHigh
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Baixando modelo neural Faber (${(downloadProgress * 100).toInt()}%)...",
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Card para as vozes online da Microsoft Edge-TTS com streaming sob demanda.
 */
@Composable
private fun OnlineVoiceCard(
    voice: AvailableVoice,
    gender: String,
    isSelected: Boolean,
    isNetworkAvailable: Boolean,
    onSelect: () -> Unit
) {
    val containerColor = if (isSelected) LunaHighlightParagraph else LunaSurfaceContainerLow
    val borderColor = if (isSelected) LunaSecondary else LunaOutlineVariant.copy(alpha = 0.4f)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Indicador de seleção
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) LunaSecondary else LunaSurfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selecionado",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = LunaOnSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Detalhes da voz
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) LunaSecondary else LunaOnSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LunaLavender
                    ) {
                        Text(
                            text = gender,
                            style = MaterialTheme.typography.labelSmall,
                            color = LunaSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = voice.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LunaOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LunaSecondaryFixed
                ) {
                    Text(
                        text = "Em uso",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = LunaSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onSelect,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LunaSecondary),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Selecionar", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
