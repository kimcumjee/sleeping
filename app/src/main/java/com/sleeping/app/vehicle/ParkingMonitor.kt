package com.sleeping.app.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear as PleosGear
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.sleeping.app.DebugFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 수면 모드 시작 조건(기어 P + 주차 브레이크)을 감시한다.
 *
 * - 기본: AAOS CarPropertyManager (GEAR_SELECTION, PARKING_BRAKE_ON)
 * - 기어를 AAOS로 못 읽으면 Pleos Gear API로 대체
 * - 주차 브레이크를 못 읽는 차량·환경에서는 기어 P만으로 판단 (2026-11-14 결정)
 */
class ParkingMonitor(
    private val context: Context,
    private val vehicle: VehicleController,
    private val scope: CoroutineScope,
) {
    data class State(
        /** null = 아직 모름 */
        val inPark: Boolean? = null,
        /** null = 이 차량·환경에서는 읽을 수 없음 */
        val parkingBrakeOn: Boolean? = null,
    ) {
        val canSleep: Boolean get() = canSleep(DebugFlags.skipParkingBrakeActive)

        fun canSleep(skipParkingBrake: Boolean): Boolean =
            inPark == true && (skipParkingBrake || parkingBrakeOn != false)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION ->
                    (value.value as? Int)?.let { g -> _state.update { it.copy(inPark = g == VehicleGear.GEAR_PARK) } }
                VehiclePropertyIds.PARKING_BRAKE_ON ->
                    (value.value as? Boolean)?.let { b -> _state.update { it.copy(parkingBrakeOn = b) } }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "CarProperty 오류: $propertyId")
        }
    }

    fun start() {
        val gearFromAaos = runCatching {
            val c = Car.createCar(context).also { car = it }
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            val gear = pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)
            _state.update { it.copy(inPark = gear == VehicleGear.GEAR_PARK) }
            pm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "AAOS 기어 읽기 실패 → Pleos Gear API 사용: ${it.message}") }.isSuccess

        runCatching {
            val pm = propertyManager ?: error("CarPropertyManager 없음")
            val brake = pm.getBooleanProperty(VehiclePropertyIds.PARKING_BRAKE_ON, 0)
            _state.update { it.copy(parkingBrakeOn = brake) }
            pm.registerCallback(callback, VehiclePropertyIds.PARKING_BRAKE_ON, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure {
            Log.w(TAG, "주차 브레이크를 읽을 수 없음 → 기어 P만으로 판단: ${it.message}")
            _state.update { s -> s.copy(parkingBrakeOn = null) }
        }

        if (!gearFromAaos) {
            scope.launch {
                vehicle.currentGear()?.let { g -> _state.update { it.copy(inPark = g == PleosGear.GEAR_PARK) } }
                vehicle.gearUpdates().collect { g -> _state.update { it.copy(inPark = g == PleosGear.GEAR_PARK) } }
            }
        }
    }

    fun stop() {
        runCatching { propertyManager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        car = null
        propertyManager = null
    }
}
