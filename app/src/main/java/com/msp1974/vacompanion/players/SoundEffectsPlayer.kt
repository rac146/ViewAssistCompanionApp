package com.msp1974.vacompanion.players

import android.content.Context
import android.os.Handler
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.USAGE_NOTIFICATION
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

class SoundEffectsPlayer(val context: Context) {
    private val players = mutableMapOf<Int, ExoPlayer>()
    private val _finished = MutableStateFlow(false)
     val finished: StateFlow<Boolean> = _finished

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(USAGE_NOTIFICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    suspend fun preload(resId: Int) {
        if (players.containsKey(resId)) return

        try {
            withContext(Dispatchers.Main) {
                val player = createPlayer(resId)
                player.prepare()
                players[resId] = player
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun createPlayer(resId: Int): ExoPlayer {
        try {
            val player = ExoPlayer.Builder(context).build()
            val mediaItem =
                MediaItem.fromUri("android.resource://${context.packageName}/$resId".toUri())
            player.setAudioAttributes(audioAttributes, false)
            player.setMediaItem(mediaItem)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player  .STATE_ENDED) {
                        _finished.value = true
                    }
                }
            })
            return player
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    suspend fun play(resId: Int) {
        withContext(Dispatchers.Main) {
            try {
                _finished.value = false
                // Ensure only one feedback sound plays at a time
                stopAllInternal()

                val player = players[resId]
                if (player != null) {
                    player.seekTo(0)
                    player.play()
                } else {
                    // Fallback for non-prepared sounds
                    val adhocPlayer = createPlayer(resId)
                    adhocPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                adhocPlayer.release()
                                _finished.value = true
                            }
                        }
                    })
                    adhocPlayer.prepare()
                    adhocPlayer.play()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    suspend fun stop() {
        stopAllInternal()
        release()
    }

    private suspend fun stopAllInternal() {
        withContext(Dispatchers.Main) {
            players.values.forEach {
                if (it.isPlaying) {
                    it.pause()
                    it.seekTo(0)
                }
            }
        }
    }

    suspend fun release() {
        withContext(Dispatchers.Main) {
            players.values.forEach { it.release() }
            players.clear()
        }
    }
}