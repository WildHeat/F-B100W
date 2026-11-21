package dev.trrrst.watch.listen

/** Turns a stream of Bluetooth adverts into a few readable log lines for the
 *  listening test: the first advert from each address, changes in what it sends,
 *  and how long each burst of advertising lasted. Pure, unit-tested.
 *
 *  A burst ends when an address hasn't been heard for [goneAfterMs]. [clock] formats
 *  a time in milliseconds for the log. */
class AdvertTracker(
    private val goneAfterMs: Long = 30_000,
    private val clock: (Long) -> String,
) {
    private class Burst(val first: Long, var last: Long, var count: Int, var data: String)

    private val active = linkedMapOf<String, Burst>()
    private val bursts = mutableListOf<String>()

    /** Total adverts heard so far, from every address. */
    var total = 0
        private set

    /** An advert was heard. Returns the lines to log (often none). */
    fun seen(now: Long, address: String, name: String?, rssi: Int, connectable: Boolean?, data: String): List<String> {
        total++
        val b = active[address]
        if (b == null) {
            active[address] = Burst(now, now, 1, data)
            return listOf(
                "advert from $address: name=${name ?: "(none)"} rssi=$rssi " +
                    "connectable=${connectable ?: "unknown"} data=$data"
            )
        }
        b.last = now
        b.count++
        if (data != b.data) {
            b.data = data
            return listOf("advert from $address changed: rssi=$rssi data=$data")
        }
        return emptyList()
    }

    /** Call now and then: ends the bursts of addresses that went quiet. */
    fun tick(now: Long): List<String> {
        val quiet = active.filterValues { now - it.last >= goneAfterMs }.keys.toList()
        return quiet.map { end(it, "stopped advertising") }
    }

    /** Listening is over: ends every burst still open. */
    fun finish(): List<String> = active.keys.toList().map { end(it, "still advertising when listening stopped") }

    /** One line per burst heard, in the order they ended. */
    fun summary(): List<String> = bursts.toList()

    private fun end(address: String, how: String): String {
        val b = active.remove(address)!!
        val line = "$address $how: heard from ${clock(b.first)} to ${clock(b.last)} " +
            "(${(b.last - b.first + 500) / 1000} s, ${b.count} adverts)"
        bursts += line
        return line
    }
}
