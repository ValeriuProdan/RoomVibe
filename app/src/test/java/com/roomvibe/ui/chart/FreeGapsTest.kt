package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the compare chart is allowed to park a floating device name. A gap that
 * isn't really free puts a name on top of a line, which is the whole thing this
 * is here to prevent.
 */
class FreeGapsTest {

    private fun gaps(blocked: List<ClosedFloatingPointRange<Float>>, top: Float = 0f, bottom: Float = 100f) =
        freeGaps(blocked, top, bottom).map { it.start to it.endInclusive }

    @Test fun nothingBlockedLeavesTheWholeBand() {
        assertEquals(listOf(0f to 100f), gaps(emptyList()))
    }

    @Test fun oneBandInTheMiddleLeavesRoomEitherSide() {
        assertEquals(listOf(0f to 40f, 60f to 100f), gaps(listOf(40f..60f)))
    }

    @Test fun overlappingBandsMergeInsteadOfSplitting() {
        // 40..60 and 50..70 are one obstruction, not two with a phantom gap between.
        assertEquals(listOf(0f to 40f, 70f to 100f), gaps(listOf(40f..60f, 50f..70f)))
    }

    @Test fun bandsAreMergedRegardlessOfOrder() {
        assertEquals(gaps(listOf(10f..20f, 40f..60f)), gaps(listOf(40f..60f, 10f..20f)))
    }

    @Test fun aFullyCoveredBandLeavesNowhereToGo() {
        assertTrue("a name must be dropped, not drawn over a line", gaps(listOf(-10f..110f)).isEmpty())
    }

    @Test fun blockedEdgesDontLeakOutsideTheBand() {
        gaps(listOf(-20f..30f, 80f..200f)).forEach { (lo, hi) ->
            assertTrue("gap $lo..$hi escapes the plot band", lo >= 0f && hi <= 100f)
        }
    }

    @Test fun noGapEverIntersectsAnObstruction() {
        val blocked = listOf(12f..25f, 24f..31f, 55f..58f, 70f..99f)
        for ((lo, hi) in gaps(blocked)) {
            for (b in blocked) {
                assertTrue(
                    "gap $lo..$hi overlaps blocked ${b.start}..${b.endInclusive}",
                    hi <= b.start || lo >= b.endInclusive
                )
            }
        }
    }
}
