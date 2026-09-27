package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceIdsTest {

    @Test
    fun `fixed and dynamic MAC instances belong to the profile`() {
        assertTrue(InstanceIds.belongsTo("car", "car"))
        assertTrue(InstanceIds.belongsTo("car_A4C138112233", "car"))
        assertTrue(InstanceIds.belongsTo("car_a4c138112233", "car"))
    }

    @Test
    fun `a profile whose id starts with another id is not matched`() {
        // `car`를 지우거나 필터를 초기화할 때 `car_park` 것까지 건드리면 안 된다.
        assertFalse(InstanceIds.belongsTo("car_park", "car"))
        assertFalse(InstanceIds.belongsTo("car_park_A4C138112233", "car"))
        assertFalse(InstanceIds.belongsTo("car_A4C1381122", "car"))
    }

    @Test
    fun `of always includes the profile id and only its own instances`() {
        val known = listOf("car_A4C138112233", "car_park", "car_park_A4C138112233", "door")
        assertEquals(setOf("car", "car_A4C138112233"), InstanceIds.of("car", known))
        assertEquals(setOf("car_park", "car_park_A4C138112233"), InstanceIds.of("car_park", known))
        assertEquals(setOf("mytown_parking"), InstanceIds.of("mytown_parking", emptyList()))
    }
}
