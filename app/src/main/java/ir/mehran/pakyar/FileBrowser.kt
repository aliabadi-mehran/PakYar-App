@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ir.mehran.pakyar

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

enum class BrowserView(val label: Int, val width: Int) {
    LIST(R.string.view_list, 0), GRID_LARGE(R.string.view_large, 140),
    GRID_MEDIUM(R.string.view_medium, 108), GRID_COMPACT(R.string.view_compact, 80)
}

data class BrowserFilter(val category: String, val age: Int, val minSize: Long)

// Kept by App for navigation, but intentionally excluded from saved-instance state.
class BrowserSession {
    var selection by mutableStateOf(FileSelection(emptyList()))
    var totals by mutableStateOf(SelectionTotals())
    var files by mutableStateOf<List<CleanFile>>(emptyList(), referentialEqualityPolicy())
    var source by mutableStateOf<List<CleanFile>?>(null, referentialEqualityPolicy())
    var filter by mutableStateOf<BrowserFilter?>(null)
    var order by mutableIntStateOf(-1)
}

@Composable fun FileBrowser(all: List<CleanFile>, category: String, revision: Int, prefs: DisplayPreferences,
    session: BrowserSession, back: () -> Unit,
    delete: (DeletionPlan, List<CleanFile>, (DeletionSummary) -> Unit) -> Unit,
    removed: (Set<String>) -> Unit) {
    val mode = BrowserView.entries.firstOrNull { it.name == prefs.viewMode } ?: BrowserView.GRID_LARGE
    var age by rememberSaveable { mutableIntStateOf(if(category == "old") prefs.age else 0) }
    var minSize by rememberSaveable { mutableLongStateOf(prefs.size) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<DeletionPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var preparationError by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DeleteResult?>(null) }
    var previewFile by remember { mutableStateOf<CleanFile?>(null) }
    var previewError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val filter = BrowserFilter(category, age, minSize)
    val ready = session.source === all && session.filter == filter && session.order == sort
    val locked = busy || preparing || !ready
    val files = session.files
    val totals = session.totals
    LaunchedEffect(all, filter, sort) {
        // Returning through the navigation stack must not rebuild a live selection
        // concurrently with new taps, or replace it with an older worker snapshot.
        if (session.source === all && session.filter == filter && session.order == sort) return@LaunchedEffect
        try {
            val oldSelection = session.selection
            val sameFilter = session.filter == filter
            val (rows, selection) = withContext(Dispatchers.Default) {
                val rows = all.asSequence().filter { file ->
            val inCategory = when(category) {
                "large" -> file.kind == FileKind.VIDEO && FileRules.isLarge(file.kind, file.bytes)
                "wa" -> file.source in listOf(FileSource.WHATSAPP, FileSource.WHATSAPP_BUSINESS)
                "tg" -> file.source in listOf(FileSource.TELEGRAM, FileSource.TELEGRAM_X)
                else -> true
            }
            inCategory && (age == 0 || FileRules.isOld(file.modified, age)) && file.bytes >= minSize
                }.distinctBy { it.key }.toList().let { items -> when(sort) {
            1 -> items.sortedBy { if(it.modified > 0) it.modified else Long.MAX_VALUE }
            2 -> items.sortedByDescending { it.modified }
            else -> items.sortedByDescending { it.bytes }
                } }
                rows to oldSelection.rebase(rows, sameFilter)
            }
            session.files = rows
            session.selection = selection
            session.totals = selection.totals
            session.filter = filter
            session.order = sort
            session.source = all
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { preparationError = true }
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    fun toggle(file: CleanFile) {
        if (!busy && !preparing && ready) { session.selection.toggle(file.key); session.totals = session.selection.totals }
    }
    fun openPreview(file: CleanFile, external: Boolean = false) {
        if (busy || previewJob?.isActive == true) return
        previewJob = scope.launch {
            val readable = withContext(Dispatchers.IO) {
                try { context.contentResolver.openAssetFileDescriptor(Uri.parse(file.uri), "r")?.use { true } ?: false }
                catch (_: Exception) { false }
            }
            if (!readable) previewError = true
            else if (!external) previewFile = file
            else try {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { previewError = true }
        }
    }
    fun prepareDeletion() {
        if (busy || preparing || !ready || session.totals.count == 0) return
        preparing = true
        scope.launch {
            try { confirm = withContext(Dispatchers.Default) { session.selection.deletionPlan() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { preparationError = true }
            finally { preparing = false }
        }
    }
    BackHandler(enabled = busy || preparing) { /* Keep the active transaction visible. */ }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { BackBar { if (!busy && !preparing) back() } },
        bottomBar = {
            if(totals.count > 0 || busy || preparing) Surface(tonalElevation = 3.dp) {
                // The bottom bar owns the bottom/horizontal system insets exactly once.
                Column(Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (busy || preparing) {
                        Text(stringResource(if (busy) R.string.deleting else R.string.preparing_files))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else Button(onClick = ::prepareDeletion, enabled = !locked, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.delete_selected, totals.count, FileRules.formatBytes(totals.bytes)))
                    }
                }
            }
        }) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.files_found, files.size), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.selected_size, totals.count, FileRules.formatBytes(totals.bytes)),
                    style = MaterialTheme.typography.titleMedium)
                LargeAction(stringResource(R.string.view_current, stringResource(R.string.view_label), stringResource(mode.label)), { menu = 1 }, false, !locked)
                LargeAction(stringResource(R.string.view_current, stringResource(R.string.age_filter),
                    stringResource(when(age) { 7 -> R.string.older_7; 30 -> R.string.older_30; 90 -> R.string.older_90; else -> R.string.all_dates })),
                    { menu = 2 }, false, !locked)
                LargeAction(stringResource(R.string.view_current, stringResource(R.string.size_sort),
                    stringResource(when(sort) { 1 -> R.string.oldest; 2 -> R.string.newest; else -> R.string.largest })),
                    { menu = 3 }, false, !locked)
                LargeAction(stringResource(R.string.view_current, stringResource(R.string.size_filter),
                    stringResource(when(minSize) { 25 * FileRules.MB -> R.string.size_25; 100 * FileRules.MB -> R.string.size_100; else -> R.string.size_any })),
                    { menu = 4 }, false, !locked)
                Text(stringResource(R.string.filter_selection_hint), style = MaterialTheme.typography.bodyMedium)
                LargeAction(stringResource(R.string.select_all_filtered), {
                    if (!busy && !preparing && ready && !(session.selection.allMatchingSelected && session.selection.excludedIdCount == 0)) {
                        session.selection.selectAll(); session.totals = session.selection.totals
                    }
                }, false, !locked && files.isNotEmpty() && !(session.selection.allMatchingSelected && session.selection.excludedIdCount == 0))
                LargeAction(stringResource(R.string.clear_selection), {
                    if (!busy && !preparing && ready) { session.selection.clear(); session.totals = session.selection.totals }
                }, false, !locked && totals.count > 0)
                if (!ready) { Text(stringResource(R.string.preparing_files)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if(files.isEmpty()) Text(stringResource(R.string.no_files))
            }
        }
        if(mode == BrowserView.LIST) LazyColumn(contentModifier, state = listState, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "controls") { controls() }
            items(files, key = { it.key }) { file ->
                val checked = remember(file.key, totals, session.selection) { session.selection.isSelected(file.key) }
                DetailedFile(file, checked, revision, !locked, { toggle(file) }, { openPreview(file) })
            }
        } else {
            // More columns on wide screens; large system fonts reduce the count, never shrink text.
            BoxWithConstraints(contentModifier) {
            val baseWidth = if (mode == BrowserView.GRID_LARGE && maxWidth < 600.dp)
                ((maxWidth - 32.dp) / 2).coerceAtLeast(128.dp) else mode.width.dp
            val minimum = baseWidth * maxOf(1f, LocalDensity.current.fontScale)
            LazyVerticalGrid(GridCells.Adaptive(minimum), Modifier.fillMaxSize(), state = gridState,
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { controls() }
                items(files, key = { it.key }) { file ->
                    val checked = remember(file.key, totals, session.selection) { session.selection.isSelected(file.key) }
                    GridFile(file, checked, revision, mode, !locked, { toggle(file) }, { openPreview(file) })
                }
            }
            }
        }
    }
    if(menu != 0) {
        val options = when(menu) {
            1 -> BrowserView.entries.map { it.label }
            2 -> listOf(R.string.all_dates, R.string.older_7, R.string.older_30, R.string.older_90)
            3 -> listOf(R.string.largest, R.string.oldest, R.string.newest)
            else -> listOf(R.string.size_any, R.string.size_25, R.string.size_100)
        }
        val index = when(menu) {
            1 -> mode.ordinal; 2 -> listOf(0,7,30,90).indexOf(age)
            3 -> sort; else -> listOf(0L,25 * FileRules.MB,100 * FileRules.MB).indexOf(minSize)
        }
        AlertDialog(onDismissRequest = { menu = 0 }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, label ->
                    OutlinedButton(onClick = {
                        when(menu) {
                            1 -> prefs.viewMode = BrowserView.entries[i].name
                            2 -> { age = listOf(0,7,30,90)[i]; prefs.age = age }
                            3 -> sort = i
                            4 -> { minSize = listOf(0L,25 * FileRules.MB,100 * FileRules.MB)[i]; prefs.size = minSize }
                        }
                        menu = 0
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { this.selected = i == index }) {
                        if(i == index) Icon(Icons.Default.Check, null)
                        else if(menu == 1) Icon(if(i == 0) Icons.Default.ViewList else Icons.Default.GridView, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }, confirmButton = { LargeAction(stringResource(R.string.cancel), { menu = 0 }, false) })
    }
    confirm?.let { snapshot ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.selected_size, snapshot.totals.count, FileRules.formatBytes(snapshot.totals.bytes)), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.delete_warning))
            } },
            confirmButton = { Button(onClick = {
                if (!busy) {
                    confirm = null; busy = true
                    delete(snapshot, all) { outcome ->
                        busy = false; result = outcome.result
                        removed(outcome.removed)
                    }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_files)) } },
            dismissButton = { LargeAction(stringResource(R.string.cancel), { confirm = null }, false) })
    }
    result?.let { MessageDialog(stringResource(R.string.delete_result, it.deleted, it.failed, FileRules.formatBytes(it.freedBytes))) { result = null } }
    previewFile?.let { file ->
        AlertDialog(onDismissRequest = { previewFile = null }, title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { MediaThumbnail(file, revision, Modifier.fillMaxWidth().aspectRatio(1f), onUnavailable = { previewError = true }) },
            confirmButton = { LargeAction(stringResource(R.string.preview), { openPreview(file, external = true) }) },
            dismissButton = { LargeAction(stringResource(R.string.back), { previewFile = null }, false) })
    }
    if (previewError) MessageDialog(stringResource(R.string.preview_failed)) { previewError = false }
    if (preparationError) MessageDialog(stringResource(R.string.preparing_failed)) { preparationError = false }
}

@Composable private fun DetailedFile(file: CleanFile, checked: Boolean, revision: Int, enabled: Boolean, toggle: () -> Unit, preview: () -> Unit) {
    val state = stringResource(if(checked) R.string.selected_yes else R.string.selected_no)
    val previewLabel = stringResource(R.string.preview)
    Card(Modifier.fillMaxWidth().toggleable(checked, enabled && file.deletable, Role.Checkbox) { toggle() }
        .semantics(mergeDescendants = true) {
            stateDescription = state
            customActions = listOf(CustomAccessibilityAction(previewLabel) { preview(); true })
        },
        colors = CardDefaults.cardColors(containerColor = if(checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    MediaThumbnail(file, revision, Modifier.size(96.dp))
                    SelectionMark(checked, Modifier.align(Alignment.TopEnd))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(FileRules.formatBytes(file.bytes), style = MaterialTheme.typography.titleMedium)
                    Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                }
            }
            Text(sourceLabel(file.source), style = MaterialTheme.typography.bodyMedium)
            Text(FileRules.formatDate(file.modified), style = MaterialTheme.typography.bodyMedium)
            if(!file.deletable) Text(stringResource(R.string.folder_readonly))
            LargeAction(previewLabel, preview, false, enabled)
        }
    }
}

@Composable private fun GridFile(file: CleanFile, checked: Boolean, revision: Int, mode: BrowserView,
    enabled: Boolean, toggle: () -> Unit, preview: () -> Unit) {
    val state = stringResource(if(checked) R.string.selected_yes else R.string.selected_no)
    val previewLabel = stringResource(R.string.preview)
    Card(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .border(if(checked) 3.dp else 1.dp, if(checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
        .toggleable(checked, enabled && file.deletable, Role.Checkbox) { toggle() }
        .semantics(mergeDescendants = true) {
            stateDescription = state
            contentDescription = file.name
            customActions = listOf(CustomAccessibilityAction(previewLabel) { preview(); true })
        }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            MediaThumbnail(file, revision, Modifier.matchParentSize(), showVideoBadge = false)
            // Minimum height grows with font scale so size never overlaps the selection/video badges.
            Column(Modifier.fillMaxWidth().heightIn(min = maxWidth), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.fillMaxWidth().height(56.dp)) { SelectionMark(checked, Modifier.align(Alignment.TopEnd)) }
                val actionLabel = stringResource(if (file.kind == FileKind.VIDEO) R.string.play_file_action else R.string.preview_file_action, file.name)
                // This child owns its click, so preview never toggles the parent checkbox.
                IconButton(onClick = preview, enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(56.dp)
                        .background(Color.Black.copy(alpha = .85f), MaterialTheme.shapes.small)) {
                    Icon(if (file.kind == FileKind.VIDEO) Icons.Default.PlayArrow else Icons.Default.Visibility,
                        actionLabel, Modifier.size(48.dp), tint = Color.White)
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 32.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(FileRules.formatBytes(file.bytes), Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.9f)).padding(4.dp),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if(mode == BrowserView.GRID_LARGE) Text(file.name, Modifier.padding(8.dp), maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
    }
}
@Composable private fun SelectionMark(checked: Boolean, modifier: Modifier) {
    Box(modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Icon(if(checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, Modifier.size(30.dp))
    }
}
@Composable fun sourceLabel(source: FileSource): String = stringResource(when(source) {
    FileSource.WHATSAPP -> R.string.source_whatsapp; FileSource.WHATSAPP_BUSINESS -> R.string.source_whatsapp_business
    FileSource.TELEGRAM -> R.string.source_telegram; FileSource.TELEGRAM_X -> R.string.source_telegram_x
    FileSource.DOWNLOAD -> R.string.source_download; FileSource.OTHER -> R.string.source_other
})
