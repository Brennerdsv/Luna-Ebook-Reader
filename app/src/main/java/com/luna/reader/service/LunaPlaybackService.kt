package com.luna.reader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.luna.reader.MainActivity
import com.luna.reader.R

/**
 * Serviço de execução contínua em segundo plano e ForegroundService baseado em AndroidX Media3.
 *
 * Responsável por:
 * 1. Garantir reprodução ininterrupta de áudio com a tela desligada ou bloqueada (WakeLock + C.WAKE_MODE_LOCAL).
 * 2. Gerenciar a notificação multimídia nativa persistente com controles (Play/Pause, -10s, +10s) e scrubbing.
 * 3. Tratar foco de áudio e desconexão de fones de ouvido (Audio Becoming Noisy).
 */
@OptIn(UnstableApi::class)
class LunaPlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "LunaPlaybackService"
        const val NOTIFICATION_CHANNEL_ID = "luna_audio_playback_channel"
        const val NOTIFICATION_ID = 1001

        // Ações personalizadas de mídia
        const val ACTION_REWIND_10 = "com.luna.reader.ACTION_REWIND_10"
        const val ACTION_FORWARD_10 = "com.luna.reader.ACTION_FORWARD_10"
    }

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Inicializando LunaPlaybackService")

        try {
            createNotificationChannel()
            acquireWakeLock()
            initializePlayerAndSession()
            setupNotificationProvider()
        } catch (t: Throwable) {
            Log.e(TAG, "Erro crítico ao inicializar LunaPlaybackService: ${t.message}", t)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Reprodução de Áudio Luna",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reprodução de leitura neural da Luna"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LunaApp:AudioPlaybackWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // Limite de 12 horas por segurança
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível adquirir WakeLock parcial", e)
        }
    }

    private fun initializePlayerAndSession() {
        // Configura atributos de áudio para narração/leitura e tratamento automático de foco
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true) // Pausa automaticamente se o fone desconectar
            .setWakeMode(C.WAKE_MODE_LOCAL) // Impede que a CPU durma durante a reprodução
            .setSeekBackIncrementMs(10_000) // Salto de 10s para trás
            .setSeekForwardIncrementMs(10_000) // Salto de 10s para frente
            .build()

        // Intent para reabrir o app ao tocar na notificação
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Botões de comando customizados para Seek -10s e +10s
        val rewind10Button = CommandButton.Builder()
            .setDisplayName("Retroceder 10s")
            .setIconResId(android.R.drawable.ic_media_rew)
            .setSessionCommand(SessionCommand(ACTION_REWIND_10, Bundle.EMPTY))
            .build()

        val forward10Button = CommandButton.Builder()
            .setDisplayName("Avançar 10s")
            .setIconResId(android.R.drawable.ic_media_ff)
            .setSessionCommand(SessionCommand(ACTION_FORWARD_10, Bundle.EMPTY))
            .build()

        // Callback da sessão de mídia
        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand(ACTION_REWIND_10, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_FORWARD_10, Bundle.EMPTY))
                    .build()

                return MediaSession.ConnectionResult.accept(
                    availableSessionCommands,
                    connectionResult.availablePlayerCommands
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_REWIND_10 -> {
                        exoPlayer.seekBack()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_FORWARD_10 -> {
                        exoPlayer.seekForward()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(sessionCallback)
            .setCustomLayout(ImmutableList.of(rewind10Button, forward10Button))
            .build()
    }

    private fun setupNotificationProvider() {
        // Provedor padrão de notificação do Media3 que se integra ao MediaStyle do sistema
        val notificationProvider = object : DefaultMediaNotificationProvider(
            this,
            { NOTIFICATION_ID },
            NOTIFICATION_CHANNEL_ID,
            R.string.audio_notification_channel_name
        ) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showWhenCompact: Boolean
            ): ImmutableList<CommandButton> {
                val rewindButton = CommandButton.Builder()
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .setIconResId(android.R.drawable.ic_media_rew)
                    .setDisplayName("Voltar 10s")
                    .build()

                val forwardButton = CommandButton.Builder()
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .setIconResId(android.R.drawable.ic_media_ff)
                    .setDisplayName("Avançar 10s")
                    .build()

                val defaultButtons = super.getMediaButtons(session, playerCommands, customLayout, showWhenCompact)
                val playPauseButton = defaultButtons.firstOrNull() ?: CommandButton.Builder()
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setIconResId(android.R.drawable.ic_media_play)
                    .setDisplayName("Play/Pause")
                    .build()

                return ImmutableList.of(
                    rewindButton,
                    playPauseButton,
                    forwardButton
                )
            }
        }

        setMediaNotificationProvider(notificationProvider)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: LunaPlaybackService ativado como Started Service")
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Finalizando LunaPlaybackService")
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar WakeLock", e)
        }

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
