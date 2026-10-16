package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootReceiverTest {

    @Test
    fun `BootReceiver inherits BroadcastReceiver`() {
        assertTrue(
            "BootReceiver must extend BroadcastReceiver",
            BroadcastReceiver::class.java.isAssignableFrom(BootReceiver::class.java),
        )
    }

    @Test
    fun `BootReceiver timeout is 10 seconds`() {
        assertEquals(10_000L, BOOT_RECEIVER_TIMEOUT_MS)
    }

    @Test
    fun `AndroidManifest declares BootReceiver with BOOT_COMPLETED and MY_PACKAGE_REPLACED`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml should exist", manifestFile.exists())
        val content = manifestFile.readText()

        assertTrue(
            "BootReceiver should be declared in AndroidManifest.xml",
            content.contains(".service.BootReceiver"),
        )
        assertTrue(
            "BOOT_COMPLETED action should be registered for BootReceiver",
            content.contains("android.intent.action.BOOT_COMPLETED"),
        )
        assertTrue(
            "MY_PACKAGE_REPLACED action should be registered for BootReceiver",
            content.contains("android.intent.action.MY_PACKAGE_REPLACED"),
        )
    }
}

