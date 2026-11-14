package com.sleeping.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 밤에 차 안에서 보기 편하도록 어두운 남색 + 따뜻한 주황 포인트 */
object SleepColors {
    val Night = Color(0xFF0B1020)
    val NightRaised = Color(0xFF151C33)
    val Ink = Color(0xFFE7EAF3)
    val InkMuted = Color(0xFF9AA3BC)
    val Warm = Color(0xFFFFB26B)
    val Ok = Color(0xFF7FD8A6)
    val Warn = Color(0xFFFF8A80)
}

@Composable
fun SleepingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SleepColors.Warm,
            onPrimary = SleepColors.Night,
            background = SleepColors.Night,
            onBackground = SleepColors.Ink,
            surface = SleepColors.NightRaised,
            onSurface = SleepColors.Ink,
            error = SleepColors.Warn,
        ),
        content = content,
    )
}
