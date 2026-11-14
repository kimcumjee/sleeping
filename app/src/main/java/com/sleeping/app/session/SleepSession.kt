package com.sleeping.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WakeReason { TIME_UP, USER, LOW_ENERGY, GEAR_CHANGED }

sealed interface SleepPhase {
    /** 대기. 직전 세션에서 적용·복원되지 않은 기능이 있으면 skipped에 남는다. */
    data class Idle(val message: String? = null, val skipped: List<String> = emptyList()) : SleepPhase

    /** 상태 저장 + 수면 세팅 적용 중 */
    data object Preparing : SleepPhase

    data class Sleeping(val endsAtMillis: Long, val skipped: List<String>) : SleepPhase

    /** 알람이 울리고, 세팅을 복원하고, 창문 환기 중 */
    data class Waking(val reason: WakeReason, val alarmOn: Boolean, val skipped: List<String>) : SleepPhase
}

/** 서비스와 화면이 함께 보는 수면 세션 상태 */
object SleepSession {
    private val _phase = MutableStateFlow<SleepPhase>(SleepPhase.Idle())
    val phase: StateFlow<SleepPhase> = _phase.asStateFlow()

    internal fun set(phase: SleepPhase) {
        _phase.value = phase
    }
}
