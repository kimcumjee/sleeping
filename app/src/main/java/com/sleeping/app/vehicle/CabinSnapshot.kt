package com.sleeping.app.vehicle

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch

/**
 * 수면 세팅을 적용하기 직전의 차량 상태.
 * 기상 시 이 값으로 한 번에 복원한다. 읽지 못한 항목(null / 빈 map)은 복원하지 않는다.
 */
data class CabinSnapshot(
    val backrest: Int? = null,
    val foreAft: Int? = null,
    val seatHeat: Int? = null,
    val seatVentilation: Int? = null,
    val hvacArea: SeatArea? = null,
    val hvacPowerArea: SeatArea? = null,
    val hvacPowerOn: Boolean? = null,
    /** 풍량·풍향·내기순환은 차량에 따라 온도와 다른 영역(예: 1열 전체)을 쓴다 */
    val fanSpeedArea: SeatArea? = null,
    val fanDirectionArea: SeatArea? = null,
    val recirculationArea: SeatArea? = null,
    val hvacTemperatureC: Float? = null,
    val fanSpeed: Int? = null,
    val fanDirection: FanDirectionType? = null,
    val recirculation: Boolean? = null,
    val cabinLight: LightSwitch? = null,
    val readingLightArea: SeatArea? = null,
    val readingLight: LightSwitch? = null,
    val ambientSwitch: Map<SeatArea, LightSwitch> = emptyMap(),
    val ambientBrightness: Map<SeatArea, Int> = emptyMap(),
    val ambientColor: Map<SeatArea, Int> = emptyMap(),
    val smartGlassOn: Boolean? = null,
    val smartGlassLevels: Map<WindowArea, Int> = emptyMap(),
    val blindPositions: Map<WindowArea, Int> = emptyMap(),
)

/** 적용·복원 결과. 지원하지 않거나 실패한 기능은 건너뛰고 이름만 모아 둔다. */
data class ControlReport(val skipped: List<String>)
