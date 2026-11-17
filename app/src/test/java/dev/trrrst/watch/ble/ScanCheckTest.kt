package dev.trrrst.watch.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScanCheckTest {

    @Test
    fun allGoodOnAndroid10() {
        assertNull(ScanCheck.problem(hasAdapter = true, bluetoothOn = true, locationOn = true, sdkInt = 29))
    }

    @Test
    fun noAdapter() {
        assertEquals(
            ScanCheck.NO_BLUETOOTH,
            ScanCheck.problem(hasAdapter = false, bluetoothOn = false, locationOn = true, sdkInt = 29)
        )
    }

    @Test
    fun bluetoothOffWinsOverLocationOff() {
        assertEquals(
            ScanCheck.BLUETOOTH_OFF,
            ScanCheck.problem(hasAdapter = true, bluetoothOn = false, locationOn = false, sdkInt = 29)
        )
    }

    @Test
    fun locationOffBlocksAndroid10And11() {
        for (sdk in listOf(26, 29, 30)) {
            assertEquals(
                ScanCheck.LOCATION_OFF,
                ScanCheck.problem(hasAdapter = true, bluetoothOn = true, locationOn = false, sdkInt = sdk)
            )
        }
    }

    @Test
    fun locationOffIsFineOnAndroid12Plus() {
        assertNull(ScanCheck.problem(hasAdapter = true, bluetoothOn = true, locationOn = false, sdkInt = 31))
    }

    @Test
    fun messagesArePlainEnglish() {
        val all = listOf(
            ScanCheck.NO_BLUETOOTH, ScanCheck.BLUETOOTH_OFF, ScanCheck.LOCATION_OFF,
            ScanCheck.NOT_ALLOWED, ScanCheck.CONNECT_FAILED,
        )
        for (m in all) {
            assertFalse(m, Regex("0x|Exception|[0-9a-f]{4,}").containsMatchIn(m))
        }
    }
}
