package com.sleeping.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.sleeping.app.session.SleepService
import com.sleeping.app.session.SleepSession
import com.sleeping.app.vehicle.ParkingMonitor
import com.sleeping.app.vehicle.VehicleController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 시작 화면의 상태 표시(기어·잔량)용. 수면 세션 자체는 SleepService가 담당한다.
    private lateinit var vehicle: VehicleController
    private lateinit var parking: ParkingMonitor
    private val energy = MutableStateFlow<Int?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = VehicleController(this).also { it.connect() }
        parking = ParkingMonitor(this, vehicle, lifecycleScope).also { it.start() }
        lifecycleScope.launch {
            while (true) {
                energy.value = vehicle.currentEnergyPercent()
                delay(30_000)
            }
        }
        requestNotificationPermission()

        setContent {
            SleepingTheme {
                val phase by SleepSession.phase.collectAsStateWithLifecycle()
                val parkState by parking.state.collectAsStateWithLifecycle()
                val energyPercent by energy.collectAsStateWithLifecycle()
                SleepingApp(
                    phase = phase,
                    parking = parkState,
                    energyPercent = energyPercent,
                    onStart = { startService(SleepService.intent(this, SleepService.ACTION_START)) },
                    onWakeNow = { startService(SleepService.intent(this, SleepService.ACTION_WAKE_NOW)) },
                    onDismissAlarm = { startService(SleepService.intent(this, SleepService.ACTION_DISMISS_ALARM)) },
                )
            }
        }
    }

    override fun onDestroy() {
        parking.stop()
        vehicle.release()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
