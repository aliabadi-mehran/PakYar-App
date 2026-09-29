@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ir.mehran.pakyar

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.coroutines.resume

data class DeletionSummary(val result: DeleteResult, val removed: Set<String>)

class MainActivity : ComponentActivity() {
    private lateinit var repo: StorageRepository
    private lateinit var preferences: DisplayPreferences
    private var revision by mutableIntStateOf(0)
    private var message by mutableIntStateOf(0)
    private var afterDelete: ((Boolean) -> Unit)? = null
    private var deleting = false
    private var replacingFolder: String? = null
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try { contentResolver.takePersistableUriPermission(uri, flags) }
                catch (_: SecurityException) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                repo.addFolder(uri)
                replacingFolder?.takeIf { it != uri.toString() }?.let { forgetFolder(it) }
            } catch (_: Exception) { message = R.string.folder_failed }
        }
        replacingFolder = null
        revision++
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    private val deletion = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val callback = afterDelete
        afterDelete = null
        callback?.invoke(it.resultCode == Activity.RESULT_OK)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        repo = StorageRepository(this)
        preferences = DisplayPreferences(this)
        setContent {
            PakYarTheme {
                App(repo, preferences, revision, message, { message = 0 }, ::askMedia,
                    { old -> replacingFolder = old; folder.launch(old?.let(Uri::parse)) },
                    ::forgetFolder, ::openSettings, ::delete, ::finish)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        ThumbnailCache.clear()
        revision++
    }
    private fun askMedia() { permissions.launch(mediaPermissions()) }
    private fun forgetFolder(raw: String) {
        val uri = Uri.parse(raw)
        contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
            val flags = (if(it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if(it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        repo.removeFolder(raw)
        revision++
    }
    private fun openSettings(pkg: String) {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
        catch (_: Exception) { message = R.string.settings_failed }
    }
    private suspend fun approval(sender: android.content.IntentSender): Boolean = suspendCancellableCoroutine { continuation ->
        afterDelete = { if (continuation.isActive) continuation.resume(it) }
        continuation.invokeOnCancellation { afterDelete = null }
        try { deletion.launch(IntentSenderRequest.Builder(sender).build()) }
        catch (_: Exception) { afterDelete = null; continuation.resume(false) }
    }
    private fun delete(plan: DeletionPlan, files: List<CleanFile>, done: (DeletionSummary) -> Unit) {
        if (deleting) return
        deleting = true
        lifecycleScope.launch {
            val removed = mutableSetOf<String>()
            var freed = 0L
            try {
                withContext(Dispatchers.IO) {
                    for (batch in plan.resolveBatches(files, batchSize = 64)) {
                        for (file in batch) {
                            ensureActive()
                            try {
                                val size = repo.currentSize(file)
                                // Changed since confirmation: leave it untouched.
                                if (size != null && size != file.bytes) continue
                                if (repo.exists(file) != true) continue
                                var accepted = false
                                if (file.saf) {
                                    accepted = repo.deleteSaf(listOf(file)).deleted == 1
                                } else {
                                    try { accepted = contentResolver.delete(Uri.parse(file.uri), null, null) > 0 }
                                    catch (e: SecurityException) {
                                        if (Build.VERSION.SDK_INT >= 30) {
                                            // Never send thousands of URIs through Binder.
                                            val sender = MediaStore.createDeleteRequest(contentResolver, listOf(Uri.parse(file.uri))).intentSender
                                            accepted = withContext(Dispatchers.Main) { approval(sender) } && repo.exists(file) == false
                                        } else if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                                            if (withContext(Dispatchers.Main) { approval(e.userAction.actionIntent.intentSender) })
                                                accepted = contentResolver.delete(Uri.parse(file.uri), null, null) > 0
                                        }
                                    }
                                }
                                // Successful provider deletion is authoritative; SAF providers may
                                // throw FileNotFoundException instead of returning an empty cursor.
                                if (accepted) { removed += file.key; freed += size ?: 0L }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Keep this failed item; continue the batch. */ }
                        }
                    }
                }
            } finally {
                deleting = false
                ThumbnailCache.clear()
                done(DeletionSummary(DeleteResult(removed.size, plan.totals.count - removed.size, freed), removed))
            }
        }
    }
}

@Composable private fun App(
    repo: StorageRepository, prefs: DisplayPreferences, revision: Int, message: Int, clearMessage: () -> Unit,
    permission: () -> Unit, folder: (String?) -> Unit, forgetFolder: (String) -> Unit,
    appSettings: (String) -> Unit, delete: (DeletionPlan, List<CleanFile>, (DeletionSummary) -> Unit) -> Unit, exit: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var files by remember { mutableStateOf(emptyList<CleanFile>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableIntStateOf(0) }
    var scanCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var stack by rememberSaveable { mutableStateOf(listOf("home")) }
    var onboarding by rememberSaveable { mutableStateOf(!prefs.onboarded) }
    var exitDialog by rememberSaveable { mutableStateOf(false) }
    var access by remember { mutableStateOf(AccessState()) }
    val holder = rememberSaveableStateHolder()
    val browserSessions = remember { mutableMapOf<String, BrowserSession>() }
    fun push(screen: String) { stack = stack + screen }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) else exitDialog = true }
    BackHandler { back() }
    LaunchedEffect(revision) {
        access = withContext(Dispatchers.IO) { readAccess(context, repo, prefs, revision) }
    }
    fun runScan() {
        if (scanning) return
        scanning = true
        scanMessage = 0
        scanJob = scope.launch {
            try {
                val task = currentCoroutineContext()
                files = withContext(Dispatchers.IO) {
                    repo.scan { count ->
                        task.ensureActive()
                        if (count % 40 == 0) scope.launch { scanCount = count }
                    }
                }
            } catch (e: CancellationException) { scanMessage = R.string.scan_cancelled }
            catch (_: Exception) { scanMessage = R.string.scan_failed }
            catch (_: OutOfMemoryError) { ThumbnailCache.clear(); scanMessage = R.string.scan_failed }
            finally { scanning = false }
        }
    }
    fun completeOnboarding(request: Boolean) {
        prefs.onboarded = true
        onboarding = false
        if (request) permission()
    }
    val screen = stack.last()
    if (onboarding) {
        AccessOnboarding({ completeOnboarding(true) }, { completeOnboarding(false) },
            { completeOnboarding(false); folder(null) })
    } else holder.SaveableStateProvider(screen) {
        when {
            screen.startsWith("list:") -> FileBrowser(files, screen.substringAfter(":"), access.revision, prefs,
                browserSessions.getOrPut(screen) { BrowserSession() }, ::back, delete, { removed ->
                    scope.launch { files = withContext(Dispatchers.Default) { files.filterNot { it.key in removed } } }
                })
            screen == "access" -> AccessScreen(access, permission, folder, forgetFolder,
                { appSettings(context.packageName) }, ::back)
            screen == "settings" -> SettingsScreen(access, repo, prefs, { push("access") },
                { runScan(); stack = listOf("home") }, appSettings, ::back)
            else -> {
                Home(files, scanning, ::runScan, access, { push("list:$it") }, { push("settings") })
            }
        }
    }
    if (scanning) AlertDialog(
        onDismissRequest = { scanJob?.cancel() },
        title = { Text(stringResource(R.string.scanning)) },
        text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.scan_progress, scanCount)) } },
        confirmButton = { LargeAction(stringResource(R.string.cancel), { scanJob?.cancel() }, primary = false) })
    val visibleMessage = if (message != 0) message else scanMessage
    if (visibleMessage != 0) MessageDialog(stringResource(visibleMessage)) { clearMessage(); scanMessage = 0 }
    if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false },
        title = { Text(stringResource(R.string.exit_title)) },
        confirmButton = { LargeAction(stringResource(R.string.exit_stay), { exitDialog = false }) },
        dismissButton = { LargeAction(stringResource(R.string.exit_action), exit, primary = false) })
}

@Composable private fun Home(files: List<CleanFile>, scanning: Boolean, scan: () -> Unit, access: AccessState, open: (String) -> Unit, settings: () -> Unit) {
    val stat = remember { StatFs(Environment.getDataDirectory().path) }; val total = stat.totalBytes; val free = stat.availableBytes; val used = total - free; val scanningLabel = stringResource(R.string.scanning)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        Surface { Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            TextButton(onClick = settings, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_about))
            }
        } }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall) }
            item { Text(stringResource(R.string.home_subtitle)) }
            item { StorageSummary(total, used, free, files.filter { it.deletable }.sumOf { it.bytes }) }
            item { Button(onClick = scan, enabled = !scanning, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(12.dp)); Text(if(scanning) stringResource(R.string.scanning) else stringResource(R.string.scan_storage)) } }
            if(scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = scanningLabel }) }
            item { Text(stringResource(R.string.categories_title), style = MaterialTheme.typography.titleLarge) }
            item { CategoryGrid(files, open) }
            if (!access.fullMedia || access.revoked) item {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, null)
                    Text(stringResource(if (access.revoked) R.string.access_revoked else access.label))
                    LargeAction(stringResource(R.string.access_manage), settings, primary = false)
                } }
            }
        }
    }
}

@Composable private fun StorageSummary(total: Long, used: Long, free: Long, deletable: Long) { val storageLabel=stringResource(R.string.storage); ElevatedCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = storageLabel }) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Storage, null, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Text(storageLabel, style = MaterialTheme.typography.titleLarge) }; LinearProgressIndicator(progress = { if(total == 0L) 0f else used.toFloat()/total }, modifier = Modifier.fillMaxWidth().height(12.dp)); Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Value(stringResource(R.string.used), FileRules.formatBytes(used)); Value(stringResource(R.string.free), FileRules.formatBytes(free)) }; Text(stringResource(R.string.deletable_size, FileRules.formatBytes(deletable)), style = MaterialTheme.typography.titleMedium) } } }
@Composable private fun Value(label: String, value: String) = Column { Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.titleMedium) }
@Composable private fun CategoryGrid(files: List<CleanFile>, open: (String) -> Unit) { val data = listOf(Triple("large", R.string.large_videos, Icons.Default.VideoLibrary), Triple("old", R.string.old_files, Icons.Default.History), Triple("wa", R.string.whatsapp_files, Icons.Default.Chat), Triple("tg", R.string.telegram_files, Icons.Default.Send)); Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { data.chunked(if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) 1 else 2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { (id, title, icon) -> val selected = filter(files,id); ElevatedCard(Modifier.weight(1f).heightIn(min=150.dp).clickable(role=Role.Button) { open(id) }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, modifier=Modifier.size(32.dp)); Text(stringResource(title), style=MaterialTheme.typography.titleMedium); Text(stringResource(R.string.category_count, selected.size, FileRules.formatBytes(selected.sumOf { it.bytes })), style=MaterialTheme.typography.bodyMedium) } } } } } } }
private fun filter(all: List<CleanFile>, id: String) = when(id) { "large" -> all.filter { it.kind == FileKind.VIDEO && FileRules.isLarge(it.kind,it.bytes) }; "old" -> all.filter { FileRules.isOld(it.modified,30) }; "wa" -> all.filter { it.source==FileSource.WHATSAPP || it.source==FileSource.WHATSAPP_BUSINESS }; else -> all.filter { it.source==FileSource.TELEGRAM || it.source==FileSource.TELEGRAM_X } }
