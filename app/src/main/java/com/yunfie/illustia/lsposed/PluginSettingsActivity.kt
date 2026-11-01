package com.yunfie.illustia.lsposed

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor

class PluginSettingsActivity : ComponentActivity() {
    companion object {
        const val REQUEST_CODE = 0x504c
        const val EXTRA_PLUGINS = "com.yunfie.illustia.lsposed.PLUGINS"
        const val EXTRA_RUNTIME_STATUS = "com.yunfie.illustia.lsposed.RUNTIME_STATUS"
        const val EXTRA_PLUGIN_SETTINGS = "com.yunfie.illustia.lsposed.PLUGIN_SETTINGS"
        const val EXTRA_SETTING_VALUES = "com.yunfie.illustia.lsposed.SETTING_VALUES"
    }

    private val pendingSettingValues = Bundle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PalladiumTheme {
                PluginManagerScreen(onClose = ::finishWithResult)
            }
        }
    }

    fun finishWithResult() {
        try {
            val exported = ArrayList<Bundle>()
            for (plugin in PluginStore.list(this)) {
                val item = Bundle()
                item.putString("package", plugin.packageName)
                item.putString("entry", plugin.entryClass)
                item.putString("name", plugin.name)
                item.putString("description", plugin.description)
                item.putString("version", plugin.version)
                item.putString("sha256", plugin.sha256)
                item.putBoolean("enabled", plugin.enabled)
                item.putParcelable("apk", ParcelFileDescriptor.open(
                    PluginStore.apkFile(this, plugin.packageName), ParcelFileDescriptor.MODE_READ_ONLY
                ))
                exported.add(item)
            }
            setResult(RESULT_OK, Intent()
                .putParcelableArrayListExtra(EXTRA_PLUGINS, exported)
                .putExtra(EXTRA_SETTING_VALUES, pendingSettingValues))
        } catch (failure: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    fun saveSettingValue(pluginId: String, key: String, value: Any) {
        val pluginValues = pendingSettingValues.getBundle(pluginId) ?: Bundle()
        when (value) {
            is Boolean -> pluginValues.putBoolean(key, value)
            is String -> pluginValues.putString(key, value)
        }
        pendingSettingValues.putBundle(pluginId, pluginValues)
    }
}

@Composable
private fun PalladiumTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val lightColors = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
        onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
        background = androidx.compose.ui.graphics.Color(0xFFFFFBFE),
        surface = androidx.compose.ui.graphics.Color(0xFFFFFBFE),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
        onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
        outline = androidx.compose.ui.graphics.Color(0xFF79747E),
        error = androidx.compose.ui.graphics.Color(0xFFB3261E)
    )
    val darkColors = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF381E72),
        secondary = androidx.compose.ui.graphics.Color(0xFFCCC2DC),
        background = androidx.compose.ui.graphics.Color(0xFF141218),
        surface = androidx.compose.ui.graphics.Color(0xFF141218),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
        onSurface = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFCAC4D0),
        outline = androidx.compose.ui.graphics.Color(0xFF938F99),
        error = androidx.compose.ui.graphics.Color(0xFFF2B8B5)
    )
    MaterialTheme(colorScheme = if (dark) darkColors else lightColors, content = content)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PluginManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as PluginSettingsActivity
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var plugins by remember { mutableStateOf(PluginStore.list(context)) }
    var importing by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<PluginStore.PluginInfo?>(null) }
    var selectedSettingsPlugin by remember { mutableStateOf<PluginStore.PluginInfo?>(null) }
    val runtimeStatuses = remember { activity.intent.getBundleExtra(PluginSettingsActivity.EXTRA_RUNTIME_STATUS) }
    val runtimeSettings = remember { activity.intent.getBundleExtra(PluginSettingsActivity.EXTRA_PLUGIN_SETTINGS) }

    fun refresh() { plugins = PluginStore.list(context) }

    fun importPlugin(uri: Uri) {
        scope.launch {
            importing = true
            try {
                withContext(Dispatchers.IO) {
                    val source = context.contentResolver.openInputStream(uri)
                        ?: error(context.getString(R.string.error_read_plugin))
                    source.use { PluginStore.importApk(context, it) }
                }
                refresh()
                snackbars.showSnackbar(context.getString(R.string.plugin_import_success))
            } catch (failure: Exception) {
                snackbars.showSnackbar(context.getString(
                    R.string.plugin_import_failed,
                    failure.message ?: failure.javaClass.simpleName
                ))
            } finally {
                importing = false
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPlugin(uri)
    }

    BackHandler {
        if (selectedSettingsPlugin != null) selectedSettingsPlugin = null else onClose()
    }

    LaunchedPluginStatusRefresh { refresh() }

    removeTarget?.let { plugin ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.plugin_remove)) },
            text = { Text(stringResource(R.string.plugin_remove_confirmation, plugin.name)) },
            confirmButton = {
                TextButton(onClick = {
                    PluginStore.remove(context, plugin.packageName)
                    removeTarget = null
                    refresh()
                }) { Text(stringResource(R.string.plugin_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedSettingsPlugin?.name ?: stringResource(R.string.plugin_short_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedSettingsPlugin != null) selectedSettingsPlugin = null else onClose()
                    }) {
                        SimpleGlyph(Glyph.BACK, MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    if (selectedSettingsPlugin == null) {
                        IconButton(
                            onClick = { documentPicker.launch(arrayOf("*/*")) },
                            enabled = !importing,
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.plugin_import)
                            }
                        ) {
                            SimpleGlyph(Glyph.ADD, MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbars) }
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            HorizontalDivider()
            if (selectedSettingsPlugin != null) {
                PluginSettingsPage(
                    settingCatalog = runtimeSettings?.getBundle(selectedSettingsPlugin!!.packageName),
                    onValueChange = { key, value ->
                        activity.saveSettingValue(selectedSettingsPlugin!!.packageName, key, value)
                    }
                )
            } else if (plugins.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SimpleGlyph(Glyph.PLUGIN, MaterialTheme.colorScheme.outline, Modifier.size(42.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.plugin_settings_empty), style = MaterialTheme.typography.bodyMedium)
                    if (importing) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.plugin_importing), style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(plugins, key = { it.packageName }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            runtimeStatus = runtimeStatuses?.getString(plugin.packageName),
                            hasSettings = runtimeSettings?.containsKey(plugin.packageName) == true,
                            onSettings = { selectedSettingsPlugin = plugin },
                            onEnabled = { enabled ->
                                PluginStore.setEnabled(context, plugin.packageName, enabled)
                                refresh()
                                scope.launch {
                                    delay(800)
                                    refresh()
                                }
                            },
                            onRemove = { removeTarget = plugin }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedPluginStatusRefresh(onRefresh: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(1800)
            onRefresh()
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginStore.PluginInfo,
    runtimeStatus: String?,
    hasSettings: Boolean,
    onSettings: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val enabledDescription = stringResource(R.string.plugin_enabled)
    val disabledDescription = stringResource(R.string.plugin_disabled)
    val removeDescription = stringResource(R.string.plugin_remove)
    val settingsDescription = stringResource(R.string.plugin_settings_open)
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(plugin.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val currentStatus = runtimeStatus ?: plugin.status
                val status = when {
                    currentStatus == "loaded" -> stringResource(R.string.plugin_status_loaded)
                    currentStatus?.startsWith("error:") == true -> stringResource(R.string.plugin_status_error_short)
                    plugin.enabled -> stringResource(R.string.plugin_waiting_restart_short)
                    else -> stringResource(R.string.plugin_disabled_short)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tint = when {
                        currentStatus?.startsWith("error:") == true -> MaterialTheme.colorScheme.error
                        currentStatus == "loaded" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    SimpleGlyph(if (currentStatus?.startsWith("error:") == true) Glyph.ERROR else Glyph.DOT, tint, Modifier.size(12.dp))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.semantics {
                    contentDescription = if (plugin.enabled) enabledDescription else disabledDescription
                }
            )
            if (hasSettings) {
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.semantics {
                        contentDescription = settingsDescription
                    }
                ) { SimpleGlyph(Glyph.SETTINGS, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = removeDescription }
            ) { SimpleGlyph(Glyph.DELETE, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PluginSettingsPage(
    settingCatalog: Bundle?,
    onValueChange: (String, Any) -> Unit
) {
    val definitions = settingCatalog?.getParcelableArrayList<Bundle>("definitions") ?: emptyList()
    val initialValues = settingCatalog?.getBundle("values") ?: Bundle()
    val values = remember(settingCatalog) {
        mutableStateMapOf<String, Any>().apply {
            for (definition in definitions) {
                val key = definition.getString("key") ?: continue
                val value = initialValues.get(key) ?: definition.get("default") ?: continue
                if (value is Boolean || value is String) put(key, value)
            }
        }
    }

    if (definitions.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SimpleGlyph(Glyph.SETTINGS, MaterialTheme.colorScheme.outline, Modifier.size(38.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.plugin_settings_empty_fields), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(definitions, key = { it.getString("key") ?: it.hashCode().toString() }) { definition ->
                val key = definition.getString("key") ?: return@items
                val title = definition.getString("title").orEmpty()
                val summary = definition.getString("summary").orEmpty()
                if (definition.getString("type") == "BOOLEAN") {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(title, style = MaterialTheme.typography.titleSmall)
                                if (summary.isNotBlank()) Text(
                                    summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = values[key] as? Boolean ?: definition.getBoolean("default"),
                                onCheckedChange = { value ->
                                    values[key] = value
                                    onValueChange(key, value)
                                }
                            )
                        }
                    }
                } else {
                    val value = values[key] as? String ?: definition.getString("default").orEmpty()
                    OutlinedTextField(
                        value = value,
                        onValueChange = { updated ->
                            values[key] = updated
                            onValueChange(key, updated)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(title) },
                        supportingText = if (summary.isBlank()) null else ({ Text(summary) }),
                        singleLine = true
                    )
                }
            }
        }
    }
}

private enum class Glyph { BACK, ADD, DELETE, PLUGIN, DOT, ERROR, SETTINGS }

@Composable
private fun SimpleGlyph(glyph: Glyph, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = (w * 0.09f).coerceAtLeast(1.5.dp.toPx())
        val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(tint, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), strokeWidth, StrokeCap.Round)
        }
        when (glyph) {
            Glyph.BACK -> {
                line(w * .78f, h * .5f, w * .22f, h * .5f)
                line(w * .22f, h * .5f, w * .48f, h * .22f)
                line(w * .22f, h * .5f, w * .48f, h * .78f)
            }
            Glyph.ADD -> {
                line(w * .5f, h * .2f, w * .5f, h * .8f)
                line(w * .2f, h * .5f, w * .8f, h * .5f)
            }
            Glyph.DELETE -> {
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * .2f, h * .28f), androidx.compose.ui.geometry.Offset(w * .8f, h * .28f), strokeWidth, StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * .36f, h * .2f), androidx.compose.ui.geometry.Offset(w * .64f, h * .2f), strokeWidth, StrokeCap.Round)
                drawRoundRect(tint, androidx.compose.ui.geometry.Offset(w * .28f, h * .34f), androidx.compose.ui.geometry.Size(w * .44f, h * .52f), androidx.compose.ui.geometry.CornerRadius(w * .04f), style)
                line(w * .43f, h * .43f, w * .43f, h * .75f)
                line(w * .57f, h * .43f, w * .57f, h * .75f)
            }
            Glyph.PLUGIN -> {
                drawRoundRect(tint, androidx.compose.ui.geometry.Offset(w * .26f, h * .28f), androidx.compose.ui.geometry.Size(w * .48f, h * .57f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style)
                line(w * .4f, h * .28f, w * .4f, h * .12f)
                line(w * .6f, h * .28f, w * .6f, h * .12f)
                line(w * .26f, h * .55f, w * .12f, h * .55f)
                line(w * .74f, h * .55f, w * .88f, h * .55f)
            }
            Glyph.DOT -> drawCircle(tint, w * .32f)
            Glyph.ERROR -> {
                drawCircle(tint, w * .4f, style = style)
                line(w * .5f, h * .28f, w * .5f, h * .54f)
                drawCircle(tint, w * .06f, androidx.compose.ui.geometry.Offset(w * .5f, h * .7f))
            }
            Glyph.SETTINGS -> {
                drawCircle(tint, w * .31f, androidx.compose.ui.geometry.Offset(w * .5f, h * .5f), style = style)
                drawCircle(tint, w * .09f, androidx.compose.ui.geometry.Offset(w * .5f, h * .5f))
                for (index in 0..7) {
                    val angle = (Math.PI * index / 4.0).toFloat()
                    val inner = w * .34f
                    val outer = w * .46f
                    line(w * .5f + kotlin.math.cos(angle) * inner, h * .5f + kotlin.math.sin(angle) * inner,
                        w * .5f + kotlin.math.cos(angle) * outer, h * .5f + kotlin.math.sin(angle) * outer)
                }
            }
        }
    }
}
