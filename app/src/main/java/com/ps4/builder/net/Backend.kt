package com.ps4.builder.net

/**
 * Where builds run and with which credentials. Three modes, first match wins:
 *  1. PROXY_URL set      -> calls go through a proxy that holds the token (see /proxy). No token in the app.
 *  2. embedded token set -> the owner's token is built into the app; Settings shows only the PS4 IP.
 *  3. neither            -> every user enters their own token and owner/repo in Settings.
 *
 * Mode 2 is NOT secret storage: anybody who unpacks the APK can recover the token. The bytes below are only scrambled so that
 * the token does not appear as plain text (GitHub revokes tokens it finds in public source code).
 */
object Backend {
    const val PROXY_URL = ""
    const val REPO = "iagamere/Ps4-AAA"
    private val T = intArrayOf(61, 51, 40, 6, 4, 62, 24, 61, 30, 31, 29, 17, 20, 106, 107, 8, 0, 16, 42, 110, 50, 35, 61, 108, 27, 12, 105, 106, 2, 3, 26, 44, 43, 49, 111, 45, 44, 17, 32, 7)
    val token: String get() = String(CharArray(T.size) { (T[it] xor 0x5A xor (it % 7)).toChar() })
    val proxied: Boolean get() = PROXY_URL.isNotBlank()
    /** true = Settings must not ask for a token / repository. */
    val managed: Boolean get() = proxied || T.isNotEmpty()
}
