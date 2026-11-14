package com.sleeping.app

/**
 * 1차 개발 고정값 (2026-11-14 팀 결정).
 * 커스텀·프리셋은 1차 범위에서 제외되어, 모든 수면 세팅은 이 값으로 고정된다.
 */
object SleepConfig {
    /** 수면 시간: 15분 고정 */
    const val SLEEP_DURATION_MS = 15 * 60 * 1000L

    /** 등받이: 차량 범위 기준 70%만큼 뒤로 눕힘 (0% = 가장 세운 상태, 100% = 가장 눕힌 상태) */
    const val BACKREST_RECLINE_RATIO = 0.70f

    /** 시트 앞뒤: 가장 뒤로 (0% = 가장 뒤, 100% = 가장 앞) */
    const val SEAT_FORE_AFT_RATIO = 0.0f

    /** 시트 열선 1단, 통풍 끔 */
    const val SEAT_HEAT_LEVEL = 1
    const val SEAT_VENTILATION_LEVEL = 0

    /** 실내 온도 23℃ */
    const val CABIN_TEMPERATURE_C = 23f

    /** 앰비언트 조명 밝기: 차량 범위 기준 10% */
    const val AMBIENT_BRIGHTNESS_RATIO = 0.10f

    /**
     * 앰비언트 조명 색상(따뜻한 주황).
     * TODO: SDK의 색상 int 형식(RGB/팔레트 번호)을 Pleos 에뮬레이터에서 확인한 뒤 맞춰야 함.
     */
    const val AMBIENT_WARM_COLOR = 0xFF8C3C

    /** 화이트 노이즈(브라운 노이즈) 볼륨 30% */
    const val NOISE_VOLUME = 0.30f

    /** 잔량이 15% 이하가 되면 깨움 */
    const val LOW_ENERGY_PERCENT = 15

    /** 기상 시 운전석 창문을 범위의 20%만큼 열고 1분 뒤 닫음 */
    const val WAKE_WINDOW_OPEN_RATIO = 0.20f
    const val WAKE_WINDOW_OPEN_MS = 60 * 1000L

    /** 기상 알람은 사용자가 끄지 않으면 최대 2분 울림 */
    const val ALARM_MAX_MS = 2 * 60 * 1000L

    const val WAKE_MESSAGE = "일어날 시간이에요. 시트와 실내 설정을 원래대로 돌려 드릴게요."
    const val LOW_ENERGY_MESSAGE = "배터리나 연료가 얼마 남지 않아 깨워 드렸어요."
}
