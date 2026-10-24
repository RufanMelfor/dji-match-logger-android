package com.djimatchlogger.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values were computed by replaying the firmware's C implementation
 * (`custom_crc16.c` / `custom_crc32.c`, init `0x3AA3`) over the same inputs.
 * They are an independent check on the copied tables and, above all, on the
 * masking: a table transcribed correctly but shifted with `shr` instead of
 * `ushr` still passes a casual eye and fails here.
 */
class DjiCrcTest {

    @Test
    fun `empty input returns the init value untouched`() {
        assertEquals(0x3AA3, DjiCrc.crc16(ByteArray(0)))
        assertEquals(0x00003AA3, DjiCrc.crc32(ByteArray(0)))
    }

    @Test
    fun `crc16 matches the firmware`() {
        assertEquals(0x797A, DjiCrc.crc16(byteArrayOf(0x00)))
        assertEquals(0x2752, DjiCrc.crc16("123456789".toByteArray()))
    }

    @Test
    fun `crc32 matches the firmware`() {
        assertEquals(0x4FDFF268, DjiCrc.crc32(byteArrayOf(0x00)))
        assertEquals(0xE4D9DC14.toInt(), DjiCrc.crc32("123456789".toByteArray()))
    }

    /**
     * Bytes above 0x7F sign-extend when Kotlin widens them to Int. Without the
     * `and 0xFF` on the table index this walks off the table or lands on the
     * wrong entry.
     */
    @Test
    fun `high bytes do not sign-extend into the table index`() {
        val high = byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0xC3.toByte())
        assertEquals(0x23F3, DjiCrc.crc16(high))
        assertEquals(0x79E102EC, DjiCrc.crc32(high))
    }

    @Test
    fun `honours the length argument instead of the whole array`() {
        val padded = "123456789".toByteArray() + ByteArray(16) { 0x5A }
        assertEquals(0x2752, DjiCrc.crc16(padded, 9))
        assertEquals(0xE4D9DC14.toInt(), DjiCrc.crc32(padded, 9))
    }
}
