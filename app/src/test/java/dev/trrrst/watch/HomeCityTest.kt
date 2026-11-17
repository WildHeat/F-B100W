package dev.trrrst.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCityTest {
    private val london = City(30439, "LONDON", 0, 60, 2, "Europe/London")
    private val paris = City(30300, "PARIS", 60, 60, 2, "Europe/Paris")
    private val cities = listOf(london, paris)

    @Test fun savedCityWins() {
        assertEquals(paris, HomeCity.choose(30300, "Europe/London", cities))
    }

    @Test fun phoneZoneUsedWhenNothingSaved() {
        assertEquals(london, HomeCity.choose(null, "Europe/London", cities))
    }

    @Test fun unknownSavedCityFallsBackToPhoneZone() {
        assertEquals(london, HomeCity.choose(12345, "Europe/London", cities))
    }

    @Test fun noMatchMeansAskTheUser() {
        assertNull(HomeCity.choose(null, "Mars/Olympus", cities))
    }

    @Test fun summerTimeFlag() {
        assertTrue(HomeCity.dstOn(DstMode.AUTO, phoneInDst = true))
        assertFalse(HomeCity.dstOn(DstMode.AUTO, phoneInDst = false))
        assertTrue(HomeCity.dstOn(DstMode.ON, phoneInDst = false))
        assertFalse(HomeCity.dstOn(DstMode.OFF, phoneInDst = true))
    }
}
