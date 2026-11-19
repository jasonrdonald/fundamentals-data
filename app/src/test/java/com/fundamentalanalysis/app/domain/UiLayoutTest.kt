package com.fundamentalanalysis.app.domain

import com.fundamentalanalysis.app.domain.model.Display
import com.fundamentalanalysis.app.domain.model.UiComponent
import com.fundamentalanalysis.app.domain.model.UiLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLayoutTest {
    @Test fun `defaults show everything`() {
        val l = UiLayout()
        assertTrue(UiComponent.entries.all { l.shows(it) && !l.simple(it) })
        assertEquals(0, l.customised)
        assertEquals("", l.encode())
    }

    @Test fun `choices round-trip through storage, defaults are not stored`() {
        val l = UiLayout(mapOf(
            UiComponent.NAV_REPORTS to Display.HIDE,
            UiComponent.PORTFOLIO_SUMMARY to Display.SIMPLE,
            UiComponent.OVERVIEW_WATCHLIST to Display.SHOW,
        ))
        val text = l.encode()
        assertEquals("NAV_REPORTS=HIDE;PORTFOLIO_SUMMARY=SIMPLE", text)
        val back = UiLayout.decode(text)
        assertFalse(back.shows(UiComponent.NAV_REPORTS))
        assertTrue(back.simple(UiComponent.PORTFOLIO_SUMMARY))
        assertEquals(2, back.customised)
    }

    @Test fun `unsupported or unknown entries fall back to the default`() {
        // Momentum has no simplified form; OLD_THING no longer exists; garbage is ignored.
        val l = UiLayout.decode("OVERVIEW_MOMENTUM=SIMPLE;OLD_THING=HIDE;nonsense;STOCK_FACTORS=SIMPLE")
        assertEquals(Display.SHOW, l.display(UiComponent.OVERVIEW_MOMENTUM))
        assertEquals(Display.SIMPLE, l.display(UiComponent.STOCK_FACTORS))
        assertEquals(1, l.customised)
    }

    @Test fun `every simplified component says what simplified means`() {
        UiComponent.entries.filter { Display.SIMPLE in it.modes }.forEach { assertTrue(it.name, !it.simplified.isNullOrBlank()) }
        UiComponent.entries.forEach { assertTrue(it.name, Display.SHOW in it.modes && Display.HIDE in it.modes) }
    }
}
