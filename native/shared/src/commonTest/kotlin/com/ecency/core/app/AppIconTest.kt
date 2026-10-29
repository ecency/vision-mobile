package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppIconTest {
    @Test
    fun onlyIconsThatPointAlongTheTextMirror() {
        assertEquals(
            setOf(
                AppIcon.BACK, AppIcon.ARROW_FORWARD, AppIcon.CHEVRON_LEFT, AppIcon.CHEVRON_RIGHT, AppIcon.CHEVRON_DOUBLE_RIGHT,
                AppIcon.LOGOUT, AppIcon.SEND, AppIcon.BACKSPACE, AppIcon.REPLY,
            ),
            AppIcon.entries.filter { it.isDirectional }.toSet(),
        )
    }

    @Test
    fun theModelsCarryIconsByMeaning() {
        assertEquals(AppIcon.POWER, SideMenuItem.LOGOUT.icon)
        assertEquals(AppIcon.MEMBERS, CommunityManageAction.MEMBERS.icon)
        assertEquals(listOf(AppIcon.GIFT, AppIcon.BOOST, AppIcon.BOLT, AppIcon.MEGAPHONE, AppIcon.ROCKET), SpendOptions.ALL.map { it.icon })
        assertEquals(
            listOf(AppIcon.CHECKIN, AppIcon.EDIT, AppIcon.COMMENT, AppIcon.VOTE_UP, AppIcon.REBLOG, AppIcon.STAR),
            listOf("check-circle", "pencil", "comment", "chevron-up-circle", "repeat", "gift").map(AppIcon::quest),
        )
    }

    @Test
    fun theWavesMarkIsOneOpenStrokeAcrossTheGrid() {
        assertEquals(2, WavesMark.START.size)
        assertTrue(WavesMark.CURVES.all { it.size == 6 && it.all { v -> v in 0f..WavesMark.GRID } })
        assertEquals(WavesMark.GRID - WavesMark.START[0], WavesMark.CURVES.last()[4], "the stroke ends as far from the edge as it starts")
    }

    @Test
    fun theEcencyLogoMarkIsTheLogoGlyphAndItsHoleInsideTheViewBox() {
        val contours = EcencyLogoMark.CONTOURS
        assertEquals(2, contours.size, "the outline and the inner curl")
        for (c in contours) {
            assertEquals(0, (c.size - 2) % 6)
            val xs = c.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = c.filterIndexed { i, _ -> i % 2 == 1 }
            assertTrue(xs.all { it in EcencyLogoMark.VIEW_X..EcencyLogoMark.VIEW_X + EcencyLogoMark.VIEW_SIZE })
            assertTrue(ys.all { it in EcencyLogoMark.VIEW_Y..EcencyLogoMark.VIEW_Y + EcencyLogoMark.VIEW_SIZE })
            assertEquals(c[0], c[c.size - 2], 0.01f, "each contour closes on its start")
            assertEquals(c[1], c[c.size - 1], 0.01f)
        }
        assertEquals(listOf(88.27f, 105.71f, 84.71f, 59.25f), contours.flatMap { listOf(it[0], it[1]) })
    }

    @Test
    fun svgArcsAndSmoothCurvesBecomeCubics() {
        // a half circle of radius 1 from (0, 0) to (2, 0), then a smooth cubic mirroring the previous control
        val c = SvgPath.cubics("M0,0a1,1,0,0,0,2,0s1,1,2,0Z").single()
        assertEquals(2 + 6 * 3, c.size, "two quarter arcs and the S")
        assertEquals(1f, c[7], 0.01f, "the arc bulges down to y = 1 (sweep 0, y grows down)")
        assertEquals(2f, c[12], 0.01f); assertEquals(0f, c[13], 0.01f)
    }
}
