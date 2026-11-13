package md.sime.opensime.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageEntranceTest {
    @Test fun firstCardsStartWithAShortStagger() {
        assertEquals(0L, pageEntranceDelay(0, 0))
        assertEquals(30L, pageEntranceDelay(1, 0))
        assertEquals(270L, pageEntranceDelay(9, 0))
    }

    @Test fun gradeDetailsFollowTheirCardWithoutAnUnboundedDelay() {
        assertEquals(45L, pageEntranceDelay(0, 0, 0))
        assertEquals(93L, pageEntranceDelay(1, 0, 1))
        assertEquals(441L, pageEntranceDelay(9, 0, 15))
        assertEquals(0L, pageEntranceDelay(0, 200, 2))
        assertNull(pageEntranceDelay(0, 0, 16))
        assertNull(pageEntranceDelay(0, 360, 1))
    }

    @Test fun lateCompositionUsesTheOriginalPageTimeline() {
        assertEquals(20L, pageEntranceDelay(4, 100))
        assertEquals(0L, pageEntranceDelay(1, 100))
    }

    @Test fun scrollingAndRefreshesDoNotStartAnotherEntrance() {
        assertNull(pageEntranceDelay(0, 360))
        assertNull(pageEntranceDelay(2, 10_000))
        assertNull(pageEntranceDelay(10, 0))
        assertNull(pageEntranceDelay(-1, 0))
    }
}
