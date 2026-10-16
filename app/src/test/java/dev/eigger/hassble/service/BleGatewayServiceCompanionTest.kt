package dev.eigger.hassble.service

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class BleGatewayServiceCompanionTest {

    @Test
    fun `BleGatewayService companion object contains expected control methods`() {
        val companionClass = BleGatewayService.Companion::class.java
        val methodNames = companionClass.declaredMethods.map { it.name }.toSet()

        val expectedMethods = listOf(
            "start",
            "reloadConfig",
            "stop",
            "removeDevice",
            "setAutoConnect",
            "connectDevice",
            "disconnectDevice",
            "triggerAdvertise",
            "stopAdvertise",
        )

        for (methodName in expectedMethods) {
            assertTrue("Method $methodName should exist in companion object", methodNames.contains(methodName))
        }
    }
}
