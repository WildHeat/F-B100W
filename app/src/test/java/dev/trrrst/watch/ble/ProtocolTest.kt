package dev.trrrst.watch.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun basicSettingsCanSetAndClearEveryManagedBit() {
        // Actual watch state captured before the bug: 24h on, tone off, power saving off.
        val initial = Protocol.Basic.decode(hex("131700000000000000000000"))
        assertArrayEquals(hex("131600000000000000000000"),
            initial.copy(h24 = false).encode())
        assertArrayEquals(hex("130700000000000000000000"),
            initial.copy(powerSaving = true).encode())
        assertArrayEquals(hex("131500000000000000000000"),
            initial.copy(buttonTone = true).encode())
        assertArrayEquals(hex("131700000000000000000000"), initial.encode())
    }

    @Test
    fun basicSettingsMatchOfficialAppWrites() {
        val official = Protocol.Basic.decode(hex("130700000000000000000000"))
        assertArrayEquals(hex("130600000000000000000000"), official.copy(h24 = false).encode())
        assertArrayEquals(hex("131700000000000000000000"), official.copy(powerSaving = false).encode())
        assertArrayEquals(hex("130701000000000000000000"), official.copy(lightLong = true).encode())
    }

    @Test
    fun stepRecordMatchesCapturedWatchValues() {
        val text = requireNotNull(javaClass.getResource("/step-record.hex")).readText().trim()
        val rec = Protocol.decodeStepRecord(hex(text))
        assertEquals(273, rec.todaySteps)
        assertEquals(100, rec.todayDistance)
        assertEquals(273, rec.hourly.sumOf { it.steps } + rec.currentBuckets.sum())
        assertEquals(2026, rec.timestamp.get(java.util.Calendar.YEAR))
    }

    @Test
    fun alarmModesAndHourlySignalMatchProtocol() {
        val alarms = (0 until 5).map { Protocol.Alarm(enabled = false, hour = 0, minute = 0) }
            .toMutableList()
        alarms[0] = Protocol.Alarm(enabled = true, hour = 10, minute = 30)
        alarms[1] = Protocol.Alarm(enabled = true, hour = 12, minute = 0, oneTime = true)
        alarms[2] = Protocol.Alarm(enabled = true, hour = 6, minute = 0, snooze = true)

        val (daily, _) = Protocol.encodeAlarms(alarms, hourlyChime = false)
        val (chime, rest) = Protocol.encodeAlarms(alarms, hourlyChime = true)
        assertEquals(0x40, daily[1].toInt())
        assertEquals(0xc0, chime[1].toInt() and 0xFF)
        assertEquals(0x60, rest[1].toInt())  // one-time alarm 2
        assertEquals(0x50, rest[5].toInt())  // snooze alarm 3

        val decoded = Protocol.decodeAlarms(chime, rest)
        assertEquals(true, decoded[0].enabled)
        assertEquals(true, decoded[1].oneTime)
        assertEquals(true, decoded[2].snooze)
    }

    @Test
    fun bleSettingsPreserveUnrelatedBytes() {
        val initial = Protocol.BleSettings(hex("110f0f0f0600500004000100001e03"))
        val disabled = initial.withAutoSync(hour = 6, minute = 45, enabled = false)
        assertEquals(0x86, disabled[12].toInt() and 0xFF)
        assertEquals(45, disabled[13].toInt())
        assertEquals(10, initial.withPeriod(10)[14].toInt())
        assertArrayEquals(initial.raw, initial.withAutoSync(0, 30, enabled = true))
    }

    @Test
    fun freshWatchHasNoAutoSyncTime() {
        // Inferred from the user's F-B100W (2026-11-17): enabling auto-sync on a fresh watch
        // sent 110a0a0406005000040001007fff03, i.e. the stored bytes 12/13 were FF FF and only
        // the "disabled" bit got cleared -> hour 127 / minute 255, which the watch rejected.
        val fresh = Protocol.BleSettings(hex("110a0a040600500004000100ffff03"))
        assertEquals(false, fresh.autoSyncEnabled)
        assertEquals(false, fresh.hasAutoSyncTime)
        assertThrows(IllegalArgumentException::class.java) {
            fresh.withAutoSync(fresh.autoSyncHour, fresh.autoSyncMinute, enabled = true)
        }
        val on = fresh.withAutoSync(8, 0, enabled = true)
        assertEquals(8, on[12].toInt())
        assertEquals(0, on[13].toInt())
        assertEquals(true, Protocol.BleSettings(on).hasAutoSyncTime)
        assertEquals(true, Protocol.BleSettings(on).autoSyncEnabled)
    }

    @Test
    fun timerMatchesOfficialAppWrite() {
        val current = hex("18000a000000000000000000000000")
        assertArrayEquals(
            hex("180000060000000000000000000000"),
            Protocol.encodeTimer(0, 0, 6, current)
        )
    }

    @Test
    fun dstSettingsMatchOfficialAppWrites() {
        val world = Protocol.DstSetting(slot = 1, city = 84, utcMin = -420, dstMin = 60, rule = 1)
        assertArrayEquals(hex("1e015400e40401"), world.encode())
        assertEquals(world, Protocol.DstSetting.decode(world.encode()))

        val captured = hex("1d00010303f3765400ffffffffffff")
        val state = Protocol.DstState.decode(captured)
        assertEquals(30451, state.homeCity)
        assertEquals(84, state.worldCity)
        assertEquals(true, state.homeAuto)
        assertEquals(true, state.homeDstOn)
        assertArrayEquals(captured, state.encode())
    }

    // Captured on our F-B100W (2026-11-18 03:02, listen.log), before and after bonding.
    private val capturedUnregisteredAppInfo = "22ffffffffffffffffffff00"

    @Test
    fun unregisteredAppInfoIsRecognised() {
        assertTrue(Protocol.appInfoNeedsRegister(hex(capturedUnregisteredAppInfo)))
        assertArrayEquals(hex(capturedUnregisteredAppInfo), Protocol.APP_INFO_UNREGISTERED)
    }

    @Test
    fun anythingElseIsLeftAlone() {
        assertFalse(Protocol.appInfoNeedsRegister(Protocol.APP_INFO_REGISTER))
        assertFalse(Protocol.appInfoNeedsRegister(hex("22ffffffffffffffffffff02")))  // last byte differs
        assertFalse(Protocol.appInfoNeedsRegister(hex("22ffffffffffffffffff00")))    // too short
        assertFalse(Protocol.appInfoNeedsRegister(hex("22ffffffffffffffffffff0000"))) // too long
        assertFalse(Protocol.appInfoNeedsRegister(hex("23ffffffffffffffffffff00")))  // other opcode
    }

    @Test
    fun registrationMatchesGshockApi() {
        // izivkov/gshock_api src/gshock_api/iolib/app_info_io.py: header 0x22, payload
        // 3488F4E5D5AFC829E06D, trailer 0x02.
        assertArrayEquals(hex("223488f4e5d5afc829e06d02"), Protocol.APP_INFO_REGISTER)
        assertEquals(Protocol.APP_INFO_UNREGISTERED.size, Protocol.APP_INFO_REGISTER.size)
    }

    @Test
    fun connectReasonFromCapturedFeatures() {
        // Captured 2026-11-18 02:20 (C held, app info unregistered): byte 8 = 0.
        val captured = hex("1003f8415a33e57f00030fffffffff26000000")
        assertEquals("E5:33:5A:41:F8:03", Protocol.decodeBleFeatures(captured).first)
        assertEquals(Protocol.ConnectReason.RESET, Protocol.connectReason(captured))
        fun withReason(code: Int) = captured.copyOf().also { it[8] = code.toByte() }
        assertEquals(Protocol.ConnectReason.C_BUTTON, Protocol.connectReason(withReason(1)))
        assertEquals(Protocol.ConnectReason.FIND_PHONE, Protocol.connectReason(withReason(2)))
        assertEquals(Protocol.ConnectReason.AUTO_SYNC, Protocol.connectReason(withReason(3)))
        assertEquals(Protocol.ConnectReason.D_BUTTON, Protocol.connectReason(withReason(4)))
        assertEquals(Protocol.ConnectReason.UNKNOWN, Protocol.connectReason(withReason(99)))
        assertEquals(Protocol.ConnectReason.UNKNOWN, Protocol.connectReason(captured.copyOf(10)))
    }
}
