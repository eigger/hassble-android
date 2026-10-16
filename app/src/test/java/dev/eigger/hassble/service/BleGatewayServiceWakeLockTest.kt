package dev.eigger.hassble.service

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BleGatewayServiceWakeLockTest {

    @Test
    fun `BleGatewayService has wakeLock field of type PowerManager WakeLock`() {
        val field = BleGatewayService::class.java.getDeclaredField("wakeLock")
        assertNotNull("wakeLock field must exist in BleGatewayService", field)
        assertEquals(
            "wakeLock field must be PowerManager.WakeLock",
            PowerManager.WakeLock::class.java,
            field.type,
        )
    }
}
