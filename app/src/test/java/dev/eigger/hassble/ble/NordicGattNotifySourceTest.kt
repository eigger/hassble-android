package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class NordicGattNotifySourceTest {

    @Test
    fun `activeConnections field is ConcurrentHashMap`() {
        val field = NordicGattNotifySource::class.java.getDeclaredField("activeConnections")
        assertEquals(
            "activeConnections must be a ConcurrentHashMap to prevent concurrent modification issues",
            ConcurrentHashMap::class.java,
            field.type,
        )
    }
}
