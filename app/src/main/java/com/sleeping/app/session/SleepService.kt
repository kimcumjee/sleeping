package com.sleeping.app.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.sleeping.app.R
import com.sleeping.app.SleepConfig
import com.sleeping.app.audio.LoopPlayer
import com.sleeping.app.audio.WakeAnnouncer
import com.sleeping.app.ui.MainActivity
import com.sleeping.app.vehicle.CabinSnapshot
import com.sleeping.app.vehicle.ParkingMonitor
import com.sleeping.app.vehicle.VehicleController
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 수면 세션 전체 흐름을 담당하는 포그라운드 서비스.
 *
 * 시작 → (안전 확인 · 잔량 확인) → 상태 저장 → 수면 세팅 적용 + 화이트 노이즈
 *      → 15분 대기 (기어 이탈 · 잔량 15% 이하 · 사용자 요청 시 즉시 기상)
 *      → 기상: 알람 + TTS 안내, 한 번에 복원, 창문 1분 환기 → 종료
 */
@OptIn(UnstableApi::class)
class SleepService : LifecycleService() {

    private lateinit var vehicle: VehicleController
    private lateinit var parking: ParkingMonitor
    private lateinit var announcer: WakeAnnouncer
    private lateinit var noise: LoopPlayer
    private lateinit var alarm: LoopPlayer

    private var snapshot: CabinSnapshot? = null
    private var sessionJob: Job? = null
    private var watchJob: Job? = null
    private var alarmStopJob: Job? = null
    private var waking = false

    override fun onCreate() {
        super.onCreate()
        vehicle = VehicleController(this).also { it.connect() }
        parking = ParkingMonitor(this, vehicle, lifecycleScope).also { it.start() }
        announcer = WakeAnnouncer(this).also { it.initialize() }
        noise = LoopPlayer(this, R.raw.noise_brown, C.USAGE_MEDIA, SleepConfig.NOISE_VOLUME)
        alarm = LoopPlayer(this, R.raw.alarm_chime, C.USAGE_ALARM, 1.0f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (sessionJob == null) startSession()
            ACTION_WAKE_NOW -> lifecycleScope.launch { wake(WakeReason.USER) }
            ACTION_DISMISS_ALARM -> dismissAlarm()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        startInForeground(getString(R.string.notif_preparing))
        SleepSession.set(SleepPhase.Preparing)

        sessionJob = lifecycleScope.launch {
            // 1) 안전 확인: 기어 P
            val parkState = withTimeoutOrNull(PARK_CHECK_TIMEOUT_MS) {
                parking.state.filter { it.inPark != null }.first()
            }
            if (parkState?.canSleep != true) {
                finish(getString(R.string.msg_not_parked))
                return@launch
            }
            // 2) 잔량 확인: 이미 15% 이하라면 시작하지 않음
            val energy = vehicle.currentEnergyPercent()
            if (energy != null && energy <= SleepConfig.LOW_ENERGY_PERCENT) {
                finish(getString(R.string.msg_low_energy_start, energy))
                return@launch
            }

            // 3) 상태 저장 → 수면 세팅 적용 → 화이트 노이즈
            val saved = vehicle.snapshot().also { snapshot = it }
            val report = vehicle.applySleepSettings(saved)
            noise.start()

            val endsAt = System.currentTimeMillis() + SleepConfig.SLEEP_DURATION_MS
            SleepSession.set(SleepPhase.Sleeping(endsAt, report.skipped))
            startInForeground(getString(R.string.notif_sleeping))

            // 4) 수면 중 감시
            watchJob = launch {
                launch {
                    parking.state.drop(1).filter { it.inPark == false }.first()
                    wake(WakeReason.GEAR_CHANGED)
                }
                launch {
                    vehicle.energyUpdates().filter { it <= SleepConfig.LOW_ENERGY_PERCENT }.first()
                    wake(WakeReason.LOW_ENERGY)
                }
            }

            // 5) 15분 뒤 기상
            delay(SleepConfig.SLEEP_DURATION_MS)
            wake(WakeReason.TIME_UP)
        }
    }

    private suspend fun wake(reason: WakeReason) {
        if (waking) return
        val saved = snapshot
        if (saved == null) {
            finish(null)
            return
        }
        waking = true
        watchJob?.cancel()
        noise.stop()

        withContext(NonCancellable) {
            val skippedOnApply = (SleepSession.phase.value as? SleepPhase.Sleeping)?.skipped.orEmpty()
            val ringAlarm = reason != WakeReason.GEAR_CHANGED && reason != WakeReason.USER
            SleepSession.set(SleepPhase.Waking(reason, alarmOn = ringAlarm, skipped = skippedOnApply))
            startInForeground(getString(R.string.notif_waking))

            // 알람 + 음성 안내
            if (ringAlarm) {
                alarm.start()
                announcer.speak(
                    if (reason == WakeReason.LOW_ENERGY) SleepConfig.LOW_ENERGY_MESSAGE else SleepConfig.WAKE_MESSAGE,
                )
                alarmStopJob = lifecycleScope.launch {
                    delay(SleepConfig.ALARM_MAX_MS)
                    dismissAlarm()
                }
            }

            // 시트·공조·조명·차광을 수면 전 상태로 한 번에 복원
            val restoreReport = vehicle.restore(saved)
            val skipped = (skippedOnApply + restoreReport.skipped).distinct()

            // 기상 환기: 주행 시작(기어 이탈) 시에는 창문을 열지 않는다
            if (reason != WakeReason.GEAR_CHANGED) {
                val original = vehicle.openWindowForVentilation()
                if (original != null) {
                    delay(SleepConfig.WAKE_WINDOW_OPEN_MS)
                    if (!vehicle.closeWindow(original)) Log.w(TAG, "창문 닫기 실패")
                }
            }

            // 알람이 아직 울리고 있으면 사용자가 끌 때까지(최대 2분) 기다린다
            SleepSession.phase.map { (it as? SleepPhase.Waking)?.alarmOn == true }.filter { !it }.first()
            finish(messageFor(reason), skipped)
        }
    }

    private fun dismissAlarm() {
        alarmStopJob?.cancel()
        alarm.stop()
        (SleepSession.phase.value as? SleepPhase.Waking)?.let { SleepSession.set(it.copy(alarmOn = false)) }
    }

    private fun messageFor(reason: WakeReason): String = when (reason) {
        WakeReason.TIME_UP, WakeReason.USER -> getString(R.string.msg_done)
        WakeReason.LOW_ENERGY -> getString(R.string.msg_woke_low_energy)
        WakeReason.GEAR_CHANGED -> getString(R.string.msg_woke_gear)
    }

    private fun finish(message: String?, skipped: List<String> = emptyList()) {
        SleepSession.set(SleepPhase.Idle(message, skipped))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        noise.release()
        alarm.release()
        announcer.release()
        parking.stop()
        vehicle.release()
        if (SleepSession.phase.value !is SleepPhase.Idle) SleepSession.set(SleepPhase.Idle())
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // 알림
    // ---------------------------------------------------------------------

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val wakeNow = PendingIntent.getService(
            this, 1, intent(this, ACTION_WAKE_NOW), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sleeping)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_wake_now), wakeNow)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    companion object {
        private const val TAG = "Sleeping"
        private const val CHANNEL_ID = "sleep_session"
        private const val NOTIFICATION_ID = 1
        private const val PARK_CHECK_TIMEOUT_MS = 5_000L

        const val ACTION_START = "com.sleeping.app.START"
        const val ACTION_WAKE_NOW = "com.sleeping.app.WAKE_NOW"
        const val ACTION_DISMISS_ALARM = "com.sleeping.app.DISMISS_ALARM"

        fun intent(context: Context, action: String) = Intent(context, SleepService::class.java).setAction(action)
    }
}
