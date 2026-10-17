package com.ps4.builder

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ps4.builder.data.HistoryEntry
import com.ps4.builder.data.HistoryStore
import com.ps4.builder.data.Settings
import com.ps4.builder.net.Backend
import com.ps4.builder.net.GitHubApi
import com.ps4.builder.net.GitHubException
import com.ps4.builder.net.Ps4Client
import com.ps4.builder.net.RunInfo
import com.ps4.builder.net.StepInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A user-visible message in both languages. */
data class Msg(val ar: String, val en: String) {
    fun t(lang: String) = if (lang == "ar") ar else en
}

sealed interface BuildUi {
    object Idle : BuildUi
    data class Working(val title: Msg, val stage: String, val progress: Float?, val canCancel: Boolean, val runUrl: String?) : BuildUi
    data class Done(val entry: HistoryEntry) : BuildUi
    data class Failed(val entry: HistoryEntry?, val message: Msg?, val errorsTxt: String, val runUrl: String?) : BuildUi
    object Cancelled : BuildUi
    data class Problem(val msg: Msg) : BuildUi
}

sealed interface SendUi {
    object Idle : SendUi
    data class Sending(val sent: Long, val total: Long) : SendUi
    data class Verifying(val attempt: Int, val max: Int) : SendUi
    data class Result(val ok: Boolean, val msg: Msg) : SendUi
}

data class LogView(val title: String, val lines: List<String>)

data class UiState(
    val lang: String = "ar",
    val token: String = "",
    val repo: String = "",
    val ip: String = "",
    val settingsError: Msg? = null,
    val settingsMsg: Msg? = null,
    val settingsBusy: Boolean = false,
    val build: BuildUi = BuildUi.Idle,
    val history: List<HistoryEntry> = emptyList(),
    val send: SendUi = SendUi.Idle,
    val sendTag: String? = null,
    val log: LogView? = null,
    val toast: Msg? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = HistoryStore(ctx.filesDir)
    private var settings: Settings? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var buildJob: Job? = null
    private var sendJob: Job? = null
    @Volatile private var activeRunId = 0L
    @Volatile private var cancelRequested = false
    private val repoRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    private fun update(f: (UiState) -> UiState) { _state.value = f(_state.value) }
    private fun setBuild(b: BuildUi) = update { it.copy(build = b) }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    init {
        try {
            val s = Settings(ctx)
            settings = s
            update { it.copy(lang = s.lang, token = s.token, repo = s.repo, ip = s.ip) }
        } catch (e: Exception) {
            update {
                it.copy(settingsError = Msg(
                    "تعذّر فتح التخزين الآمن (${e.javaClass.simpleName}). لا يمكن حفظ الـ token بأمان على هذا الجهاز.",
                    "Secure storage could not be opened (${e.javaClass.simpleName}). The token cannot be stored safely on this device."
                ))
            }
        }
        update { it.copy(history = store.load()) }
        // resume tracking after the process was killed mid-build
        val s = settings
        if (s != null && s.pendingRunId != 0L) resume(s.pendingRunId, s.pendingZip)
    }

    // ---------------------------------------------------------------- settings
    fun setLang(l: String) { settings?.lang = l; update { it.copy(lang = l) } }

    private fun normalizeRepo(raw: String) =
        raw.trim().removePrefix("https://github.com/").removePrefix("http://github.com/").removeSuffix(".git").trim('/')

    fun saveSettings(token: String, repoRaw: String, ip: String) {
        val s = settings ?: return update { it.copy(settingsMsg = it.settingsError) }
        val repo = normalizeRepo(repoRaw)
        if (repo.isNotEmpty() && !repoRegex.matches(repo)) {
            return update { it.copy(settingsMsg = Msg("صيغة المستودع يجب أن تكون owner/repo", "Repository must look like owner/repo")) }
        }
        s.token = token; s.repo = repo; s.ip = ip
        update { it.copy(token = s.token, repo = s.repo, ip = s.ip, settingsMsg = Msg("تم الحفظ", "Saved")) }
    }

    fun testConnection() {
        val api = apiOrNull() ?: return
        update { it.copy(settingsBusy = true, settingsMsg = null) }
        viewModelScope.launch {
            val msg = try {
                val (branch, push, wf) = io { api.check() }
                Msg(
                    "الاتصال ناجح. الفرع الافتراضي: $branch. صلاحية الكتابة: ${if (push) "نعم" else "لا"}. " +
                        "build.yml: ${if (wf) "موجود" else "غير موجود على الفرع الافتراضي"}.",
                    "Connected. Default branch: $branch. Push permission: ${if (push) "yes" else "no"}. " +
                        "build.yml: ${if (wf) "found" else "NOT found on the default branch"}."
                )
            } catch (e: CancellationException) { throw e } catch (e: Exception) { friendly(e) }
            update { it.copy(settingsBusy = false, settingsMsg = msg) }
        }
    }

    private fun apiOrNull(): GitHubApi? {
        val s = settings
        if (s == null) { update { it.copy(settingsMsg = it.settingsError) }; return null }
        if (Backend.proxied) {
            val (o, r) = Backend.REPO.split("/")
            return GitHubApi("", o, r, Backend.PROXY_URL.trimEnd('/'))
        }
        if (Backend.managed) {
            val (o, r) = Backend.REPO.split("/")
            return GitHubApi(Backend.token, o, r)
        }
        if (s.token.isBlank() || !repoRegex.matches(s.repo)) {
            update { it.copy(settingsMsg = Msg("أدخل الـ token و owner/repo ثم اضغط حفظ أولاً.", "Enter the token and owner/repo and press Save first.")) }
            setBuild(BuildUi.Problem(Msg("الإعدادات غير مكتملة: الـ token أو owner/repo ناقص (تبويب الإعدادات).", "Settings incomplete: token or owner/repo missing (Settings tab).")))
            return null
        }
        val (o, r) = s.repo.split("/")
        return GitHubApi(s.token, o, r)
    }

    // ---------------------------------------------------------------- build
    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: ""
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    fun onZipPicked(uri: Uri) {
        if (buildJob?.isActive == true) return
        val api = apiOrNull() ?: return
        cancelRequested = false
        activeRunId = 0L
        buildJob = viewModelScope.launch {
            try {
                val name = displayName(uri)
                if (!name.lowercase(Locale.ROOT).endsWith(".zip")) {
                    return@launch setBuild(BuildUi.Problem(Msg("الملف المختار ليس بصيغة .zip ($name)", "The chosen file is not a .zip ($name)")))
                }
                setBuild(BuildUi.Working(Msg("جارٍ قراءة الملف…", "Reading file…"), "", null, false, null))
                val bytes = io { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: return@launch setBuild(BuildUi.Problem(Msg("تعذّرت قراءة الملف.", "Could not read the file.")))
                if (bytes.size > 60 * 1024 * 1024) {
                    return@launch setBuild(BuildUi.Problem(Msg("الملف أكبر من 60MB (حد الرفع عبر API).", "File is larger than 60 MB (API upload limit).")))
                }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val path = "incoming/$stamp-$safe"
                setBuild(BuildUi.Working(Msg("رفع الملف إلى المستودع (commit واحد)…", "Uploading to the repository (one commit)…"), "", null, false, null))
                val sha = io { api.commitFile(path, bytes, "build: $name") }

                setBuild(BuildUi.Working(Msg("انتظار بدء الـ workflow…", "Waiting for the workflow to start…"), "", null, true, null))
                var run: RunInfo? = null
                for (i in 0 until 30) {                     // up to ~2.5 min
                    if (cancelRequested) return@launch setBuild(BuildUi.Cancelled)
                    run = io { api.findRun(sha) }
                    if (run != null) break
                    delay(5000)
                }
                val r = run ?: return@launch setBuild(BuildUi.Problem(Msg(
                    "تم رفع الملف لكن GitHub لم يبدأ أي run للـ commit ${sha.take(7)}. تأكد أن Actions مفعّلة وأن build.yml موجود على الفرع الافتراضي.",
                    "The file was uploaded but GitHub started no run for commit ${sha.take(7)}. Make sure Actions is enabled and build.yml exists on the default branch."
                )))
                settings?.pendingRunId = r.id
                settings?.pendingZip = name
                track(api, r.id, name)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { setBuild(BuildUi.Problem(friendly(e))) }
        }
    }

    private fun resume(runId: Long, zipName: String) {
        val api = apiOrNull() ?: return
        cancelRequested = false
        buildJob = viewModelScope.launch {
            try { track(api, runId, zipName) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { setBuild(BuildUi.Problem(friendly(e))) }
        }
    }

    private fun describe(run: RunInfo, steps: List<StepInfo>): Pair<String, Float?> {
        if (steps.isEmpty()) return (if (run.status == "queued") "queued" else run.status) to null
        val done = steps.count { it.status == "completed" }
        val current = steps.firstOrNull { it.status == "in_progress" }?.name
            ?: steps.lastOrNull { it.status == "completed" }?.name ?: ""
        return current to (done.toFloat() / steps.size)
    }

    private suspend fun track(api: GitHubApi, runId: Long, zipName: String) {
        activeRunId = runId
        var failures = 0
        var last: RunInfo? = null
        while (true) {
            try {
                val run = io { api.getRun(runId) }
                last = run
                val steps = io { api.getSteps(runId) }
                failures = 0
                val (stage, prog) = describe(run, steps)
                setBuild(BuildUi.Working(
                    if (run.status == "queued") Msg("في الانتظار في طابور GitHub…", "Queued on GitHub…")
                    else Msg("البناء قيد التنفيذ", "Build in progress"),
                    stage, if (run.status == "completed") 1f else prog, true, run.url
                ))
                if (run.status == "completed") break
            } catch (e: CancellationException) { throw e
            } catch (e: GitHubException) {
                if (e.code == 401 || e.code == 403 || e.code == 404) throw e
                if (++failures > 5) throw e
            } catch (e: Exception) {
                if (++failures > 5) throw e
            }
            delay(5000)
        }
        finish(api, last!!, zipName)
    }

    private suspend fun finish(api: GitHubApi, run: RunInfo, zipName: String) {
        settings?.pendingRunId = 0L
        activeRunId = 0L
        if (run.conclusion == "cancelled") return setBuild(BuildUi.Cancelled)

        val tag = "build-${run.number}"
        setBuild(BuildUi.Working(Msg("جلب نتائج البناء…", "Fetching build results…"), tag, null, false, run.url))
        var release = io { api.getRelease(tag) }
        var tries = 0
        while (release == null && tries++ < 5) { delay(3000); release = io { api.getRelease(tag) } }
        if (release == null) {
            val failedStep = try { io { api.getSteps(run.id) }.firstOrNull { it.conclusion == "failure" }?.name } catch (e: Exception) { null }
            return setBuild(BuildUi.Failed(null, Msg(
                "انتهى الـ run بنتيجة ${run.conclusion} ولا يوجد Release باسم $tag." + (failedStep?.let { " الخطوة الفاشلة: $it." } ?: "") + " افتح صفحة الـ run على GitHub.",
                "The run ended with '${run.conclusion}' and there is no release named $tag." + (failedStep?.let { " Failed step: $it." } ?: "") + " Open the run page on GitHub."
            ), "", run.url))
        }

        val status = release.assets.firstOrNull { it.name == "status.json" }
            ?.let { JSONObject(io { api.downloadText(it.id) }) }
        val ok = status?.optBoolean("ok", false) ?: false
        val version = status?.optString("version", "") ?: ""
        val time = status?.optString("time", "")?.takeIf { it.isNotBlank() }
            ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        val logDir = store.logDir(tag)
        release.assets.firstOrNull { it.name == "build.log" }?.let { io { api.downloadTo(it.id, File(logDir, "build.log")) {} } }
        val errorsTxt = release.assets.firstOrNull { it.name == "errors.txt" }?.let { io { api.downloadText(it.id) } } ?: ""
        if (errorsTxt.isNotEmpty()) io { File(logDir, "errors.txt").writeText(errorsTxt) }

        val elfAsset = release.assets.firstOrNull { it.name.endsWith(".elf") }
        var entry = HistoryEntry(tag, run.number, time, false, version, "", "", 0, zipName, run.url)
        if (ok && elfAsset != null) {
            val local = "$tag-${elfAsset.name}"
            val dest = store.elfTarget(local)
            io {
                api.downloadTo(elfAsset.id, dest) { got ->
                    setBuild(BuildUi.Working(Msg("تنزيل الـ ELF…", "Downloading the ELF…"), elfAsset.name,
                        if (elfAsset.size > 0) got.toFloat() / elfAsset.size else null, false, run.url))
                }
            }
            entry = entry.copy(ok = true, elfName = elfAsset.name, elfFile = local, elfSize = dest.length())
        }
        addHistory(entry)
        if (entry.ok) setBuild(BuildUi.Done(entry))
        else setBuild(BuildUi.Failed(entry, if (status == null) Msg("لا يوجد status.json في الـ Release.", "The release has no status.json.") else null, errorsTxt, run.url))
    }

    private fun addHistory(e: HistoryEntry) {
        val list = listOf(e) + _state.value.history.filter { it.tag != e.tag }
        store.save(list)
        update { it.copy(history = list) }
    }

    fun cancelBuild() {
        cancelRequested = true
        val id = activeRunId
        val api = apiOrNull() ?: return
        if (id == 0L) { buildJob?.cancel(); setBuild(BuildUi.Cancelled); return }
        viewModelScope.launch {
            try { io { api.cancelRun(id) } }
            catch (e: CancellationException) { throw e }
            catch (e: GitHubException) {
                // 409 = already finished; polling will pick up the final state anyway
                if (e.code != 409) setBuild(BuildUi.Problem(friendly(e)))
            } catch (e: Exception) { setBuild(BuildUi.Problem(friendly(e))) }
        }
    }

    fun dismissBuild() { if (buildJob?.isActive != true) setBuild(BuildUi.Idle) }

    // ---------------------------------------------------------------- logs
    fun logBundle(tag: String): String? {
        val dir = store.logDir(tag)
        val errors = File(dir, "errors.txt").takeIf { it.isFile }?.readText().orEmpty()
        val log = File(dir, "build.log").takeIf { it.isFile }?.readLines().orEmpty()
        if (errors.isEmpty() && log.isEmpty()) return null
        return buildString {
            append("===== errors.txt =====\n").append(errors.ifEmpty { "(none)" }).append("\n\n")
            append("===== build.log (last 200 lines) =====\n").append(log.takeLast(200).joinToString("\n"))
        }
    }

    fun showLog(tag: String) {
        viewModelScope.launch {
            val lines = io { File(store.logDir(tag), "build.log").takeIf { it.isFile }?.readLines() }
            if (lines == null) update { it.copy(toast = Msg("لا يوجد build.log محفوظ لهذا البناء.", "No saved build.log for this build.")) }
            else update { it.copy(log = LogView("build.log — $tag", lines)) }
        }
    }

    fun hasLog(tag: String) = File(store.logDir(tag), "build.log").isFile
    fun closeLog() = update { it.copy(log = null) }
    fun clearToast() = update { it.copy(toast = null) }

    // ---------------------------------------------------------------- PS4
    fun sendToPs4(e: HistoryEntry) {
        if (sendJob?.isActive == true) return
        val ip = settings?.ip.orEmpty().trim()
        val file = store.elfPath(e)
        update { it.copy(sendTag = e.tag) }
        if (ip.isEmpty()) return update { it.copy(send = SendUi.Result(false, Msg("أدخل IP الـ PS4 في الإعدادات.", "Enter the PS4 IP in Settings."))) }
        if (file == null) return update { it.copy(send = SendUi.Result(false, Msg("ملف الـ ELF غير موجود على الجهاز.", "The ELF file is not on this device."))) }
        sendJob = viewModelScope.launch {
            try {
                update { it.copy(send = SendUi.Sending(0, file.length())) }
                io { Ps4Client.sendElf(ip, file) { sent -> update { s -> s.copy(send = SendUi.Sending(sent, file.length())) } } }
                delay(3000)
                val max = 12
                var resent = false
                var last: Ps4Client.Info = Ps4Client.Info.Unreachable("")
                for (attempt in 1..max) {
                    update { it.copy(send = SendUi.Verifying(attempt, max)) }
                    last = io { Ps4Client.info(ip) }
                    if (last is Ps4Client.Info.Version) break
                    // HTTP 404 = the OLD ezRemote Server still answers on 6701 (replacement not finished): after ~20 s send the file once more
                    if (!resent && attempt >= 5 && (last as? Ps4Client.Info.Unreachable)?.reason == "HTTP 404") {
                        resent = true
                        try { io { Ps4Client.sendElf(ip, file) { } } } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { }
                        delay(3000)
                    }
                    if (attempt < max) delay(4000)
                }
                val sentMsg = "أُرسل ${e.elfName} (${fmtSize(file.length())}) إلى $ip:${Ps4Client.LOADER_PORT}. "
                val sentEn = "Sent ${e.elfName} (${fmtSize(file.length())}) to $ip:${Ps4Client.LOADER_PORT}. "
                val res = when (val l = last) {
                    is Ps4Client.Info.Version -> {
                        val match = e.version.isNotBlank() && l.version == e.version
                        SendUi.Result(true, Msg(
                            sentMsg + "السيرفر استجاب — الإصدار على الجهاز: ${l.version}" + if (e.version.isBlank()) "." else if (match) " (يطابق الـ ELF المرسل)." else " (⚠ لا يطابق الـ ELF المرسل: ${e.version}).",
                            sentEn + "Server answered — version on device: ${l.version}" + if (e.version.isBlank()) "." else if (match) " (matches the sent ELF)." else " (⚠ differs from the sent ELF: ${e.version})."
                        ))
                    }
                    is Ps4Client.Info.NoVersion -> SendUi.Result(false, Msg(
                        sentMsg + "السيرفر ردّ على /v2/info لكن بدون حقل version: ${l.snippet}",
                        sentEn + "The server answered /v2/info but without a 'version' field: ${l.snippet}"))
                    is Ps4Client.Info.Unreachable -> SendUi.Result(false, Msg(
                        sentMsg + "لكن السيرفر لم يستجب على http://$ip:${Ps4Client.INFO_PORT}/v2/info بعد $max محاولات (${l.reason})." +
                            (if (l.reason == "HTTP 404") "\nالسيرفر القديم ما زال يعمل. افتح ezRemote على الـ PS4 ثم أرسل مرة أخرى، وإن بقي الأمر فأرسل لي محتوى الملف /data/ezremote_boot.log من الـ PS4." else ""),
                        sentEn + "but the server did not respond at http://$ip:${Ps4Client.INFO_PORT}/v2/info after $max attempts (${l.reason})." +
                            (if (l.reason == "HTTP 404") "\nThe old server is still running. Open ezRemote on the PS4 and send again; if it persists, send the contents of /data/ezremote_boot.log from the PS4." else "")))
                }
                update { it.copy(send = res) }
            } catch (ex: CancellationException) { throw ex
            } catch (ex: Exception) {
                update { it.copy(send = SendUi.Result(false, ps4Error(ex, ip))) }
            }
        }
    }

    fun clearSend() = update { it.copy(send = SendUi.Idle, sendTag = null) }

    // ---------------------------------------------------------------- helpers
    private fun ps4Error(e: Exception, ip: String): Msg = when (e) {
        is ConnectException -> Msg("رُفض الاتصال بـ $ip:${Ps4Client.LOADER_PORT}. تأكد أن الـ PS4 يعمل وأن خيار Enable PayLoader Server مفعّل في إعدادات GoldHEN.", "Connection to $ip:${Ps4Client.LOADER_PORT} refused. Check that the PS4 is on and Enable PayLoader Server is on in GoldHEN settings.")
        is SocketTimeoutException -> Msg("انتهت مهلة الاتصال بـ $ip:${Ps4Client.LOADER_PORT}. تأكد من الـ IP ومن أن الهاتف على نفس الشبكة.", "Timed out connecting to $ip:${Ps4Client.LOADER_PORT}. Check the IP and that the phone is on the same network.")
        is UnknownHostException -> Msg("عنوان غير صالح: $ip", "Invalid address: $ip")
        else -> Msg("فشل الإرسال: ${e.javaClass.simpleName} ${e.message ?: ""}", "Send failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }

    private fun friendly(e: Exception): Msg = when (e) {
        is GitHubException -> when (e.code) {
            401 -> Msg("GitHub رفض الـ token (401): غير صالح أو منتهي.", "GitHub rejected the token (401): invalid or expired.")
            403 -> Msg("ممنوع (403): ${e.message}. تحقق من صلاحيات الـ token (Contents/Actions/Releases = Read & Write) أو من حد الطلبات.", "Forbidden (403): ${e.message}. Check the token permissions (Contents/Actions/Releases = Read & Write) or the rate limit.")
            404 -> Msg("غير موجود (404): ${e.message}. تحقق من owner/repo ومن أن الـ token يملك وصولاً لهذا المستودع.", "Not found (404): ${e.message}. Check owner/repo and that the token can access this repository.")
            else -> Msg("خطأ من GitHub (${e.code}): ${e.message}", "GitHub error (${e.code}): ${e.message}")
        }
        is UnknownHostException, is SocketTimeoutException, is ConnectException ->
            Msg("لا يوجد اتصال بالإنترنت أو انتهت المهلة (${e.javaClass.simpleName}).", "No internet connection or timeout (${e.javaClass.simpleName}).")
        else -> Msg("خطأ غير متوقع: ${e.javaClass.simpleName} ${e.message ?: ""}", "Unexpected error: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
    b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}
