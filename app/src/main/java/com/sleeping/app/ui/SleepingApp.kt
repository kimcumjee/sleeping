package com.sleeping.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleeping.app.SleepConfig
import com.sleeping.app.session.SleepPhase
import com.sleeping.app.session.WakeReason
import com.sleeping.app.vehicle.ParkingMonitor
import kotlinx.coroutines.delay

@Composable
fun SleepingApp(
    phase: SleepPhase,
    parking: ParkingMonitor.State,
    energyPercent: Int?,
    onStart: () -> Unit,
    onWakeNow: () -> Unit,
    onDismissAlarm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SleepColors.Night)
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            is SleepPhase.Idle -> HomeScreen(phase, parking, energyPercent, onStart)
            SleepPhase.Preparing -> BusyScreen("수면 환경을 준비하고 있어요", "시트를 눕히고 조명과 공조를 맞추는 중입니다")
            is SleepPhase.Sleeping -> SleepingScreen(phase, onWakeNow)
            is SleepPhase.Waking -> WakingScreen(phase, onDismissAlarm)
        }
    }
}

@Composable
private fun HomeScreen(
    phase: SleepPhase.Idle,
    parking: ParkingMonitor.State,
    energyPercent: Int?,
    onStart: () -> Unit,
) {
    val lowEnergy = energyPercent != null && energyPercent <= SleepConfig.LOW_ENERGY_PERCENT
    val canStart = parking.canSleep && !lowEnergy

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 720.dp),
    ) {
        Text("슬리핑", color = SleepColors.Ink, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "15분 동안 깊게 쉬고, 제때 깨워 드려요",
            color = SleepColors.InkMuted,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(36.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusChip(
                label = "기어 P",
                state = when (parking.inPark) {
                    true -> ChipState.Ok
                    false -> ChipState.Bad
                    null -> ChipState.Unknown
                },
            )
            StatusChip(
                label = energyPercent?.let { "잔량 $it%" } ?: "잔량",
                state = when {
                    energyPercent == null -> ChipState.Unknown
                    lowEnergy -> ChipState.Bad
                    else -> ChipState.Ok
                },
            )
        }
        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onStart,
            enabled = canStart,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SleepColors.Warm, contentColor = SleepColors.Night),
            modifier = Modifier.height(88.dp).widthIn(min = 360.dp),
        ) {
            Text("15분 수면 시작", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                lowEnergy -> "배터리나 연료가 ${SleepConfig.LOW_ENERGY_PERCENT}% 이하라 시작할 수 없어요"
                parking.inPark == false -> "기어를 P에 두면 시작할 수 있어요"
                parking.inPark == null -> "차량 상태를 확인하고 있어요"
                else -> "시트를 눕히고, 조명을 낮추고, 화이트 노이즈를 틀어 드려요"
            },
            color = SleepColors.InkMuted,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )

        phase.message?.let {
            Spacer(Modifier.height(28.dp))
            Text(it, color = SleepColors.Ink, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        SkippedNote(phase.skipped)
    }
}

@Composable
private fun SleepingScreen(phase: SleepPhase.Sleeping, onWakeNow: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase.endsAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remainingSec = ((phase.endsAtMillis - now) / 1000).coerceAtLeast(0)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("편히 쉬세요", color = SleepColors.InkMuted, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "%02d:%02d".format(remainingSec / 60, remainingSec % 60),
            color = SleepColors.Ink.copy(alpha = 0.85f),
            fontSize = 120.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(8.dp))
        Text("시간이 되면 알람과 음성으로 깨워 드려요", color = SleepColors.InkMuted, fontSize = 16.sp)
        Spacer(Modifier.height(40.dp))
        OutlinedButton(onClick = onWakeNow, shape = RoundedCornerShape(16.dp), modifier = Modifier.height(64.dp)) {
            Text("지금 일어나기", fontSize = 20.sp, color = SleepColors.Ink)
        }
        SkippedNote(phase.skipped)
    }
}

@Composable
private fun WakingScreen(phase: SleepPhase.Waking, onDismissAlarm: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (phase.reason) {
                WakeReason.TIME_UP, WakeReason.USER -> "일어날 시간이에요"
                WakeReason.LOW_ENERGY -> "잔량이 부족해 깨워 드렸어요"
                WakeReason.GEAR_CHANGED -> "기어가 바뀌어 수면 모드를 끝냈어요"
            },
            color = SleepColors.Ink,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "시트·공조·조명을 원래대로 돌리고, 창문을 잠시 열어 환기하고 있어요",
            color = SleepColors.InkMuted,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        if (phase.alarmOn) {
            Button(
                onClick = onDismissAlarm,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SleepColors.Warm, contentColor = SleepColors.Night),
                modifier = Modifier.height(88.dp).widthIn(min = 320.dp),
            ) {
                Text("알람 끄기", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            CircularProgressIndicator(color = SleepColors.Warm)
        }
    }
}

@Composable
private fun BusyScreen(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = SleepColors.Warm, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(24.dp))
        Text(title, color = SleepColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = SleepColors.InkMuted, fontSize = 16.sp)
    }
}

/** 1차 범위에서는 차종별 대응 대신, 적용되지 않은 기능을 작게 알려 준다. */
@Composable
private fun SkippedNote(skipped: List<String>) {
    if (skipped.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    Text(
        "이 차량에서 적용되지 않은 기능: ${skipped.joinToString(", ")}",
        color = SleepColors.InkMuted.copy(alpha = 0.7f),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

private enum class ChipState { Ok, Bad, Unknown }

@Composable
private fun StatusChip(label: String, state: ChipState) {
    val (dot, text) = when (state) {
        ChipState.Ok -> SleepColors.Ok to "확인"
        ChipState.Bad -> SleepColors.Warn to "아님"
        ChipState.Unknown -> SleepColors.InkMuted to "확인 불가"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(SleepColors.NightRaised, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(10.dp).background(dot, RoundedCornerShape(50)))
        Spacer(Modifier.size(10.dp))
        Text("$label · $text", color = SleepColors.Ink, fontSize = 16.sp)
    }
}
