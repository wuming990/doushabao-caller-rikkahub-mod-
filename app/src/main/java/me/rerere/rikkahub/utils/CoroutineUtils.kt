package me.rerere.rikkahub.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoroutineUtils"

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        // v323：必须把 CancellationException 原样上抛。旧写法用 runCatching 一把抓，
        // 正常取消（页面关闭 / 退出 App / 单测 tearDown 取消 scope）也会走进 onFailure，
        // 然后 Runtime.halt(1) 直接把整个进程杀掉 —— 单测 JVM 就是这样非正常退出的。
        // 真正的读取异常仍然保留 halt(1)（官方原有行为）。
        try {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error while collecting flow: ${e.message}", e)

            Runtime.getRuntime().halt(1)
        }
    }
    return stateFlow
}
