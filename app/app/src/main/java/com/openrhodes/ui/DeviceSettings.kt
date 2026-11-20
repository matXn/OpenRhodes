package com.openrhodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrhodes.RhodesApp
import com.openrhodes.domain.Lamp
import kotlinx.coroutines.*

internal enum class DeviceSettingsPage(val title: String) {
    Menu("设备设置"), Name("灯具名称"), Connection("连接"),
    Information("设备信息"), Licenses("第三方开源许可");

    fun back(): DeviceSettingsPage? = if (this == Menu) null else Menu
}

@Composable
internal fun DeviceSettings(
    app: RhodesApp,
    lamp: Lamp,
    page: DeviceSettingsPage,
    navigate: (DeviceSettingsPage) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GroupColor)) {
        for (section in DeviceSettingsPage.entries) {
            val (glyph, description) = when (section) {
                DeviceSettingsPage.Name -> SettingsGlyph.Name to lamp.name
                DeviceSettingsPage.Connection -> SettingsGlyph.Connection to null
                DeviceSettingsPage.Information -> SettingsGlyph.Information to null
                DeviceSettingsPage.Licenses -> SettingsGlyph.Licenses to null
                DeviceSettingsPage.Menu -> continue
            }
            if (section != DeviceSettingsPage.Name) HorizontalDivider(
                Modifier.padding(start = 56.dp), thickness = .5.dp, color = Muted.copy(alpha = .22f),
            )
            val expanded = section == page
            SettingsMenuRow(glyph, section.title, description, expanded) {
                navigate(if (expanded) DeviceSettingsPage.Menu else section)
            }
            Column(Modifier.fillMaxWidth().animateContentSize(tween(180)).padding(start = 56.dp, end = 16.dp)) {
                if (expanded) when (section) {
                    DeviceSettingsPage.Name -> LampNameSettings(app, lamp) { navigate(DeviceSettingsPage.Menu) }
                    DeviceSettingsPage.Connection -> WifiSetup(app, lamp)
                    DeviceSettingsPage.Information -> LampDeviceInformation(app, lamp)
                    DeviceSettingsPage.Licenses -> ThirdPartyLicenseDocument()
                    DeviceSettingsPage.Menu -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuRow(glyph: SettingsGlyph, title: String, description: String?, expanded: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click)
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsIcon(glyph)
        Text(title, Modifier.weight(1f), color = Cream, style = MaterialTheme.typography.bodyLarge)
        if (description != null && !expanded) Text(description, Modifier.widthIn(max = 132.dp),
            color = Muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SettingsSection(glyph: SettingsGlyph, title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsIcon(glyph)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun LampNameSettings(app: RhodesApp, lamp: Lamp, saved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(lamp.id) { mutableStateOf(lamp.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (busy) return
        if (name.isBlank()) { error = "请填写灯具名称"; return }
        busy = true
        scope.launch {
            try { app.catalog.rename(lamp.id, name); saved() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "名称保存失败，请重试" }
            finally { busy = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val inlineSave = maxWidth >= 248.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
        val saveAction: @Composable () -> Unit = {
            TextButton(enabled = !busy, onClick = ::save) { Text(if (busy) "正在保存…" else "保存名称") }
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(name, { name = it.replace("\n", " ").take(48); error = null },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "灯具名称输入" },
                    enabled = !busy, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Cream),
                    cursorBrush = SolidColor(Gold), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    decorationBox = { field -> Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) { field() } })
                if (inlineSave) saveAction()
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!inlineSave) saveAction()
        }
    }
}

@Composable
private fun LampDeviceInformation(app: RhodesApp, lamp: Lamp) {
    val context = LocalContext.current
    val session = remember(lamp.id, lamp.hardware) { app.session(lamp.id) }
    val observation by session.observation.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MAC 地址", color = Muted, fontSize = 13.sp)
            val mac = lamp.hardware?.displayTag?.removePrefix("#")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(mac ?: "尚未确认", Modifier.weight(1f), fontFamily = AddressFont, fontSize = 17.sp)
                if (mac != null) TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("MAC 地址", mac)); copied = true
                }) { Text(if (copied) "已复制" else "复制") }
            }
        }
        observation.state?.firmwareVersion?.takeIf { it.isNotBlank() }?.let { version ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("固件版本", color = Muted, fontSize = 13.sp)
                SelectionContainer { Text(version) }
            }
        }
    }
}

/** 一级入口直接呈现许可全文，不经过项目列表或额外弹窗。 */
@Composable
internal fun ThirdPartyLicenseDocument() {
    val context = LocalContext.current
    val document by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("third-party-notices.txt").bufferedReader().use { it.readText() } }
                .getOrElse { "许可文档暂时无法读取，请返回后重试。" }
        }
    }
    SelectionContainer {
        Text(document ?: "正在读取许可…", color = Cream, fontSize = 13.sp, lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
}
