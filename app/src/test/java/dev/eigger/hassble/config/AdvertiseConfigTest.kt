package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertiseConfigTest {

    @Test
    fun testParseAdvertiseYaml() {
        val yaml = """
        devices:
          - id: mytown_parking
            name: "마이타운 주차위치"
            source: advertisement
            instance_mode: shared
            match:
              manufacturer_id: 861
              manufacturer_hex_prefix: "06CB34447B91F8C69BBE41157C70BB631C"
              manufacturer_min_length: 20
            advertise:
              manufacturer_id: 861
              payload: "05CB34447B91F8C69BBE41157C70BB631C40{counter:02X}"
              mode: balanced
              tx_power: high
              timeout: 15s
              repeat_interval: 1s
              stop_on_response: true
              connectable: false
              scannable: true
            controls:
              - key: request_location
                type: button
                name: "주차위치 요청"
                action: advertise
                icon: mdi:car-search
            sensors:
              - key: floor
                icon: mdi:layers-outline
                source_field: manufacturer_data
                min_length: 18
                decode: { offset: 17, length: 1, type: int8 }
              - key: location
                platform: text_sensor
                icon: mdi:map-marker-outline
                source_field: manufacturer_data
                min_length: 19
                decode: { offset: 18, length: 0, type: string }
        """.trimIndent()

        val config = Yaml.default.decodeFromString(GatewayConfig.serializer(), yaml)
        assertEquals(1, config.devices.size)
        val d = config.devices[0]
        assertEquals("mytown_parking", d.id)
        assertEquals(Source.advertisement, d.source)
        assertEquals(AdvertisementInstanceMode.shared, d.instanceMode)

        val adv = d.advertise
        assertNotNull(adv)
        assertEquals(861, adv!!.manufacturerId)
        assertEquals("05CB34447B91F8C69BBE41157C70BB631C40{counter:02X}", adv.payload)
        assertEquals(AdvertiseCounterMode.reset, adv.counterMode)
        assertEquals(0, adv.counterStart)
        assertEquals(AdvertiseModeOption.balanced, adv.mode)
        assertEquals(AdvertiseTxPowerOption.high, adv.txPower)
        assertEquals("15s", adv.timeout)
        assertEquals("1s", adv.repeatInterval)
        assertTrue(adv.stopOnResponse)
        assertFalse(adv.connectable)
        assertTrue(adv.scannable)
        assertFalse(adv.includeDeviceName)

        val ctrl = d.controls[0]
        assertEquals("request_location", ctrl.key)
        assertEquals(ControlType.button, ctrl.type)
        assertEquals(ControlAction.advertise, ctrl.action)

        val issues = ConfigValidator.validate(config)
        assertTrue("Expected no ERROR issues, but got: $issues", issues.none { it.level == ValidationLevel.ERROR })
    }

    @Test
    fun testParseAdvertisePersistMode() {
        val yaml = """
        devices:
          - id: mytown_parking
            name: "마이타운 주차위치"
            source: advertisement
            advertise:
              manufacturer_id: 861
              payload: "05CB34447B91F8C69BBE41157C70BB631C40{counter:02X}"
              counter_mode: persist
              counter_start: 5
        """.trimIndent()

        val config = Yaml.default.decodeFromString(GatewayConfig.serializer(), yaml)
        val adv = config.devices[0].advertise
        assertNotNull(adv)
        assertEquals(AdvertiseCounterMode.persist, adv!!.counterMode)
        assertEquals(5, adv.counterStart)
    }

    @Test
    fun testRule1_ControlActionWithoutAdvertiseBlock() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            controls = listOf(
                ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)
            )
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val err = issues.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("requires an 'advertise' block") }
        assertNotNull(err)
    }

    @Test
    fun testRule2_AdvertiseOnNonAdvertisementSource() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.gatt_notify,
            gatt = GattConfig(serviceUuid = "FFF0", notifyCharUuid = "FFF1", writeCharUuid = "FFF2"),
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "05CB"),
            controls = listOf(
                ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)
            )
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val err = issues.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("only supported for source: advertisement") }
        assertNotNull(err)
    }

    @Test
    fun testRule3_AdvertisePayloadInvalid() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "INVALID_HEX_ZZ"),
            controls = listOf(
                ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)
            )
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val err = issues.firstOrNull { it.level == ValidationLevel.ERROR }
        assertNotNull(err)
    }

    @Test
    fun testRule4_AdvertiseInstanceModeMacWarning() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.mac,
            match = MatchConfig(manufacturerId = 861),
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "05CB34"),
            controls = listOf(
                ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)
            )
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val warn = issues.firstOrNull { it.level == ValidationLevel.WARNING && it.message.contains("instance_mode: mac") }
        assertNotNull(warn)
    }

    @Test
    fun testRule5_AdvertiseWithoutAdvertiseActionControlWarning() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.shared,
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "05CB34"),
            controls = emptyList()
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val warn = issues.firstOrNull { it.level == ValidationLevel.WARNING && it.message.contains("no control with action: advertise") }
        assertNotNull(warn)
    }

    @Test
    fun testHasDeviceError() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "INVALID_HEX_ZZ"),
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        assertTrue(ConfigValidator.hasDeviceError(issues, "test"))
        assertFalse(ConfigValidator.hasDeviceError(issues, "other_device"))
    }

    @Test
    fun testCounterStartOutOfRange() {
        val deviceNegative = DeviceConfig(
            id = "test_neg",
            name = "Test",
            source = Source.advertisement,
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "05CB34", counterStart = -1),
        )
        val issuesNeg = ConfigValidator.validate(GatewayConfig(devices = listOf(deviceNegative)))
        val errNeg = issuesNeg.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("counter_start must be between 0 and 255") }
        assertNotNull(errNeg)

        val deviceTooLarge = DeviceConfig(
            id = "test_large",
            name = "Test",
            source = Source.advertisement,
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "05CB34", counterStart = 256),
        )
        val issuesLarge = ConfigValidator.validate(GatewayConfig(devices = listOf(deviceTooLarge)))
        val errLarge = issuesLarge.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("counter_start must be between 0 and 255") }
        assertNotNull(errLarge)
    }

    @Test
    fun testIncludeDeviceNameWarning() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.shared,
            advertise = AdvertiseConfig(manufacturerId = 861, payload = "05CB34", includeDeviceName = true),
            controls = listOf(ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)),
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val warn = issues.firstOrNull { it.level == ValidationLevel.WARNING && it.message.contains("include_device_name: true") }
        assertNotNull(warn)
    }

    @Test
    fun testParsePayloadPhasesYaml() {
        val yaml = """
        devices:
          - id: parking_beacon
            name: "Parking Beacon"
            source: advertisement
            advertise:
              manufacturer_id: 861
              payload: "02050064{state:02X}{counter:02X}"
              counter_mode: persist
              payload_phases:
                - state: 65
                  duration: 200ms
                - state: 64
                  duration: 3s
            controls:
              - key: request_location
                type: button
                action: advertise
        """.trimIndent()

        val config = Yaml.default.decodeFromString(GatewayConfig.serializer(), yaml)
        val adv = config.devices[0].advertise
        assertNotNull(adv)
        assertEquals(AdvertiseCounterMode.persist, adv!!.counterMode)
        assertEquals(2, adv.payloadPhases.size)
        assertEquals(65, adv.payloadPhases[0].state)
        assertEquals("200ms", adv.payloadPhases[0].duration)
        assertEquals(64, adv.payloadPhases[1].state)
        assertEquals("3s", adv.payloadPhases[1].duration)

        val issues = ConfigValidator.validate(config)
        assertTrue("Expected no ERROR issues, but got: $issues", issues.none { it.level == ValidationLevel.ERROR })
    }

    @Test
    fun testStateTokenWithoutPhasesIsError() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.shared,
            advertise = AdvertiseConfig(
                manufacturerId = 861,
                payload = "02050064{state:02X}{counter:02X}",
            ),
            controls = listOf(ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)),
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val err = issues.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("{state}") }
        assertNotNull(err)
    }

    @Test
    fun testPayloadPhasesIgnoresRepeatInterval() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.shared,
            advertise = AdvertiseConfig(
                manufacturerId = 861,
                payload = "02050064{state:02X}{counter:02X}",
                repeatInterval = "1s",
                payloadPhases = listOf(
                    AdvertisePayloadPhase(state = 65, duration = "200ms"),
                    AdvertisePayloadPhase(state = 64, duration = "3s"),
                ),
            ),
            controls = listOf(ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)),
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        assertTrue(issues.none { it.level == ValidationLevel.ERROR })
        val warn = issues.firstOrNull { it.level == ValidationLevel.WARNING && it.message.contains("repeat_interval is ignored") }
        assertNotNull(warn)
    }

    @Test
    fun testPayloadPhaseInvalidDuration() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.shared,
            advertise = AdvertiseConfig(
                manufacturerId = 861,
                payload = "02050064{state:02X}{counter:02X}",
                payloadPhases = listOf(AdvertisePayloadPhase(state = 65, duration = "nope")),
            ),
            controls = listOf(ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)),
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val err = issues.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("duration") }
        assertNotNull(err)
    }

    @Test
    fun testParseLocalNameYaml() {
        val yaml = """
        devices:
          - id: parking_beacon
            name: "Parking Beacon"
            source: advertisement
            advertise:
              manufacturer_id: 861
              payload: "02050064{state:02X}{counter:02X}"
              local_name: "APT SmartKey"
              payload_phases:
                - state: 65
                  duration: 200ms
                - state: 64
                  duration: 3s
            controls:
              - key: request_location
                type: button
                action: advertise
        """.trimIndent()

        val config = Yaml.default.decodeFromString(GatewayConfig.serializer(), yaml)
        val adv = config.devices[0].advertise
        assertNotNull(adv)
        assertEquals("APT SmartKey", adv!!.localName)
        assertEquals("APT SmartKey", adv.resolvedLocalName())
        assertTrue(adv.includeNameInAdvertiseData())

        val issues = ConfigValidator.validate(config)
        assertTrue("Expected no ERROR issues, but got: $issues", issues.none { it.level == ValidationLevel.ERROR })
        val warn = issues.firstOrNull { it.level == ValidationLevel.WARNING && it.message.contains("local_name resets") }
        assertNotNull(warn)
    }

    @Test
    fun testLocalNameTooLongForLegacyAdv() {
        val device = DeviceConfig(
            id = "test",
            name = "Test",
            source = Source.advertisement,
            instanceMode = AdvertisementInstanceMode.shared,
            advertise = AdvertiseConfig(
                manufacturerId = 861,
                payload = "020500640000",
                localName = "THIS NAME IS WAY TOO LONG FOR BLE",
            ),
            controls = listOf(ControlConfig(key = "req", type = ControlType.button, action = ControlAction.advertise)),
        )
        val issues = ConfigValidator.validate(GatewayConfig(devices = listOf(device)))
        val err = issues.firstOrNull { it.level == ValidationLevel.ERROR && it.message.contains("31-byte legacy BLE limit") }
        assertNotNull(err)
    }
}
