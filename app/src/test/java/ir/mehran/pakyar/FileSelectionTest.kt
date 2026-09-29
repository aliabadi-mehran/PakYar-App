package ir.mehran.pakyar

import org.junit.Assert.*
import org.junit.Test

class FileSelectionTest {
    private fun file(id: Int, bytes: Long = id.toLong() + 1, deletable: Boolean = true) = CleanFile(
        "$id", "content://media/external/images/media/$id", "$id.jpg", "image/jpeg", bytes,
        1000L, "Pictures", FileSource.OTHER, FileKind.IMAGE, deletable = deletable)
    private fun files(count: Int) = List(count) { file(it, 4_000_000_000L + it) }

    @Test fun selectAllTenThousandUsesOneFlagAndNoSelectedIds() {
        val data = files(10_000)
        val model = FileSelection(data)
        model.selectAll()
        assertTrue(model.allMatchingSelected)
        assertEquals(0, model.explicitIdCount)
        assertEquals(0, model.excludedIdCount)
        assertEquals(SelectionTotals(10_000, data.sumOf { it.bytes }), model.totals)
        assertTrue(model.isSelected("9999"))
        model.selectAll()
        assertEquals(10_000, model.totals.count)
    }

    @Test fun twentyThousandFilesAndExceptionsKeepAccurateLongTotals() {
        val data = files(20_000)
        val model = FileSelection(data)
        model.selectAll()
        model.toggle("0"); model.toggle("19999")
        assertFalse(model.isSelected("0"))
        assertEquals(2, model.excludedIdCount)
        assertEquals(SelectionTotals(19_998, data.sumOf { it.bytes } - data.first().bytes - data.last().bytes), model.totals)
        model.toggle("0")
        assertEquals(19_999, model.totals.count)
        assertEquals(1, model.excludedIdCount)
    }

    @Test fun clearSelectionRemovesFlagAndExceptions() {
        val model = FileSelection(files(10_000))
        model.selectAll(); model.toggle("3"); model.clear()
        assertFalse(model.allMatchingSelected)
        assertEquals(SelectionTotals(), model.totals)
        assertEquals(0, model.excludedIdCount)
        assertEquals(0, model.explicitIdCount)
        assertFalse(model.isSelected("2"))
        model.toggle("2"); model.clear()
        assertEquals(SelectionTotals(), model.totals)
    }

    @Test fun filteredSelectAllNeverResolvesHiddenOrReadonlyFiles() {
        val data = List(100) { file(it, deletable = it != 8) }
        val model = FileSelection(data.filter { it.key.toInt() % 2 == 0 })
        model.selectAll()
        val resolved = model.deletionPlan().resolveBatches(data).flatten().toList()
        assertEquals(49, resolved.size)
        assertTrue(resolved.all { it.key.toInt() % 2 == 0 && it.deletable })
        assertEquals(resolved.sumOf { it.bytes }, model.totals.bytes)
        assertFalse(model.isSelected("1"))
    }

    @Test fun filterChangeClearsSelectionButSortingDoesNot() {
        val data = files(100)
        val model = FileSelection(data)
        model.toggle("2"); model.toggle("7")
        val sorted = model.rebase(data.reversed(), sameFilter = true)
        assertEquals(model.totals, sorted.totals)
        assertTrue(sorted.isSelected("7"))
        val changedFilter = sorted.rebase(data.take(10), sameFilter = false)
        assertEquals(SelectionTotals(), changedFilter.totals)
    }

    @Test fun changedDatasetPrunesMissingIdsAndRefreshesSizes() {
        val model = FileSelection(listOf(file(1), file(2), file(3)))
        model.selectAll(); model.toggle("2")
        val next = model.rebase(listOf(file(2), file(3, 999L), file(4, 500L)), sameFilter = true)
        assertEquals(SelectionTotals(2, 1499L), next.totals)
        assertFalse(next.isSelected("1")); assertFalse(next.isSelected("2"))
        assertTrue(next.isSelected("4"))
        val explicit = FileSelection(files(5))
        explicit.toggle("1"); explicit.toggle("4")
        assertEquals(1, explicit.rebase(files(3), true).totals.count)
    }

    @Test fun duplicateIdsCannotDuplicateTotalsOrDeletionTargets() {
        val data = listOf(file(1), file(1), file(2), file(2))
        val model = FileSelection(data)
        model.toggle("1"); model.toggle("1"); model.toggle("1")
        assertEquals(1, model.explicitIdCount)
        model.selectAll()
        assertEquals(SelectionTotals(2, 5L), model.totals)
        assertEquals(listOf("1", "2"), model.deletionPlan().resolveBatches(data).flatten().map { it.key }.toList())
    }

    @Test fun deletionResolutionIsBoundedInMemoryWithoutAnyBundle() {
        val data = files(20_000)
        val model = FileSelection(data)
        model.selectAll(); model.toggle("42")
        val plan = model.deletionPlan()
        model.clear() // Frozen confirmation is independent of later UI state.
        var count = 0
        var total = 0L
        plan.resolveBatches(data, 64).forEach { batch ->
            assertTrue(batch.size in 1..64)
            assertTrue(batch.none { it.key == "42" })
            count += batch.size; total += batch.sumOf { it.bytes }
        }
        assertEquals(plan.totals, SelectionTotals(count, total))
        assertEquals(19_999, count)
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(DeletionPlan::class.java))
    }

    @Test fun confirmationDoesNotExpandToNewOrChangedFiles() {
        val original = listOf(file(1), file(2), file(3))
        val model = FileSelection(original)
        model.selectAll()
        val plan = model.deletionPlan()
        val latest = listOf(file(2, 999), file(3), file(4))
        assertEquals(listOf("3"), plan.resolveBatches(latest).flatten().map { it.key }.toList())
    }

    @Test fun unknownAndReadonlyIdsCannotBecomeSelected() {
        val model = FileSelection(listOf(file(1, deletable = false)))
        model.toggle("missing"); model.toggle("1"); model.selectAll()
        assertEquals(SelectionTotals(), model.totals)
    }
}
