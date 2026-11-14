package com.sleeping.app.audio

import android.content.Context
import androidx.annotation.RawRes
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * 앱에 포함된 raw 음원을 끊김 없이 반복 재생한다 (Media3 ExoPlayer).
 * - 화이트 노이즈: USAGE_MEDIA, 오디오 포커스 처리
 * - 기상 알람: USAGE_ALARM
 */
@UnstableApi
class LoopPlayer(
    context: Context,
    @RawRes private val rawRes: Int,
    usage: Int,
    private val volume: Float,
) {
    private val appContext = context.applicationContext
    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(if (usage == C.USAGE_ALARM) C.AUDIO_CONTENT_TYPE_SONIFICATION else C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            // Media3는 USAGE_MEDIA/GAME만 오디오 포커스 자동 처리를 허용한다 (알람은 직접 처리하지 않음)
            /* handleAudioFocus = */ usage == C.USAGE_MEDIA,
        )
        .build()

    fun start() {
        player.setMediaItem(MediaItem.fromUri("android.resource://${appContext.packageName}/$rawRes"))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = volume
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
    }

    fun release() {
        player.release()
    }
}
