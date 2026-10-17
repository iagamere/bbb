package com.ps4.builder.ui

import com.ps4.builder.net.Backend
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ps4.builder.BuildUi
import com.ps4.builder.MainViewModel
import com.ps4.builder.Msg
import com.ps4.builder.SendUi
import com.ps4.builder.UiState
import com.ps4.builder.data.HistoryEntry
import com.ps4.builder.fmtSize

val LocalLang = compositionLocalOf { "ar" }

@Composable
fun s(ar: String, en: String): String = if (LocalLang.current == "ar") ar else en

private val Ok = Color(0xFF3FB950)
private val Bad = Color(0xFFF85149)
private val Muted = Color(0xFF8B949E)

private val Dark = darkColorScheme(
    primary = Color(0xFF58A6FF), onPrimary = Color(0xFF0B1220),
    background = Color(0xFF0D1117), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D), onSurfaceVariant = Color(0xFFC9D1D9),
    error = Bad, outline = Color(0xFF30363D)
)

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val dir = if (st.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLang provides st.lang, LocalLayoutDirection provides dir) {
        MaterialTheme(colorScheme = Dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Root(vm, st)
            }
        }
    }
}

@Composable
private fun Root(vm: MainViewModel, st: UiState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val ctx = LocalContext.current
    LaunchedEffect(st.toast) {
        st.toast?.let { Toast.makeText(ctx, it.t(st.lang), Toast.LENGTH_LONG).show(); vm.clearToast() }
    }

    if (st.log != null) {
        LogScreen(st.log.title, st.log.lines, onClose = vm::closeLog)
        return
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text(
            s("بنّاء PS4", "PS4 Builder"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        val titles = listOf(s("بناء", "Build"), s("السجل", "History"), s("الإعدادات", "Settings"))
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> BuildTab(vm, st, goSettings = { tab = 2 })
            1 -> HistoryTab(vm, st)
            else -> SettingsTab(vm, st)
        }
    }
}

// =============================================================== Build tab
@Composable
private fun BuildTab(vm: MainViewModel, st: UiState, goSettings: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onZipPicked(uri)
    }
    val busy = st.build is BuildUi.Working
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!Backend.managed && (st.repo.isBlank() || st.token.isBlank())) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s("أكمل الإعدادات أولاً: GitHub token و owner/repo.", "Finish the settings first: GitHub token and owner/repo."))
                    OutlinedButton(onClick = goSettings) { Text(s("فتح الإعدادات", "Open settings")) }
                }
            }
        }
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(s("اختر zip", "Choose zip"))
        }
        when (val b = st.build) {
            is BuildUi.Idle -> Text(s("اختر ملف zip لمشروع الـ PS4 لبدء البناء.", "Choose the PS4 project zip to start a build."), color = Muted)
            is BuildUi.Working -> WorkingCard(b, onCancel = vm::cancelBuild)
            is BuildUi.Done -> {
                ResultCard(b.entry)
                SendSection(vm, st, b.entry)
                OutlinedButton(onClick = vm::dismissBuild, modifier = Modifier.fillMaxWidth()) { Text(s("إغلاق", "Dismiss")) }
            }
            is BuildUi.Failed -> FailedCard(vm, b)
            is BuildUi.Cancelled -> {
                Text(s("أُلغي البناء.", "Build cancelled."), color = Muted)
                OutlinedButton(onClick = vm::dismissBuild) { Text(s("إغلاق", "Dismiss")) }
            }
            is BuildUi.Problem -> {
                Text(b.msg.t(st.lang), color = Bad)
                OutlinedButton(onClick = vm::dismissBuild) { Text(s("إغلاق", "Dismiss")) }
            }
        }
    }
}

@Composable
private fun WorkingCard(b: BuildUi.Working, onCancel: () -> Unit) {
    val lang = LocalLang.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(b.title.t(lang), style = MaterialTheme.typography.titleMedium)
            if (b.stage.isNotBlank()) Text(b.stage, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Muted)
            if (b.progress != null) LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            b.runUrl?.let { Text(it, fontSize = 12.sp, color = Muted) }
            if (b.canCancel) OutlinedButton(onClick = onCancel) { Text(s("إلغاء", "Cancel")) }
        }
    }
}

@Composable
private fun ResultCard(e: HistoryEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s("✔ نجح البناء", "✔ Build succeeded"), color = Ok, style = MaterialTheme.typography.titleMedium)
            Text(s("الإصدار: ", "Version: ") + e.version.ifBlank { "?" })
            Text(s("الحجم: ", "Size: ") + fmtSize(e.elfSize))
            Text(e.elfName, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Muted)
        }
    }
}

@Composable
private fun SendSection(vm: MainViewModel, st: UiState, e: HistoryEntry) {
    val mine = st.sendTag == e.tag
    val sending = mine && (st.send is SendUi.Sending || st.send is SendUi.Verifying)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.sendToPs4(e) }, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
            Text(s("أرسل إلى PS4", "Send to PS4"))
        }
        if (mine) when (val x = st.send) {
            is SendUi.Idle -> {}
            is SendUi.Sending -> {
                Text(s("إرسال… ", "Sending… ") + fmtSize(x.sent) + " / " + fmtSize(x.total))
                LinearProgressIndicator(progress = { if (x.total > 0) x.sent.toFloat() / x.total else 0f }, modifier = Modifier.fillMaxWidth())
            }
            is SendUi.Verifying -> Text(s("التحقق من السيرفر (${x.attempt}/${x.max})…", "Checking the server (${x.attempt}/${x.max})…"))
            is SendUi.Result -> Text(x.msg.t(st.lang), color = if (x.ok) Ok else Bad)
        }
    }
}

@Composable
private fun FailedCard(vm: MainViewModel, b: BuildUi.Failed) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val tag = b.entry?.tag
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s("✖ فشل البناء", "✖ Build failed"), color = Bad, style = MaterialTheme.typography.titleMedium)
            b.message?.let { Text(it.t(LocalLang.current)) }
            if (b.errorsTxt.isNotBlank()) {
                Text("errors.txt", color = Muted, fontSize = 12.sp)
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.background).padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(b.errorsTxt, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tag != null) {
                    OutlinedButton(onClick = {
                        val text = vm.logBundle(tag)
                        if (text != null) {
                            clip.setText(AnnotatedString(text))
                            Toast.makeText(ctx, "✔", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(s("انسخ السجل", "Copy log")) }
                    if (vm.hasLog(tag)) OutlinedButton(onClick = { vm.showLog(tag) }) { Text(s("عرض build.log", "View build.log")) }
                }
            }
            OutlinedButton(onClick = vm::dismissBuild) { Text(s("إغلاق", "Dismiss")) }
        }
    }
}

// =============================================================== History tab
@Composable
private fun HistoryTab(vm: MainViewModel, st: UiState) {
    if (st.history.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text(s("لا توجد بناءات سابقة بعد.", "No builds yet."), color = Muted)
        }
        return
    }
    val clip = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(6.dp)) }
        items(st.history, key = { it.tag }) { e ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(e.tag, style = MaterialTheme.typography.titleSmall)
                        Text(if (e.ok) s("نجح", "OK") else s("فشل", "FAILED"), color = if (e.ok) Ok else Bad)
                    }
                    Text(e.time.replace("T", " ").removeSuffix("Z"), color = Muted, fontSize = 12.sp)
                    if (e.ok) Text(s("الإصدار: ", "Version: ") + e.version.ifBlank { "?" } + "  •  " + fmtSize(e.elfSize), fontSize = 13.sp)
                    Text(e.zipName, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (e.ok) SendSection(vm, st, e)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.hasLog(e.tag)) OutlinedButton(onClick = { vm.showLog(e.tag) }) { Text("build.log") }
                        if (!e.ok && vm.hasLog(e.tag)) {
                            OutlinedButton(onClick = { vm.logBundle(e.tag)?.let { clip.setText(AnnotatedString(it)) } }) {
                                Text(s("انسخ السجل", "Copy log"))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// =============================================================== Settings tab
@Composable
private fun SettingsTab(vm: MainViewModel, st: UiState) {
    var token by remember(st.token) { mutableStateOf(st.token) }
    var repo by remember(st.repo) { mutableStateOf(st.repo) }
    var ip by remember(st.ip) { mutableStateOf(st.ip) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        st.settingsError?.let { Text(it.t(st.lang), color = Bad) }
        if (!Backend.managed) {
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true,
                label = { Text("GitHub token") },
                supportingText = { Text(s("fine-grained: Contents / Actions / Releases = Read and write", "fine-grained: Contents / Actions / Releases = Read and write")) },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = repo, onValueChange = { repo = it }, singleLine = true,
                label = { Text("owner/repo") }, modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, singleLine = true,
            label = { Text(s("IP الـ PS4", "PS4 IP")) }, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(token, repo, ip) }) { Text(s("حفظ", "Save")) }
            if (!Backend.managed) OutlinedButton(onClick = { vm.saveSettings(token, repo, ip); vm.testConnection() }, enabled = !st.settingsBusy) {
                Text(s("حفظ + اختبار GitHub", "Save + test GitHub"))
            }
        }
        if (st.settingsBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        st.settingsMsg?.let { Text(it.t(st.lang)) }

        Text(s("اللغة", "Language"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.lang == "ar") {
                Button(onClick = { vm.setLang("ar") }) { Text("العربية") }
                OutlinedButton(onClick = { vm.setLang("en") }) { Text("English") }
            } else {
                OutlinedButton(onClick = { vm.setLang("ar") }) { Text("العربية") }
                Button(onClick = { vm.setLang("en") }) { Text("English") }
            }
        }
    }
}

// =============================================================== full log
@Composable
private fun LogScreen(title: String, lines: List<String>, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors()) { Text(s("إغلاق", "Close")) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines.size) { i ->
                Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
