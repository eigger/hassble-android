package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import dev.eigger.hassble.decode.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GattPollConfigTest {
    private val yaml = """
        devices:
          - id: bm7
            name: "BM300 Pro"
            source: gatt_notify
            gatt:
              service_uuid: "fff0"
              notify_char_uuid: "fff4"
              write_char_uuid: "fff3"
              encryption: { type: aes128_cbc, key_hex: "6c656167656e64fffe30313030303040" }
              poll:
                command: "d1550700000000000000000000000000"
                interval: 60s
                response_timeout: 10s
              response_prefix: "d1550700"
            sensors:
              - { key: voltage, unit: "V", state_class: measurement, min_length: 9, decode: { offset: 7, length: 2, type: uint16, scale: 0.01 } }
              - { key: temperature, unit: "°C", state_class: measurement, min_length: 9, formula: "(1-2*d)*e" }
    """.trimIndent()

    private fun parse(text: String = yaml) = Yaml.default.decodeFromString(GatewayConfig.serializer(), text)

    private fun errors(c: GatewayConfig) =
        ConfigValidator.validate(c).filter { it.level == ValidationLevel.ERROR }

    @Test
    fun `parses new gatt fields with defaults`() {
        val g = parse().devices[0].gatt!!
        assertEquals("aes128_cbc", g.encryption!!.type)
        assertNull(g.encryption!!.ivHex)
        assertEquals("60s", g.poll!!.interval)
        assertTrue(g.poll!!.writeWithResponse)
        assertEquals("d1550700", g.responsePrefix)
        assertTrue(errors(parse()).isEmpty())
    }

    @Test
    fun `existing gatt config stays unchanged`() {
        val g = parse(
            """
            devices:
              - id: m
                name: M
                source: gatt_notify
                gatt: { service_uuid: ffe0, notify_char_uuid: ffe1 }
            """.trimIndent(),
        ).devices[0].gatt!!
        assertNull(g.encryption)
        assertNull(g.poll)
        assertNull(g.responsePrefix)
    }

    @Test
    fun `writer round trips new fields`() {
        val c = parse()
        val back = parse(ConfigYamlWriter.encode(c))
        assertEquals(c.devices[0].gatt, back.devices[0].gatt)
    }

    @Test
    fun `validator rejects bad key iv command interval and missing write char`() {
        fun withGatt(g: GattConfig) = GatewayConfig(devices = listOf(parse().devices[0].copy(gatt = g)))
        val ok = parse().devices[0].gatt!!
        assertTrue(errors(withGatt(ok.copy(encryption = ok.encryption!!.copy(keyHex = "abcd")))).isNotEmpty())
        assertTrue(errors(withGatt(ok.copy(encryption = ok.encryption!!.copy(ivHex = "00")))).isNotEmpty())
        assertTrue(errors(withGatt(ok.copy(poll = ok.poll!!.copy(command = "d155")))).isNotEmpty())
        assertTrue(errors(withGatt(ok.copy(poll = ok.poll!!.copy(command = "zz")))).isNotEmpty())
        assertTrue(errors(withGatt(ok.copy(poll = ok.poll!!.copy(interval = "soon")))).isNotEmpty())
        assertTrue(errors(withGatt(ok.copy(writeCharUuid = null))).isNotEmpty())
        // 암호화가 없으면 16바이트 배수 제약 없음
        assertTrue(errors(withGatt(ok.copy(encryption = null, poll = ok.poll!!.copy(command = "d155")))).isEmpty())
    }

    @Test
    fun `bm7 sample reply decodes to 23C and 11_95V`() {
        val bytes = Decoder.hexToBytes("d155070017010004ab00000000020000")!!
        val s = parse().devices[0].sensors
        assertEquals(11.95, Decoder.decodeStructured(bytes, s[0].decode!!) as Double, 1e-9)
        assertEquals(23.0, Decoder.evalFormula(s[1].formula!!, bytes), 1e-9)
        assertEquals(0L, (Decoder.decodeStructured(bytes, DecodeConfig(offset = 6, length = 1)) as Number).toLong())
        assertEquals(1L, (Decoder.decodeStructured(bytes, DecodeConfig(offset = 5, length = 1)) as Number).toLong())
    }

    @Test
    fun `negative temperature uses sign byte`() {
        val bytes = Decoder.hexToBytes("d155070105 00 5a 04ab".replace(" ", ""))!!
        assertEquals(-5.0, Decoder.evalFormula("(1-2*d)*e", bytes), 1e-9)
    }

    @Test
    fun `bundled bm templates parse and validate`() {
        val text = File("src/main/assets/templates.yaml").readText()
        val t = ConfigTemplates.fromYaml(text)
        for (id in listOf("ancel_bm7", "ancel_bm6")) {
            val d = t.find(id)!!.device
            assertNotNull(d.gatt!!.poll)
            assertNull(d.gatt!!.mac)
            assertEquals("fff0", d.gatt!!.serviceUuid)
            assertTrue(errors(GatewayConfig(devices = listOf(d))).isEmpty())
            assertFalse(d.sensors.isEmpty())
        }
        assertEquals("BM300 Pro", t.find("ancel_bm7")!!.device.name)
        assertEquals("BM6", t.find("ancel_bm6")!!.device.name)
    }
}
