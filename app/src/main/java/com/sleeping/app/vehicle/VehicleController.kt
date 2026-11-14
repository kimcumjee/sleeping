package com.sleeping.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.SeatForeAftPositionListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sleeping.app.SleepConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max

/**
 * Pleos Vehicle SDK로 시트·공조·조명·창문(차광·환기)을 제어한다.
 *
 * 1차 범위 정책
 * - 운전석만 제어한다.
 * - 차종별 대응(지원 기능 숨기기)은 하지 않는다. 지원하지 않거나 실패한 기능은 건너뛰고 report에 남긴다.
 * - 시트 위치 방향은 AAOS VHAL 관례를 따른다고 가정한다.
 *   (등받이: 값이 작을수록 눕힘 / 앞뒤: 값이 작을수록 뒤 / FORWARD 이동 = 값 증가)
 *   TODO: Pleos 에뮬레이터에서 실제 방향 확인.
 */
class VehicleController(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val driverWindow = WindowArea(WindowValue.ROW_1_LEFT)

    private val seat by lazy { vehicle.getSeat() }
    private val hvac by lazy { vehicle.getHVAC() }
    private val light by lazy { vehicle.getLight() }
    private val window by lazy { vehicle.getWindow() }
    private val gear by lazy { vehicle.getGear() }
    private val evBattery by lazy { vehicle.getEvBattery() }
    private val fuel by lazy { vehicle.getFuel() }

    fun connect() {
        runCatching { vehicle.initialize() }.onFailure { Log.e(TAG, "Vehicle 연결 실패", it) }
    }

    fun release() {
        runCatching { vehicle.release() }
    }

    // ---------------------------------------------------------------------
    // 수면 전 상태 저장
    // ---------------------------------------------------------------------

    suspend fun snapshot(): CabinSnapshot {
        val hvacArea = hvacArea()
        val fanArea = fanSpeedArea() ?: hvacArea
        val fanDirArea = fanDirectionArea() ?: hvacArea
        val recircArea = recirculationArea() ?: hvacArea
        val powerArea = hvacPowerArea() ?: hvacArea
        val readingArea = readingLightArea()
        val ambientAreas = ambientAreas()
        val glassAreas = smartGlassBounds().keys
        val blindAreas = blindBounds().keys

        return CabinSnapshot(
            backrest = awaitOrNull<Int?>("등받이 위치") { ok, fail ->
                seat.getCurrentSeatBackrestAnglePosition(driverSeat, ok, fail)
            },
            foreAft = awaitOrNull<Int?>("시트 앞뒤 위치") { ok, fail ->
                seat.getCurrentSeatForeAftPosition(driverSeat, ok, fail)
            },
            seatHeat = awaitOrNull<Int?>("시트 열선") { ok, fail ->
                seat.getHvacSeatTemperature(driverSeat, ok, fail)
            },
            seatVentilation = awaitOrNull<Int?>("시트 통풍") { ok, fail ->
                seat.getHvacSeatVentilationState(driverSeat, ok, fail)
            },
            hvacArea = hvacArea,
            hvacPowerArea = powerArea,
            hvacPowerOn = awaitOrNull<Boolean?>("공조 전원") { ok, fail -> hvac.getHvacPowerState(powerArea, ok, fail) },
            fanSpeedArea = fanArea,
            fanDirectionArea = fanDirArea,
            recirculationArea = recircArea,
            hvacTemperatureC = awaitOrNull<Float?>("설정 온도") { ok, fail ->
                hvac.getHvacTemperature(hvacArea, TemperatureUnit.CELSIUS, ok, fail)
            },
            fanSpeed = awaitOrNull<Int?>("풍량") { ok, fail -> hvac.getHvacFanSpeed(fanArea, ok, fail) },
            fanDirection = awaitOrNull<FanDirectionType?>("풍향") { ok, fail ->
                hvac.getHvacFanDirection(fanDirArea, ok, fail)
            },
            recirculation = awaitOrNull<Boolean?>("내기순환") { ok, fail ->
                hvac.getHvacRecirculationState(recircArea, ok, fail)
            },
            cabinLight = awaitOrNull<LightSwitch?>("실내등") { ok, fail -> light.getCabinLightSwitch(ok, fail) },
            readingLightArea = readingArea,
            readingLight = readingArea?.let { area ->
                awaitOrNull<LightSwitch?>("독서등") { ok, fail -> light.getReadingLightSwitch(area, ok, fail) }
            },
            ambientSwitch = ambientAreas.mapNotNullValues { area ->
                awaitOrNull<LightSwitch?>("앰비언트 스위치") { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) }
            },
            ambientBrightness = ambientAreas.mapNotNullValues { area ->
                awaitOrNull<Int?>("앰비언트 밝기") { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) }
            },
            ambientColor = ambientAreas.mapNotNullValues { area ->
                awaitOrNull<Int?>("앰비언트 색상") { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) }
            },
            smartGlassOn = awaitOrNull<Boolean?>("스마트글래스") { ok, fail ->
                window.getCurrentSmartGlassState(ok, fail)
            },
            smartGlassLevels = glassAreas.mapNotNullValues { area ->
                awaitOrNull<Int?>("스마트글래스 단계") { ok, fail -> window.getCurrentSmartGlassLevel(area, ok, fail) }
            },
            blindPositions = blindAreas.mapNotNullValues { area ->
                awaitOrNull<Int?>("블라인드 위치") { ok, fail -> window.getCurrentWindowBlindPosition(area, ok, fail) }
            },
        )
    }

    // ---------------------------------------------------------------------
    // 수면 세팅 적용 (원터치)
    // ---------------------------------------------------------------------

    suspend fun applySleepSettings(snapshot: CabinSnapshot): ControlReport {
        val skipped = mutableListOf<String>()
        fun check(ok: Boolean, name: String) { if (!ok) skipped += name }

        // 시트: 등받이 70% 눕힘, 가장 뒤로
        val backrestBounds = backrestBounds()
        check(
            backrestBounds != null && driveBackrest(
                // 값이 작을수록 눕힘 → max에서 70%만큼 min 쪽으로
                target = lerp(backrestBounds.max, backrestBounds.min, SleepConfig.BACKREST_RECLINE_RATIO),
                tolerance = tolerance(backrestBounds),
            ),
            "등받이",
        )
        val foreAftBounds = foreAftBounds()
        check(
            foreAftBounds != null && driveForeAft(
                target = lerp(foreAftBounds.min, foreAftBounds.max, SleepConfig.SEAT_FORE_AFT_RATIO),
                tolerance = tolerance(foreAftBounds),
            ),
            "시트 앞뒤",
        )

        // 시트 열선 1단 / 통풍 끔
        check(setSeatHeat(SleepConfig.SEAT_HEAT_LEVEL), "시트 열선")
        check(setSeatVentilation(SleepConfig.SEAT_VENTILATION_LEVEL), "시트 통풍")

        // 공조: 23℃, 최저 풍량, 발 쪽, 외기 유입
        val area = snapshot.hvacArea ?: driverSeat
        // 공조가 꺼져 있으면 풍량·풍향을 바꿀 수 없으므로 먼저 켠다
        if (snapshot.hvacPowerOn != true) check(setHvacPower(snapshot.hvacPowerArea ?: area, true), "공조 전원")
        check(setTemperature(area, SleepConfig.CABIN_TEMPERATURE_C), "실내 온도")
        val fanArea = snapshot.fanSpeedArea ?: area
        val fanBounds = fanSpeedBounds(fanArea)
        check(fanBounds != null && setFanSpeed(fanArea, fanBounds.min), "풍량")
        check(setFanDirection(snapshot.fanDirectionArea ?: area, FanDirectionType.FLOOR), "풍향")
        check(setRecirculation(snapshot.recirculationArea ?: area, false), "외기 유입")

        // 조명: 실내등·독서등 끔, 앰비언트 10% + 따뜻한 색
        check(setCabinLight(LightSwitch.OFF), "실내등")
        snapshot.readingLightArea?.let { check(setReadingLight(it, LightSwitch.OFF), "독서등") }
        val ambient = ambientBrightnessBounds()
        if (ambient.isEmpty()) skipped += "앰비언트 조명"
        ambient.forEach { (a, bounds) ->
            check(setAmbientSwitch(a, LightSwitch.ON), "앰비언트 조명")
            check(setAmbientBrightness(a, lerp(bounds.min, bounds.max, SleepConfig.AMBIENT_BRIGHTNESS_RATIO)), "앰비언트 밝기")
            check(setAmbientColor(a, SleepConfig.AMBIENT_WARM_COLOR), "앰비언트 색상")
        }

        // 차광 (지원 차량만): 스마트글래스 최대, 블라인드 닫기
        val glass = smartGlassBounds()
        if (glass.isNotEmpty()) {
            check(setSmartGlass(true), "스마트글래스")
            glass.forEach { (w, b) -> check(setSmartGlassLevel(w, b.max), "스마트글래스 단계") }
        }
        // TODO: 블라인드 '닫힘' 방향(max 가정)을 에뮬레이터에서 확인
        blindBounds().forEach { (w, b) -> check(driveBlind(w, b.max, tolerance(b)), "블라인드") }

        return ControlReport(skipped.distinct())
    }

    // ---------------------------------------------------------------------
    // 기상: 수면 전 상태로 한 번에 복원
    // ---------------------------------------------------------------------

    suspend fun restore(snapshot: CabinSnapshot): ControlReport {
        val skipped = mutableListOf<String>()
        fun check(ok: Boolean, name: String) { if (!ok) skipped += name }

        snapshot.backrest?.let { target ->
            check(driveBackrest(target, backrestBounds()?.let(::tolerance) ?: 1), "등받이 복원")
        }
        snapshot.foreAft?.let { target ->
            check(driveForeAft(target, foreAftBounds()?.let(::tolerance) ?: 1), "시트 앞뒤 복원")
        }
        snapshot.seatHeat?.let { check(setSeatHeat(it), "시트 열선 복원") }
        snapshot.seatVentilation?.let { check(setSeatVentilation(it), "시트 통풍 복원") }

        val area = snapshot.hvacArea ?: driverSeat
        snapshot.hvacTemperatureC?.let { check(setTemperature(area, it), "실내 온도 복원") }
        snapshot.fanSpeed?.let { check(setFanSpeed(snapshot.fanSpeedArea ?: area, it), "풍량 복원") }
        snapshot.fanDirection?.let { check(setFanDirection(snapshot.fanDirectionArea ?: area, it), "풍향 복원") }
        snapshot.recirculation?.let { check(setRecirculation(snapshot.recirculationArea ?: area, it), "내기순환 복원") }
        if (snapshot.hvacPowerOn == false) check(setHvacPower(snapshot.hvacPowerArea ?: area, false), "공조 전원 복원")

        snapshot.cabinLight?.let { check(setCabinLight(it), "실내등 복원") }
        val readingArea = snapshot.readingLightArea
        if (readingArea != null) snapshot.readingLight?.let { check(setReadingLight(readingArea, it), "독서등 복원") }
        snapshot.ambientColor.forEach { (a, v) -> check(setAmbientColor(a, v), "앰비언트 색상 복원") }
        snapshot.ambientBrightness.forEach { (a, v) -> check(setAmbientBrightness(a, v), "앰비언트 밝기 복원") }
        snapshot.ambientSwitch.forEach { (a, v) -> check(setAmbientSwitch(a, v), "앰비언트 조명 복원") }

        snapshot.smartGlassLevels.forEach { (w, v) -> check(setSmartGlassLevel(w, v), "스마트글래스 단계 복원") }
        snapshot.smartGlassOn?.let { check(setSmartGlass(it), "스마트글래스 복원") }
        val blind = blindBounds()
        snapshot.blindPositions.forEach { (w, v) ->
            check(driveBlind(w, v, blind[w]?.let(::tolerance) ?: 1), "블라인드 복원")
        }

        return ControlReport(skipped.distinct())
    }

    // ---------------------------------------------------------------------
    // 기상 환기: 운전석 창문을 잠깐 열었다가 원래 위치로 닫는다
    // ---------------------------------------------------------------------

    /** 창문을 열고, 열기 전 위치를 돌려준다. 실패하면 null. */
    suspend fun openWindowForVentilation(): Int? {
        val bounds = awaitOrNull<Map<WindowArea, AreaBounds<Int>>?>("창문 범위") { ok, fail ->
            window.checkWindowPositionCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
        }?.get(driverWindow)?.toRange() ?: return null
        val original = awaitOrNull<Int?>("창문 위치") { ok, fail ->
            window.getCurrentWindowPosition(driverWindow, ok, fail)
        } ?: return null
        // AAOS WINDOW_POS 관례: 0 = 닫힘, 값이 클수록 열림 (음수는 벤트 기울임)
        val closed = max(0, bounds.min)
        val target = max(original, lerp(closed, bounds.max, SleepConfig.WAKE_WINDOW_OPEN_RATIO))
        val ok = awaitSet("창문 열기") { done, fail ->
            window.setWindowPosition(driverWindow, target, { _, _ -> done() }, fail)
        }
        return if (ok) original else null
    }

    suspend fun closeWindow(originalPosition: Int): Boolean = awaitSet("창문 닫기") { done, fail ->
        window.setWindowPosition(driverWindow, originalPosition, { _, _ -> done() }, fail)
    }

    // ---------------------------------------------------------------------
    // 상태 감시: 기어(Pleos Gear API, AAOS 실패 시 대체용), 배터리·연료 잔량
    // ---------------------------------------------------------------------

    suspend fun currentGear(): VehicleGear? = awaitOrNull<VehicleGear?>("기어") { ok, fail ->
        gear.getCurrentGearSelectionState(ok, fail)
    }

    fun gearUpdates(): Flow<VehicleGear> = callbackFlow {
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { if (gear != null) trySend(gear) }
            override fun onFailed(e: Exception) { Log.w(TAG, "기어 감시 실패: ${e.message}") }
        }
        runCatching { gear.registerGearSelection(listener) }
        awaitClose { runCatching { gear.unregisterGearSelection(listener) } }
    }

    /** 현재 배터리(EV) 또는 연료 잔량(%). 둘 다 읽히면 작은 값. */
    suspend fun currentEnergyPercent(): Int? {
        val ev = awaitOrNull<Int?>("배터리 잔량") { ok, fail -> evBattery.getEvBatteryStateOfCharge(ok, fail) }
        val fuelPct = awaitOrNull<Int?>("연료 잔량") { ok, fail -> fuel.getFuelPercent(ok, fail) }
        return listOfNotNull(ev, fuelPct).minOrNull()
    }

    fun energyUpdates(): Flow<Int> = merge(
        callbackFlow {
            val l = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) { if (value != null) trySend(value) }
                override fun onFailed(e: Exception) {}
            }
            runCatching { evBattery.registerEvBatteryStateOfCharge(l) }
            awaitClose { runCatching { evBattery.unregisterEvBatteryStateOfCharge(l) } }
        },
        callbackFlow {
            val l = object : FuelPercentListener {
                override fun onFuelPercentUpdated(value: Int?) { if (value != null) trySend(value) }
                override fun onFailed(e: Exception) {}
            }
            runCatching { fuel.registerFuelPercent(l) }
            awaitClose { runCatching { fuel.unregisterFuelPercent(l) } }
        },
    )

    // ---------------------------------------------------------------------
    // 시트·블라인드 이동: 모터를 움직이고 목표 위치에 닿으면 멈춘다
    // ---------------------------------------------------------------------

    private suspend fun driveBackrest(target: Int, tolerance: Int): Boolean = drive(
        label = "등받이",
        target = target,
        tolerance = tolerance,
        read = {
            awaitOrNull<Int?>("등받이 위치") { ok, fail ->
                seat.getCurrentSeatBackrestAnglePosition(driverSeat, ok, fail)
            }
        },
        positions = { onRegistered ->
            callbackFlow {
                val l = object : SeatBackrestAnglePositionListener {
                    override fun onSeatBackrestAnglePositionUpdated(position: Int?) { if (position != null) trySend(position) }
                    override fun onFailed(e: Exception) {}
                }
                seat.registerSeatBackrestAnglePosition(driverSeat, l)
                onRegistered()
                awaitClose { runCatching { seat.unregisterSeatBackrestAnglePosition(driverSeat, l) } }
            }
        },
        move = { mc ->
            seat.moveSeatBackrestAngle(driverSeat, mc, { _, _ -> }, { e -> Log.w(TAG, "등받이 이동 실패: ${e.message}") })
        },
    )

    private suspend fun driveForeAft(target: Int, tolerance: Int): Boolean = drive(
        label = "시트 앞뒤",
        target = target,
        tolerance = tolerance,
        read = {
            awaitOrNull<Int?>("시트 앞뒤 위치") { ok, fail ->
                seat.getCurrentSeatForeAftPosition(driverSeat, ok, fail)
            }
        },
        positions = { onRegistered ->
            callbackFlow {
                val l = object : SeatForeAftPositionListener {
                    override fun onSeatForeAftPositionUpdated(position: Int?) { if (position != null) trySend(position) }
                    override fun onFailed(e: Exception) {}
                }
                seat.registerSeatForeAftPosition(driverSeat, l)
                onRegistered()
                awaitClose { runCatching { seat.unregisterSeatForeAftPosition(driverSeat, l) } }
            }
        },
        move = { mc ->
            seat.moveSeatForeAft(driverSeat, mc, { _, _ -> }, { e -> Log.w(TAG, "시트 앞뒤 이동 실패: ${e.message}") })
        },
    )

    private suspend fun driveBlind(area: WindowArea, target: Int, tolerance: Int): Boolean = drive(
        label = "블라인드",
        target = target,
        tolerance = tolerance,
        read = {
            awaitOrNull<Int?>("블라인드 위치") { ok, fail -> window.getCurrentWindowBlindPosition(area, ok, fail) }
        },
        positions = { onRegistered ->
            callbackFlow {
                val l = object : WindowPositionListener {
                    override fun onWindowPositionUpdated(position: Int?) { if (position != null) trySend(position) }
                    override fun onFailed(e: Exception) {}
                }
                window.registerWindowBlindPosition(area, l)
                onRegistered()
                awaitClose { runCatching { window.unregisterWindowBlindPosition(area, l) } }
            }
        },
        move = { mc ->
            window.moveWindowBlind(area, mc, { _, _ -> }, { e -> Log.w(TAG, "블라인드 이동 실패: ${e.message}") })
        },
    )

    private suspend fun drive(
        label: String,
        target: Int,
        tolerance: Int,
        read: suspend () -> Int?,
        positions: (onRegistered: () -> Unit) -> Flow<Int>,
        move: (MoveControl) -> Unit,
    ): Boolean {
        val start = read() ?: return false
        Log.d(TAG, "$label: 현재 $start → 목표 $target (허용 ±$tolerance)")
        if (abs(start - target) <= tolerance) return true
        val direction = if (target > start) MoveControl.FORWARD else MoveControl.BACKWARD
        return try {
            val result = withTimeoutOrNull(MOVE_TIMEOUT_MS) {
                coroutineScope {
                    var latest = start
                    var lastChangeAt = SystemClock.elapsedRealtime()
                    val collector = launch {
                        positions { runCatching { move(direction) } }.collect { p ->
                            if (p != latest) {
                                Log.d(TAG, "$label: 위치 $p ($direction)")
                                latest = p
                                lastChangeAt = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    try {
                        while (true) {
                            val p = latest
                            if (if (direction == MoveControl.FORWARD) p >= target - tolerance else p <= target + tolerance) return@coroutineScope true
                            // 모터가 일정 시간 움직이지 않으면 (끝에 닿았거나 지원하지 않음) 건너뛴다
                            if (SystemClock.elapsedRealtime() - lastChangeAt > STALL_MS) {
                                Log.w(TAG, "$label: ${STALL_MS / 1000}초 동안 위치 변화 없음 → 건너뜀 (현재 $p)")
                                return@coroutineScope false
                            }
                            delay(POLL_MS)
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } finally {
                        collector.cancel()
                    }
                }
            }
            if (result == null) Log.w(TAG, "$label: 목표 위치에 도달하지 못함(timeout)")
            result == true
        } catch (e: Exception) {
            Log.w(TAG, "$label 이동 실패: ${e.message}")
            false
        } finally {
            runCatching { move(MoveControl.STOP) }
            delay(STOP_SETTLE_MS)
        }
    }

    // ---------------------------------------------------------------------
    // 단일 set 호출
    // ---------------------------------------------------------------------

    private suspend fun setSeatHeat(level: Int) = awaitSet("시트 열선") { done, fail ->
        seat.setHvacSeatTemperature(driverSeat, level, { _, _ -> done() }, fail)
    }

    private suspend fun setSeatVentilation(level: Int) = awaitSet("시트 통풍") { done, fail ->
        seat.setHvacSeatVentilation(driverSeat, level, { _, _ -> done() }, fail)
    }

    private suspend fun setTemperature(area: SeatArea, celsius: Float) = awaitSet("실내 온도") { done, fail ->
        hvac.setHvacTemperature(area, celsius, TemperatureUnit.CELSIUS, { _, _ -> done() }, fail)
    }

    private suspend fun setHvacPower(area: SeatArea, on: Boolean) = awaitSet("공조 전원") { done, fail ->
        hvac.setHvacPower(area, on, { _, _ -> done() }, fail)
    }

    private suspend fun setFanSpeed(area: SeatArea, speed: Int) = awaitSet("풍량") { done, fail ->
        hvac.setHvacFanSpeed(area, speed, { _, _ -> done() }, fail)
    }

    private suspend fun setFanDirection(area: SeatArea, dir: FanDirectionType) = awaitSet("풍향") { done, fail ->
        hvac.setHvacFanDirection(area, dir, { _, _ -> done() }, fail)
    }

    private suspend fun setRecirculation(area: SeatArea, on: Boolean) = awaitSet("내기순환") { done, fail ->
        hvac.setHvacRecirculation(area, on, { _, _ -> done() }, fail)
    }

    private suspend fun setCabinLight(value: LightSwitch) = awaitSet("실내등") { done, fail ->
        light.setCabinLightSwitch(value, { done() }, fail)
    }

    private suspend fun setReadingLight(area: SeatArea, value: LightSwitch) = awaitSet("독서등") { done, fail ->
        light.setReadingLightSwitch(area, value, { done() }, fail)
    }

    private suspend fun setAmbientSwitch(area: SeatArea, value: LightSwitch) = awaitSet("앰비언트 스위치") { done, fail ->
        light.setAmbientLightSwitch(area, value, { _, _ -> done() }, fail)
    }

    private suspend fun setAmbientBrightness(area: SeatArea, value: Int) = awaitSet("앰비언트 밝기") { done, fail ->
        light.setAmbientLightBrightness(area, value, { _, _ -> done() }, fail)
    }

    private suspend fun setAmbientColor(area: SeatArea, color: Int) = awaitSet("앰비언트 색상") { done, fail ->
        light.setAmbientLightBrightnessColor(area, color, { _, _ -> done() }, fail)
    }

    private suspend fun setSmartGlass(on: Boolean) = awaitSet("스마트글래스") { done, fail ->
        window.setSmartGlassState(on, { done() }, fail)
    }

    private suspend fun setSmartGlassLevel(area: WindowArea, level: Int) = awaitSet("스마트글래스 단계") { done, fail ->
        window.setSmartGlassLevel(area, level, { _, _ -> done() }, fail)
    }

    // ---------------------------------------------------------------------
    // capability: 값 범위와 대상 영역 (1차에서는 화면 분기 없이 내부 계산에만 사용)
    // ---------------------------------------------------------------------

    private data class Range(val min: Int, val max: Int)

    private fun tolerance(r: Range): Int = max(1, abs(r.max - r.min) / 50)

    private suspend fun backrestBounds(): Range? = awaitOrNull<Map<SeatArea, AreaBounds<Int>>?>("등받이 범위") { ok, fail ->
        seat.checkSeatBackrestAnglePositionCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
    }?.also { Log.d(TAG, "범위: $it") }?.forDriver()?.toRange()

    private suspend fun foreAftBounds(): Range? = awaitOrNull<Map<SeatArea, AreaBounds<Int>>?>("시트 앞뒤 범위") { ok, fail ->
        seat.checkSeatForeAftPositionCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
    }?.also { Log.d(TAG, "범위: $it") }?.forDriver()?.toRange()

    private suspend fun fanSpeedBounds(area: SeatArea): Range? = awaitOrNull<Map<SeatArea, AreaBounds<Int>>?>("풍량 범위") { ok, fail ->
        hvac.checkHvacFanSpeedCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
    }?.let { it[area] ?: it.forDriver() }?.toRange()

    private suspend fun hvacArea(): SeatArea = awaitOrNull<Map<SeatArea, AreaBounds<Float>>?>("공조 영역") { ok, fail ->
        hvac.checkHvacTemperatureCapabilities({ _: CapabilityConfig, map: Map<SeatArea, AreaBounds<Float>>, _: HvacTemperatureConfigArray? -> ok(map) }, fail)
    }?.keys?.driverKey() ?: driverSeat

    private suspend fun hvacPowerArea(): SeatArea? = awaitOrNull<List<SeatArea>?>("공조 전원 영역") { ok, fail ->
        hvac.checkHvacPowerCapabilities({ _: CapabilityConfig, list -> ok(list) }, fail)
    }?.toSet()?.driverKey()

    private suspend fun fanSpeedArea(): SeatArea? = awaitOrNull<Map<SeatArea, AreaBounds<Int>>?>("풍량 영역") { ok, fail ->
        hvac.checkHvacFanSpeedCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
    }?.keys?.driverKey().also { Log.d(TAG, "풍량 영역: $it") }

    private suspend fun fanDirectionArea(): SeatArea? =
        awaitOrNull<Map<SeatArea, List<FanDirectionType>>?>("풍향 영역") { ok, fail ->
            hvac.checkHvacFanDirectionCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
        }?.keys?.driverKey().also { Log.d(TAG, "풍향 영역: $it") }

    private suspend fun recirculationArea(): SeatArea? = awaitOrNull<List<SeatArea>?>("내기순환 영역") { ok, fail ->
        hvac.checkHvacRecirculationCapabilities({ _: CapabilityConfig, list -> ok(list) }, fail)
    }?.toSet()?.driverKey().also { Log.d(TAG, "내기순환 영역: $it") }

    private suspend fun readingLightArea(): SeatArea? = awaitOrNull<Map<SeatArea, List<LightSwitch>>?>("독서등 영역") { ok, fail ->
        light.checkReadingLightSwitchCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
    }?.keys?.driverKey()

    private suspend fun ambientBrightnessBounds(): Map<SeatArea, Range> =
        awaitOrNull<Map<SeatArea, AreaBounds<Int>>?>("앰비언트 범위") { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
        }?.mapValues { it.value.toRange() } ?: emptyMap()

    private suspend fun ambientAreas(): Set<SeatArea> = ambientBrightnessBounds().keys

    private suspend fun smartGlassBounds(): Map<WindowArea, Range> =
        awaitOrNull<Map<WindowArea, AreaBounds<Int>>?>("스마트글래스 범위") { ok, fail ->
            window.checkSmartGlassLevelCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
        }?.mapValues { it.value.toRange() } ?: emptyMap()

    private suspend fun blindBounds(): Map<WindowArea, Range> =
        awaitOrNull<Map<WindowArea, AreaBounds<Int>>?>("블라인드 범위") { ok, fail ->
            window.checkWindowBlindPositionCapabilities({ _: CapabilityConfig, map -> ok(map) }, fail)
        }?.mapValues { it.value.toRange() } ?: emptyMap()

    private fun <V> Map<SeatArea, V>.forDriver(): V? = this[driverSeat] ?: keys.driverKey()?.let { this[it] }

    /** 운전석(ROW_1_LEFT)을 포함하는 영역을 찾는다. 공조처럼 여러 좌석을 묶은 영역일 수 있다. */
    private fun Set<SeatArea>.driverKey(): SeatArea? =
        firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: firstOrNull()

    private fun AreaBounds<Int>.toRange() = Range(minValue, maxValue)

    private suspend fun <K, V : Any> Set<K>.mapNotNullValues(get: suspend (K) -> V?): Map<K, V> {
        val out = LinkedHashMap<K, V>()
        for (k in this) get(k)?.let { out[k] = it }
        return out
    }

    private companion object {
        const val MOVE_TIMEOUT_MS = 20_000L
        const val STOP_SETTLE_MS = 300L
        const val STALL_MS = 3_000L
        const val POLL_MS = 100L
    }
}
