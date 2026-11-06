package com.reverseassistant.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneActionTest {
    @Test
    fun parsesDragActionAndCoordinates() {
        val action = PhoneAction.parse(
            """{"phone_action":{"type":"drag","x":0.1,"y":0.2,"x2":0.8,"y2":0.9,"duration_ms":700}}"""
        )

        requireNotNull(action)
        assertEquals("drag", action.type)
        assertEquals(0.1f, action.x, 0.001f)
        assertEquals(0.8f, action.x2, 0.001f)
        assertEquals(700L, action.durationMs)
    }

    @Test
    fun clampsCoordinatesAndDurationToSafeBounds() {
        val action = PhoneAction.parse(
            """{"phone_action":{"type":"drag","x":-4,"y":3,"x2":2,"y2":-1,"duration_ms":9000}}"""
        )

        requireNotNull(action)
        assertEquals(0f, action.x, 0f)
        assertEquals(1f, action.y, 0f)
        assertEquals(1f, action.x2, 0f)
        assertEquals(0f, action.y2, 0f)
        assertEquals(1500L, action.durationMs)
    }

    @Test
    fun parsesBoundedMultiStepPlanInOrder() {
        val plan = PhoneAction.parsePlan(
            """{"phone_actions":[{"type":"tap","x":0.2,"y":0.3},{"type":"swipe","x":0.1,"y":0.8,"x2":0.9,"y2":0.2}]}"""
        )

        requireNotNull(plan)
        assertEquals(2, plan.size)
        assertEquals("tap", plan[0].type)
        assertEquals("swipe", plan[1].type)
    }

    @Test
    fun rejectsPlansLongerThanFiveSteps() {
        val actions = (1..6).joinToString(",") { """{"type":"tap"}""" }
        assertNull(PhoneAction.parsePlan("""{"phone_actions":[$actions]}"""))
    }

    @Test
    fun rejectsUnknownActionTypes() {
        assertNull(PhoneAction.parse("""{"phone_action":{"type":"execute_shell"}}"""))
    }
}
