package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPermissionsTest {

    @Test
    fun `API 31+ needs BLUETOOTH_SCAN and fine location`() {
        assertEquals(
            listOf(ScanPermissions.BLUETOOTH_SCAN, ScanPermissions.ACCESS_FINE_LOCATION),
            ScanPermissions.required(31),
        )
        assertEquals(ScanPermissions.required(31), ScanPermissions.required(34))
    }

    @Test
    fun `below API 31 BLUETOOTH_SCAN does not exist so only location is required`() {
        // minSdk 26: 여기서 BLUETOOTH_SCAN을 보면 checkSelfPermission이 항상 DENIED라 영원히 기다린다.
        assertEquals(listOf(ScanPermissions.ACCESS_FINE_LOCATION), ScanPermissions.required(26))
        assertEquals(listOf(ScanPermissions.ACCESS_FINE_LOCATION), ScanPermissions.required(30))
    }

    @Test
    fun `missing lists only the denied ones`() {
        val granted = setOf(ScanPermissions.BLUETOOTH_SCAN)
        assertEquals(listOf(ScanPermissions.ACCESS_FINE_LOCATION), ScanPermissions.missing(33) { it in granted })
        assertTrue(ScanPermissions.missing(33) { true }.isEmpty())
        assertTrue(ScanPermissions.missing(28) { it == ScanPermissions.ACCESS_FINE_LOCATION }.isEmpty())
    }
}
