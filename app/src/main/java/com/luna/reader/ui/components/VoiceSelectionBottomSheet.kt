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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import com.luna.reader.tts.AvailableVoice
import com.luna.reader.tts.PiperVoiceManager
import com.luna.reader.ui.theme.*
import kotlinx.coroutines.launch

/**
 * ModalBottomSheet elegante para seleção e download sob demanda
 * de narradores neurais offline em Português Brasileiro (pt-BR).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSelectionBottomSheet(
    currentVoice: AvailableVoice,
    onSelectVoice: (AvailableVoice) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Estado de download por voz (voiceId -> progresso 0f..1f ou null se ocioso)
    var downloadingVoiceId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // Verifica disponibilidade local das vozes
    var localReadinessMap by remember {
        mutableStateOf(
            AvailableVoice.entries.associateWith { voice ->
                PiperVoiceManager.isVoiceReady(context, voice)
            }
        )
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
            // Cabeçalho da Folha
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LunaPrimaryFixed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = LunaPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "Narradores Offline",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LunaPrimary
                    )
                    Text(
                        text = "Síntese neural local rodando no processador (pt-BR)",
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lista de Vozes
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(AvailableVoice.entries, key = { it.id }) { voice ->
                    val isSelected = voice == currentVoice
                    val isInstalled = localReadinessMap[voice] == true
                    val isDownloading = downloadingVoiceId == voice.id

                    VoiceItemCard(
                        voice = voice,
                        isSelected = isSelected,
                        isInstalled = isInstalled,
                        isDownloading = isDownloading,
                        downloadProgress = if (isDownloading) downloadProgress else 0f,
                        onClick = {
                            if (isInstalled) {
                                onSelectVoice(voice)
                                onDismissRequest()
                            } else if (!isDownloading) {
                                // Inicia download sob demanda
                                downloadingVoiceId = voice.id
                                downloadProgress = 0.05f
                                scope.launch {
                                    val success = PiperVoiceManager.downloadVoice(context, voice) { progress ->
                                        downloadProgress = progress
                                    }
                                    downloadingVoiceId = null
                                    if (success) {
                                        localReadinessMap = localReadinessMap + (voice to true)
                                        onSelectVoice(voice)
                                        onDismissRequest()
                                    }
                                }
                            }
                        },
                        onDownloadClick = {
                            if (!isDownloading && !isInstalled) {
                                downloadingVoiceId = voice.id
                                downloadProgress = 0.05f
                                scope.launch {
                                    val success = PiperVoiceManager.downloadVoice(context, voice) { progress ->
                                        downloadProgress = progress
                                    }
                                    downloadingVoiceId = null
                                    if (success) {
                                        localReadinessMap = localReadinessMap + (voice to true)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceItemCard(
    voice: AvailableVoice,
    isSelected: Boolean,
    isInstalled: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val containerColor = if (isSelected) {
        LunaHighlightParagraph
    } else {
        LunaSurfaceContainerLow
    }

    val borderColor = if (isSelected) {
        LunaPrimary
    } else {
        LunaOutlineVariant.copy(alpha = 0.4f)
    }

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
            .clickable(onClick = onClick)
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
                // Ícone Indicador de Status / Seleção
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) LunaPrimary else LunaSurfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selecionado",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = LunaOnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Detalhes da Voz
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = voice.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) LunaPrimary else LunaOnSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Badge Offline
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) LunaPrimaryFixed else LunaSurfaceContainerHigh
                        ) {
                            Text(
                                text = "100% Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = LunaPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp
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

                // Estado de Disponibilidade / Ação
                if (isDownloading) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = LunaPrimary
                    )
                } else if (isInstalled) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = "Instalado no aparelho",
                        tint = LunaMintAccent,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    OutlinedButton(
                        onClick = onDownloadClick,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LunaPrimary),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Baixar", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Barra de Progresso quando estiver descarregando
            AnimatedVisibility(visible = isDownloading) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = LunaPrimary,
                        trackColor = LunaSurfaceContainerHigh
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Baixando modelo neural (${(downloadProgress * 100).toInt()}%)...",
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaPrimary
                    )
                }
            }
        }
    }
}
