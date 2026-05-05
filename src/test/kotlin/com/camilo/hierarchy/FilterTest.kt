package com.camilo.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterTest {

    @Test
    fun testFilter() {
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2)
        )
        val filteredActual: Hierarchy = unfiltered.filter { nodeId -> nodeId % 3 != 0 }
        val filteredExpected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 5, 8, 10, 11),
            intArrayOf(0, 1, 1, 0, 1, 2)
        )
        assertEquals(filteredExpected.formatString(), filteredActual.formatString())
    }

    @Test
    fun testFilterEmptyHierarchy() {
        val unfiltered: Hierarchy = ArrayBasedHierarchy(IntArray(0), IntArray(0))
        val filtered: Hierarchy = unfiltered.filter { true }
        assertEquals(0, filtered.size)
    }

    @Test
    fun testFilterKeepsEverything() {
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(0, 1, 2, 1, 0)
        )
        val filtered: Hierarchy = unfiltered.filter { true }
        assertEquals(unfiltered.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterKeepsNothing() {
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(0, 1, 2, 1, 0)
        )
        val filtered: Hierarchy = unfiltered.filter { false }
        assertEquals(0, filtered.size)
        assertEquals("[]", filtered.formatString())
    }

    @Test
    fun testFilterFailingRootRemovesEntireSubtree() {
        // Root 1 fails the predicate, so its entire subtree (2, 3, 4, 5) must be excluded,
        // even though those node IDs would individually pass.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5, 6, 7),
            intArrayOf(0, 1, 2, 1, 0, 1, 1)
        )
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId != 1 }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(5, 6, 7),
            intArrayOf(0, 1, 1)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterFailingIntermediateRemovesDescendants() {
        // Node 2 fails: its descendants 3, 4 must be excluded even though they pass individually.
        // Sibling 5 of failing 2 should still appear under 1.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(0, 1, 2, 3, 1)
        )
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId != 2 }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 5),
            intArrayOf(0, 1)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterSingleNode() {
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(42),
            intArrayOf(0)
        )
        assertEquals("[42:0]", unfiltered.filter { true }.formatString())
        assertEquals("[]", unfiltered.filter { false }.formatString())
    }

    @Test
    fun testFilterMultipleRoots() {
        // Forest with three roots; the middle root fails.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5, 6),
            intArrayOf(0, 1, 0, 1, 0, 1)
        )
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId != 3 }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 5, 6),
            intArrayOf(0, 1, 0, 1)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterDeepChain() {
        // A single deep chain: removing any node prunes everything below it.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(0, 1, 2, 3, 4)
        )
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId < 4 }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3),
            intArrayOf(0, 1, 2)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterOnlyLeavesFail() {
        // Predicate fails only for leaves; internal structure remains.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(0, 1, 2, 1, 0)
        )
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId !in setOf(3, 4) }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 5),
            intArrayOf(0, 1, 0)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterPreservesSiblingOrder() {
        // Removing a middle sibling must not reorder the remaining siblings.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4),
            intArrayOf(0, 1, 1, 1)
        )
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId != 3 }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 4),
            intArrayOf(0, 1, 1)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }

    @Test
    fun testFilterRootFailsButDescendantsPassIndividually() {
        // Confirms the "and all of its ancestors pass it as well" rule for the spec example.
        val unfiltered: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2)
        )
        // Only node 1 fails; the entire first tree disappears.
        val filtered: Hierarchy = unfiltered.filter { nodeId -> nodeId != 1 }
        val expected: Hierarchy = ArrayBasedHierarchy(
            intArrayOf(6, 7, 8, 9, 10, 11),
            intArrayOf(0, 1, 0, 1, 1, 2)
        )
        assertEquals(expected.formatString(), filtered.formatString())
    }
}
