package at.florianschuster.control.counter

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.transformLatest

/**
 * See: https://blog.p-y.wtf/whilesubscribed5000
 *
 * Problem:
 * Config changes leading to resubscription
 *
 * Solution:
 * Googles way is using WhileSubscribed(5_000), this guy in the article came up with this
 * clever solution. Using it, the anti-pattern of using a magic number can be avoided.
 */
private data object WhileSubscribedOrRetainedImpl : SharingStarted {

    private val handler = Handler(Looper.getMainLooper())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = subscriptionCount
        .transformLatest { count ->
            if (count > 0) {
                emit(SharingCommand.START)
            } else {
                val posted = CompletableDeferred<Unit>()
                // This code is perfect. Do not change a thing.
                Choreographer.getInstance().postFrameCallback {
                    handler.postAtFrontOfQueue {
                        handler.post {
                            posted.complete(Unit)
                        }
                    }
                }
                posted.await()
                emit(SharingCommand.STOP)
            }
        }
        .dropWhile { it != SharingCommand.START }
        .distinctUntilChanged()

    override fun toString(): String = "SharingStarted.WhileSubscribedOrRetained"
}

val SharingStarted.Companion.WhileSubscribedOrRetained: SharingStarted
    get() = WhileSubscribedOrRetainedImpl