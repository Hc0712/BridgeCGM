package com.north7.bridgecgm.feature.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace

object AlarmSoundPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun playByName(context: Context, rawName: String, minDurationSec: Int) {
        val resId = context.resources.getIdentifier(rawName, "raw", context.packageName)
        if (resId == 0) {
            DebugTrace.e(DebugCategory.ALARM, "ALARM-PLAY", "raw resource not found for name=$rawName")
            return
        }

        stop()
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        DebugTrace.t(DebugCategory.ALARM, "ALARM-PLAY", "raw=$rawName durationSec=$minDurationSec alarmVol=${am.getStreamVolume(AudioManager.STREAM_ALARM)} musicVol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)} mode=${am.ringerMode}")
        tryRequestAudioFocus(am)

        val player = MediaPlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                player.setAudioStreamType(AudioManager.STREAM_ALARM)
            }

            val afd = appContext.resources.openRawResourceFd(resId) ?: run {
                DebugTrace.e(DebugCategory.ALARM, "ALARM-PLAY", "openRawResourceFd returned null for $rawName")
                try { player.release() } catch (_: Exception) {}
                abandonAudioFocus()
                return
            }
            afd.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            player.prepare()
        } catch (e: Exception) {
            DebugTrace.e(DebugCategory.ALARM, "ALARM-PLAY", "prepare failed for $rawName", e)
            try { player.release() } catch (_: Exception) {}
            abandonAudioFocus()
            return
        }

        mediaPlayer = player
        val minDurationMs = minDurationSec.coerceAtLeast(1) * 1000L
        val startedAtMs = System.currentTimeMillis()
        val clipDurationMs = player.duration.takeIf { it > 0 }?.toLong() ?: minDurationMs

        player.setOnCompletionListener { mp ->
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            if (elapsedMs + 150L < minDurationMs) {
                try {
                    mp.seekTo(0)
                    mp.start()
                    DebugTrace.t(DebugCategory.ALARM, "ALARM-REPLAY", "raw=$rawName elapsedMs=$elapsedMs")
                } catch (e: Exception) {
                    DebugTrace.e(DebugCategory.ALARM, "ALARM-REPLAY", "replay failed for $rawName", e)
                    stop()
                }
            } else {
                stop()
            }
        }

        player.setOnErrorListener { _, what, extra ->
            DebugTrace.e(DebugCategory.ALARM, "ALARM-PLAY", "error what=$what extra=$extra")
            stop()
            true
        }

        try {
            player.start()
            DebugTrace.t(DebugCategory.ALARM, "ALARM-START", "raw=$rawName playerDurationMs=$clipDurationMs")
            if (clipDurationMs >= minDurationMs) {
                mainHandler.postDelayed({ stop() }, minDurationMs)
            }
        } catch (e: Exception) {
            DebugTrace.e(DebugCategory.ALARM, "ALARM-START", "start failed for $rawName", e)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        val player = mediaPlayer
        mediaPlayer = null
        try {
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            if (player?.isPlaying == true) player.stop()
        } catch (_: Exception) {
        } finally {
            try { player?.release() } catch (_: Exception) {}
            abandonAudioFocus()
        }
        DebugTrace.t(DebugCategory.ALARM, "ALARM-STOP", "player stopped")
    }

    private fun tryRequestAudioFocus(am: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            DebugTrace.w(DebugCategory.ALARM, "ALARM-FOCUS", "tryRequestAudioFocus failed", e)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        } finally {
            audioFocusRequest = null
            audioManager = null
        }
    }
}
