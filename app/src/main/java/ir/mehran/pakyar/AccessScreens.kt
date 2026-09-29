@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ir.mehran.pakyar

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

@Composable fun LargeAction(text: String, click: () -> Unit, primary: Boolean = true, enabled: Boolean = true) {
    if (primary) Button(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(text) }
    else OutlinedButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text) }
}

@Composable fun BackBar(back: () -> Unit) {
    Surface {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            TextButton(onClick = back, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.back))
            }
        }
    }
}

@Composable fun MessageDialog(message: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, text = {
        Text(message, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyLarge)
    }, confirmButton = { LargeAction(stringResource(R.string.back), dismiss) })
}

@Composable fun AccessOnboarding(request: () -> Unit, skip: () -> Unit, folder: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.access_intro))
            Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.access_scope), style = MaterialTheme.typography.bodyMedium)
            LargeAction(stringResource(R.string.access_request), request)
            LargeAction(stringResource(R.string.choose_folder), folder, false)
            LargeAction(stringResource(R.string.skip), skip, false)
        }
    }
}

@Composable fun AccessScreen(access: AccessState, request: () -> Unit, folder: (String?) -> Unit,
    forget: (String) -> Unit, appSettings: () -> Unit, back: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { BackBar(back) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.access_manage), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }
            item {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (access.fullMedia) Icons.Default.CheckCircle else Icons.Default.Info, null)
                    Text(stringResource(access.label), style = MaterialTheme.typography.titleMedium)
                    if (access.revoked) Text(stringResource(R.string.access_revoked))
                    for ((label, available) in listOf(R.string.image to access.images, R.string.video to access.videos, R.string.audio to access.audio)) {
                        Text(stringResource(R.string.access_type_state, stringResource(label),
                            stringResource(if (available) R.string.access_available else R.string.access_unavailable)))
                    }
                    if (access.selectedVisual && (!access.images || !access.videos)) Text(stringResource(R.string.access_selected))
                    Text(stringResource(R.string.access_scope), style = MaterialTheme.typography.bodyMedium)
                } }
            }
            item { LargeAction(stringResource(R.string.media_permission), request) }
            item { LargeAction(stringResource(R.string.access_app_settings), appSettings, false) }
            item { Text(stringResource(R.string.authorized_folders), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(R.string.access_folder_hint)) }
            if (access.folders.isEmpty()) item { Text(stringResource(R.string.no_folders)) }
            items(access.folders, key = { it.uri }) { item ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if(item.readable) Icons.Default.Folder else Icons.Default.FolderOff, null)
                    Text(item.name, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content))
                    Text(stringResource(if (!item.readable) R.string.access_unavailable else if (!item.writable) R.string.folder_readonly else R.string.access_available))
                    LargeAction(stringResource(R.string.folder_reselect), { folder(item.uri) }, false)
                    LargeAction(stringResource(R.string.folder_forget), { forget(item.uri) }, false)
                } }
            }
            item { LargeAction(stringResource(R.string.choose_folder), { folder(null) }) }
        }
    }
}

@Composable fun SettingsScreen(access: AccessState, repo: StorageRepository, prefs: DisplayPreferences,
    manage: () -> Unit, scan: () -> Unit, appSettings: (String) -> Unit, back: () -> Unit) {
    var reset by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val installedVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { BackBar(back) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.headlineSmall) }
            item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if(access.fullMedia) Icons.Default.CheckCircle else Icons.Default.Info, null)
                Text(stringResource(access.label))
                LargeAction(stringResource(R.string.access_manage), manage)
            } } }
            item { LargeAction(stringResource(R.string.scan_storage), scan, false) }
            item { Text(stringResource(R.string.cache_help)) }
            item { LargeAction(stringResource(R.string.whatsapp_cache), { appSettings("com.whatsapp") }, false) }
            item { LargeAction(stringResource(R.string.open_app_settings, stringResource(R.string.source_whatsapp_business)), { appSettings("com.whatsapp.w4b") }, false) }
            item { LargeAction(stringResource(R.string.telegram_cache), { appSettings("org.telegram.messenger") }, false) }
            item { LargeAction(stringResource(R.string.open_app_settings, stringResource(R.string.source_telegram_x)), { appSettings("org.thunderdog.challegram") }, false) }
            item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.version, installedVersion))
                Text(stringResource(R.string.device_info, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL))
                Text(stringResource(R.string.privacy))
            } } }
            item { LargeAction(stringResource(R.string.reset_preferences), { reset = true }, false) }
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false },
        text = { Text(stringResource(R.string.reset_confirm)) },
        confirmButton = { LargeAction(stringResource(R.string.reset_preferences), {
            // Preferences only. Selected document trees and all user media remain untouched.
            prefs.reset(); prefs.onboarded = true; reset = false
        }) }, dismissButton = { LargeAction(stringResource(R.string.cancel), { reset = false }, false) })
}
