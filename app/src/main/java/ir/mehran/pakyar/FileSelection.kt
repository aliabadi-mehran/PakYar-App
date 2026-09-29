package ir.mehran.pakyar

data class SelectionTotals(val count: Int = 0, val bytes: Long = 0)

/** In-memory only: never Parcelable, Serializable, saveable, or persisted in preferences.
 * One indexed dataset, one selection flag, and hash sets of exceptions. UI owns mutations.
 * Build/rebase/plan resolution run on a worker; Select All and membership are O(1).
 */
class FileSelection(files: Iterable<CleanFile>) {
    private val index = files.filter { it.deletable }.associateBy { it.key }
    private val allTotals = SelectionTotals(index.size, index.values.sumOf { it.bytes.coerceAtLeast(0) })
    var allMatchingSelected: Boolean = false
        private set
    private var included = HashSet<String>()
    private var excluded = HashSet<String>()
    var totals = SelectionTotals()
        private set
    val explicitIdCount get() = included.size
    val excludedIdCount get() = excluded.size

    fun isSelected(id: String): Boolean = index.containsKey(id) &&
        if (allMatchingSelected) id !in excluded else id in included

    fun selectAll() {
        allMatchingSelected = true
        included = HashSet()
        excluded = HashSet()
        totals = allTotals
    }

    fun clear() {
        allMatchingSelected = false
        included = HashSet()
        excluded = HashSet()
        totals = SelectionTotals()
    }

    fun toggle(id: String) {
        val file = index[id] ?: return
        val wasSelected = isSelected(id)
        if (allMatchingSelected) {
            if (wasSelected) excluded.add(id) else excluded.remove(id)
        } else {
            if (wasSelected) included.remove(id) else included.add(id)
        }
        val sign = if (wasSelected) -1 else 1
        totals = SelectionTotals(totals.count + sign, totals.bytes + sign * file.bytes.coerceAtLeast(0))
    }

    /** Changing the filter clears selection; sorting/view changes do not. New matching
     * files join Select All on dataset refresh, but never join an already confirmed plan. */
    fun rebase(files: Iterable<CleanFile>, sameFilter: Boolean): FileSelection {
        val next = FileSelection(files)
        if (!sameFilter) return next
        if (allMatchingSelected) {
            next.selectAll()
            excluded.forEach { if (next.index.containsKey(it)) next.toggle(it) }
        } else included.forEach { if (next.index.containsKey(it)) next.toggle(it) }
        return next
    }

    fun deletionPlan() = DeletionPlan(index, allMatchingSelected, included.toHashSet(), excluded.toHashSet(), totals)
}

/** Frozen confirmation scope; resolution only returns still-current, unchanged records.
 * Batches stay in memory and never enter a Bundle or an unbounded platform request. */
class DeletionPlan internal constructor(
    private val candidates: Map<String, CleanFile>,
    private val all: Boolean,
    private val included: Set<String>,
    private val excluded: Set<String>,
    val totals: SelectionTotals
) {
    fun resolveBatches(currentFiles: Iterable<CleanFile>, batchSize: Int = 64): Sequence<List<CleanFile>> = sequence {
        require(batchSize in 1..256)
        val seen = HashSet<String>()
        var batch = ArrayList<CleanFile>(batchSize)
        for (file in currentFiles) {
            val original = candidates[file.key] ?: continue
            if (!(if (all) file.key !in excluded else file.key in included)) continue
            if (!file.deletable || original.uri != file.uri || original.bytes != file.bytes || original.modified != file.modified) continue
            if (!seen.add(file.key)) continue
            batch.add(file)
            if (batch.size == batchSize) {
                yield(batch)
                batch = ArrayList(batchSize)
            }
        }
        if (batch.isNotEmpty()) yield(batch)
    }
}

/** Old releases only wrote the view key on explicit selection. A missing key is the
 * old automatic LIST default; an unmarked stored LIST must therefore be preserved. */
object ViewModeMigration {
    fun resolve(stored: String?, explicitlyChosen: Boolean?): String = when {
        stored == null || explicitlyChosen == false -> "GRID_LARGE"
        stored == "LARGE" -> "GRID_LARGE"
        stored == "MEDIUM" -> "GRID_MEDIUM"
        stored == "COMPACT" -> "GRID_COMPACT"
        stored in setOf("LIST", "GRID_LARGE", "GRID_MEDIUM", "GRID_COMPACT") -> stored
        else -> "GRID_LARGE"
    }
}
