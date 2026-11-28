/*
 * Film (Yousuf-eng-bot) — export render stall watchdog. GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * A full-resolution render can hang on some devices: a Vulkan driver call that never
 * returns, or a memory-budget state that turns the render into minutes of thrash. The
 * progress bar sits at the SIMULATE stage (~11%) forever, and the user reads it as a
 * frozen app. Kotlin cannot interrupt a wedged native call — withTimeout cannot stop a
 * thread that is inside JNI — but the native render DOES poll a RenderCancellation token
 * between chunks, so the watchdog can request a cooperative stop and, if the render
 * honours it, give up honestly instead of hanging forever.
 *
 * Contract:
 *  - render finishes inside the watchdog window -> return it (the happy path);
 *  - render exceeds the window -> cancel the token, wait one grace window: a SLOW render
 *    that polls stops in time and its result is returned;
 *  - render ignores the stop -> ExportRenderStalledException; the caller surfaces the
 *    actionable "try a smaller size" message. The render thread is abandoned; if it
 *    ever completes, the janitor coroutine releases the result with onLateResult.
 *  - the calling coroutine is cancelled (user cancel) -> the token is cancelled and the
 *    CancellationException propagates, as the previous inline call did.
 *
 * Generic over the result type so the JVM unit test can drive it with fakes.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.RenderCancellation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Raised when a render ignored the cooperative stop for the whole grace window. */
internal class ExportRenderStalledException(message: String) : RuntimeException(message)

internal const val EXPORT_RENDER_WATCHDOG_MS = 4 * 60_000L
internal const val EXPORT_RENDER_GRACE_MS = 30_000L

internal suspend fun <T> withRenderStallWatchdog(
    watchdogMs: Long = EXPORT_RENDER_WATCHDOG_MS,
    graceMs: Long = EXPORT_RENDER_GRACE_MS,
    onLateResult: (T) -> Unit = {},
    start: (RenderCancellation) -> T,
): T {
    val token = RenderCancellation()
    // Own scope: the render runs on a worker while this coroutine holds the watchdog.
    // SupervisorJob so a render failure never cancels the caller's other work.
    val runtime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val consumed = AtomicBoolean(false)
    val deferred = runtime.async { start(token) }
    // Janitor: a result that lands after the watchdog gave up (or after the caller was
    // cancelled) must still be released — these hold hundreds of MB of native memory.
    runtime.launch {
        val late = runCatching { deferred.await() }.getOrNull()
        if (late != null && !consumed.get()) onLateResult(late)
    }
    try {
        val first = withTimeoutOrNull(watchdogMs) { deferred.await() }
        if (first != null) {
            consumed.set(true)
            runtime.cancel()
            return first
        }
        // Watchdog fired. The native render polls this between chunks; a healthy-but-slow
        // render stops within one poll interval, a wedged driver call never does.
        Diag.w("export render exceeded ${watchdogMs}ms; requesting native stop")
        token.cancel()
        val second = withTimeoutOrNull(graceMs) { deferred.await() }
        if (second != null) {
            Diag.w("export render stopped in the grace window; returning the result")
            consumed.set(true)
            runtime.cancel()
            return second
        }
        throw ExportRenderStalledException(
            "render did not finish within ${watchdogMs}ms and ignored the stop request",
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // The user (or the runtime) cancelled the export: relay the stop to the native
        // render and propagate, exactly like the previous inline call did.
        token.cancel()
        // Give the janitor a fair chance to release a late result: it runs on `runtime`,
        // which stays alive until the deferred settles.
        throw cancelled
    }
}
