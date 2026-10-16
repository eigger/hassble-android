package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun `BootReceiver inherits BroadcastReceiver`() {
        assertTrue(
            "BootReceiver must extend BroadcastReceiver",
            BroadcastReceiver::class.java.isAssignableFrom(BootReceiver::class.java),
        )
    }
}
