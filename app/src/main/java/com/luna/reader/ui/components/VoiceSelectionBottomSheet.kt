package com.luna.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.luna.reader.tts.AvailableVoice

/**
 * Delegador de compatibilidade para o novo VoiceManagerBottomSheet híbrido.
 */
@Composable
fun VoiceSelectionBottomSheet(
    currentVoice: AvailableVoice,
    onSelectVoice: (AvailableVoice) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    VoiceManagerBottomSheet(
        currentVoice = currentVoice,
        onSelectVoice = onSelectVoice,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    )
}
