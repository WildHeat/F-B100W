package dev.trrrst.watch

import kotlinx.coroutines.TimeoutCancellationException

/** Who started a sync. Connecting from the app counts as MANUAL. */
enum class SyncTrigger { MANUAL, BACKGROUND }

/** Outcome of [WatchRepository.sync]. [steps] is today's count from this sync (null if
 *  the watch had no step data or the sync failed). [error] is plain English for the user.
 *  Without a known home city the steps are still downloaded but the result is not ok. */
data class SyncResult(val ok: Boolean, val steps: Int? = null, val error: String? = null)

/** Plain-English sync failure messages, each with a next step (PLAN #7). No hex,
 *  no exception text. */
object SyncMessages {
    const val NOT_CONNECTED =
        "The watch isn't connected. Hold the C button on the watch, then press Connect."
    const val CONNECTION_LOST =
        "The connection to the watch was lost. Bring the watch closer, hold C and press Connect again."
    const val NO_ANSWER =
        "The watch stopped answering. Hold C on the watch and press Connect again."
    const val NO_HOME_CITY =
        "The watch time wasn't set because no home city is known. Pick your home city in the World tab and save it."
    const val TIME_NOT_SET =
        "Steps downloaded, but the watch time couldn't be set. Press Sync time to try again."
    const val DIDNT_FINISH =
        "The sync didn't finish. Hold C on the watch and press Connect to try again."

    fun forFailure(e: Throwable, stillConnected: Boolean): String = when {
        !stillConnected -> CONNECTION_LOST
        e is TimeoutCancellationException -> NO_ANSWER
        else -> DIDNT_FINISH
    }
}
