package dev.trrrst.watch

/** A watch city from assets/cities_*.json. [iana] is set for home cities only. */
data class City(
    val no: Int, val name: String, val tzMin: Int, val dstMin: Int, val rule: Int,
    val iana: String? = null,
)

/** Summer-time modes as used by the World screen and the watch. */
object DstMode {
    const val AUTO = 0
    const val ON = 1
    const val OFF = 2
}

/**
 * The F-B100W forgets its home city when a connection ends, and ignores a time write
 * unless a home city was written just before it (seen on the watch, 2026-11-17). So
 * every sync re-sends a home city, chosen here.
 */
object HomeCity {
    /** The user's saved city if it still exists, else the city matching the phone's
     *  time zone, else null (the user has to pick one in the World tab). */
    fun choose(savedNo: Int?, phoneZone: String, cities: List<City>): City? =
        savedNo?.let { no -> cities.find { it.no == no } }
            ?: cities.find { it.iana == phoneZone }

    /** Summer-time flag to send. In auto mode it follows the phone, which knows
     *  whether summer time is in effect right now. */
    fun dstOn(mode: Int, phoneInDst: Boolean): Boolean = when (mode) {
        DstMode.ON -> true
        DstMode.OFF -> false
        else -> phoneInDst
    }
}
