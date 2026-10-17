package com.ps4.builder.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object Ps4Client {
    const val LOADER_PORT = 9090   // GoldHEN BinLoader (as specified by the user)
    const val INFO_PORT = 6701     // server: GET /v2/info (as specified by the user)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Opens TCP ip:9090, writes the raw ELF bytes, closes. Throws IOException on any failure. */
    fun sendElf(ip: String, file: File, onProgress: (Long) -> Unit) {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, LOADER_PORT), 5000)
            s.soTimeout = 15000
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); total += n; onProgress(total)
                }
            }
            out.flush()
            try { s.shutdownOutput() } catch (e: Exception) { /* closing anyway */ }
        }
    }

    sealed interface Info {
        data class Version(val version: String) : Info
        /** The server answered but the JSON had no "version" field. */
        data class NoVersion(val snippet: String) : Info
        data class Unreachable(val reason: String) : Info
    }

    /** GET http://ip:6701/v2/info and read the "version" field. */
    fun info(ip: String): Info {
        return try {
            val req = Request.Builder().url("http://$ip:$INFO_PORT/v2/info").build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) return Info.Unreachable("HTTP ${r.code}")
                val v = try { JSONObject(body).optString("version", "") } catch (e: Exception) { "" }
                if (v.isNotBlank()) Info.Version(v) else Info.NoVersion(body.take(200))
            }
        } catch (e: Exception) {
            Info.Unreachable(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
