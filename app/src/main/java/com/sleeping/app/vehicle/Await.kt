package com.sleeping.app.vehicle

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val TAG = "Sleeping"
private const val CALL_TIMEOUT_MS = 3_000L

/**
 * Vehicle SDK는 IPC 기반이라 결과를 onComplete / onFailed 콜백으로 비동기 전달한다.
 * 이를 suspend 함수로 감싸고, 실패·타임아웃은 null로 돌려준다.
 * (1차 범위: 차종별 대응은 하지 않고, 실패한 기능은 건너뛴다.)
 */
internal suspend fun <T> awaitOrNull(
    label: String,
    call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): T? = try {
    withTimeout(CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            call(
                { value -> if (cont.isActive) cont.resume(value) },
                { e -> if (cont.isActive) cont.resumeWithException(e) },
            )
        }
    }
} catch (e: TimeoutCancellationException) {
    Log.w(TAG, "$label: 응답 없음(timeout)")
    null
} catch (e: Exception) {
    Log.w(TAG, "$label: 실패 ${e.message}")
    null
}

/** set 계열: 성공 여부만 돌려준다. */
internal suspend fun awaitSet(
    label: String,
    call: (onComplete: () -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): Boolean = awaitOrNull<Unit>(label) { ok, fail -> call({ ok(Unit) }, fail) } != null

/** 범위(min~max) 안에서 비율에 해당하는 값을 계산한다. */
internal fun lerp(min: Int, max: Int, ratio: Float): Int =
    (min + (max - min) * ratio.coerceIn(0f, 1f)).toInt()
