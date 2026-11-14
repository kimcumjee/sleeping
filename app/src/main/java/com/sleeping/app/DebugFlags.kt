package com.sleeping.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 디버그 빌드 전용 테스트 스위치. 릴리스 빌드에서는 항상 꺼져 있다.
 *
 * Pleos Connect 에뮬레이터 가상 콘솔은 주차 브레이크 경고등만 켤 수 있고
 * PARKING_BRAKE_ON 값은 바꿀 수 없어서, 에뮬레이터에서는 기어 P만으로 시작할 수 있게 한다.
 */
object DebugFlags {
    val available: Boolean = BuildConfig.DEBUG

    /** true면 안전 확인에서 주차 브레이크를 건너뛰고 기어 P만 확인 */
    val skipParkingBrake = MutableStateFlow(false)

    val skipParkingBrakeActive: Boolean get() = available && skipParkingBrake.value
}
