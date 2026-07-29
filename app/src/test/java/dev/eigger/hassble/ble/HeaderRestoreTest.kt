package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderRestoreTest {

    private fun resolve(
        defaultCommands: List<String> = emptyList(),
        initCommands: List<String> = emptyList(),
        sensorPreCommands: List<List<String>> = emptyList(),
    ) = Elm327Source.resolveHeaderRestore(defaultCommands, initCommands, sensorPreCommands)

    @Test
    fun `no header switching sensors leaves behaviour untouched`() {
        // K-line 등에서 ATSH7DF를 밀어넣으면 안 되므로 빈 목록이어야 한다.
        val restore = resolve(
            initCommands = listOf("ATSP0"),
            sensorPreCommands = listOf(emptyList(), emptyList()),
        )
        assertTrue(restore.isEmpty())
    }

    @Test
    fun `header switching sensor triggers can default restore`() {
        val restore = resolve(
            initCommands = listOf("ATSP6"),
            sensorPreCommands = listOf(listOf("ATSH7C6"), emptyList()),
        )
        assertEquals(listOf("ATSH7DF"), restore)
    }

    @Test
    fun `init header wins over can default`() {
        val restore = resolve(
            initCommands = listOf("ATSP6", "ATSH7E0"),
            sensorPreCommands = listOf(listOf("ATSH7C6"), emptyList()),
        )
        assertEquals(listOf("ATSH7E0"), restore)
    }

    @Test
    fun `explicit default_commands wins over everything`() {
        val restore = resolve(
            defaultCommands = listOf("ATSH7E0", "ATCRA7E8"),
            initCommands = listOf("ATSH7DF"),
            sensorPreCommands = listOf(emptyList()),
        )
        assertEquals(listOf("ATSH7E0", "ATCRA7E8"), restore)
    }

    @Test
    fun `non header pre_commands do not trigger restore`() {
        val restore = resolve(sensorPreCommands = listOf(listOf("ATFCSD300000")))
        assertTrue(restore.isEmpty())
    }

    @Test
    fun `isSetHeader tolerates spacing and case`() {
        assertTrue(Elm327Source.isSetHeader("ATSH7C6"))
        assertTrue(Elm327Source.isSetHeader("at sh 7C6"))
        assertFalse(Elm327Source.isSetHeader("ATSP6"))
        assertFalse(Elm327Source.isSetHeader("ATFCSH7C6"))
    }
}
