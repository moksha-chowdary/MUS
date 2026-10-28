package com.mus.android.data.ytmusic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A connected YouTube Music session: session cookies captured from the
 * WebView sign-in flow plus the account's display identity.
 */
data class YtConnection(
    val cookies: Map<String, String> = emptyMap(),
    val accountName: String = "",
    val channelHandle: String? = null,
    val photoUrl: String? = null,
    val connectedAtMillis: Long = 0L,
    /**
     * Selected YouTube channel within the signed-in session. Cookies alone
     * always resolve to the default (first) channel — switching channels is
     * a per-request flag, never a cookie change:
     * [pageId] (brand-channel delegation token) goes out as the
     * `X-Goog-PageId` header (+`X-Goog-AuthUser: 0`); [onBehalfOfUser]
     * (legacy UC/`onBehalfOfUser` token) goes into the InnerTube context
     * `user` block; [authUserIndex] (multi-login session index) goes out as
     * the `X-Goog-AuthUser` header.
     */
    val onBehalfOfUser: String? = null,
    val authUserIndex: Int? = null,
    val pageId: String = "",
) {
    val isConnected: Boolean
        get() = cookies.isNotEmpty() && accountName.isNotBlank()

    companion object {
        val DISCONNECTED = YtConnection()
    }
}

/**
 * Holds the connected YouTube Music account's session cookies and derives the
 * per-request SAPISIDHASH Authorization header that InnerTube write endpoints
 * require (same scheme music.youtube.com itself uses in the browser).
 *
 * Cookies are captured by the Settings screen's WebView sign-in flow and
 * persisted in SharedPreferences ("yt_music_auth") via [YtAuthStore].
 */
@Singleton
class YtMusicAuthManager @Inject constructor(
    private val store: YtAuthStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow(YtConnection.DISCONNECTED)
    val connection: StateFlow<YtConnection> = _connection.asStateFlow()

    init {
        scope.launch {
            runCatching { _connection.value = store.load() }
                .onFailure { Log.w(TAG, "Failed to load YT connection", it) }
        }
    }

    /** SAPISID token, extracted in priority order exactly like music.youtube.com. */
    fun sapisid(account: YtConnection = connection.value): String? =
        account.cookies[COOKIE_SAPISID_PRIMARY]
            ?: account.cookies["SAPISID"]
            ?: account.cookies["APISID"]

    fun cookieHeaderValue(account: YtConnection = connection.value): String? {
        val cookies = account.cookies
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Builds `SAPISIDHASH <unix_ts>_<sha1(ts SP sapisid SP origin)>` — the
     * reverse-engineered scheme documented at stackoverflow.com/a/32065323
     * and used by every InnerTube client with credentials.
     */
    fun authorizationHeaderValue(origin: String = AUTH_ORIGIN, account: YtConnection = connection.value): String? {
        val sapisid = sapisid(account) ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val payload = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    suspend fun connect(rawCookieHeader: String, accountName: String, channelHandle: String? = null, photoUrl: String? = null) {
        val cookies = parseCookieHeader(rawCookieHeader)
        if (cookies.isEmpty()) return
        val connection = YtConnection(
            cookies = cookies,
            accountName = accountName.ifBlank { "Google account" },
            channelHandle = channelHandle,
            photoUrl = photoUrl,
            connectedAtMillis = System.currentTimeMillis(),
        )
        // Optimistic in-memory update so an immediate verify never races the
        // async SharedPreferences persistence.
        _connection.value = connection
        store.save(connection)
    }

    /** Clears cookies + identity AND wipes the WebView's own cookie jar so a
     *  later sign-in can never auto-capture the previous account's stale
     *  session. */
    suspend fun signOut() {
        _connection.value = YtConnection.DISCONNECTED
        store.clear()
        runCatching {
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                suspendCancellableCoroutine<Boolean> { cont ->
                    cm.removeAllCookies { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
                cm.flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    /**
     * Attempts to read updated session cookies from Android's system CookieManager.
     * If valid credentials are found and differ from the active connection,
     * updates the connection automatically.
     * Returns true if fresh cookies were found and saved.
     */
    suspend fun refreshCookiesFromCookieManager(): Boolean {
        val current = connection.value
        if (!current.isConnected) return false

        return try {
            val cm = CookieManager.getInstance()
            val musicCookies = cm.getCookie("https://music.youtube.com").orEmpty()
            val ytCookies = cm.getCookie("https://www.youtube.com").orEmpty()
            val combined = when {
                musicCookies.isBlank() -> ytCookies
                ytCookies.isBlank() -> musicCookies
                else -> "$musicCookies; $ytCookies"
            }
            if (combined.isBlank()) return false
            val cookies = parseCookieHeader(combined)
            val hasSapisid = listOf(COOKIE_SAPISID_PRIMARY, "SAPISID", "APISID").any { cookies.containsKey(it) }
            val hasLogin = cookies.containsKey("LOGIN_INFO")
            if (!hasSapisid || !hasLogin) return false

            if (current.cookies == cookies) return false

            val updated = current.copy(
                cookies = cookies,
                connectedAtMillis = current.connectedAtMillis.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
            )
            _connection.value = updated
            store.save(updated)
            Log.i(TAG, "Auto-refreshed YouTube Music session cookies from CookieManager")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not auto-refresh cookies from CookieManager", e)
            false
        }
    }


    private fun parseCookieHeader(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }
            }
            .toMap()

    private companion object {
        const val TAG = "YtMusicAuthManager"
        const val COOKIE_SAPISID_PRIMARY = "__Secure-3PAPISID"
        const val AUTH_ORIGIN = "https://music.youtube.com"
    }
}

/** SharedPreferences-backed persistence for the connected YouTube account. */
@Singleton
class YtAuthStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("yt_music_auth", Context.MODE_PRIVATE)

    fun load(): YtConnection {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return YtConnection.DISCONNECTED
        val cookies = runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                for (key in obj.keys()) put(key, obj.getString(key))
            }
        }.getOrNull().orEmpty()
        if (cookies.isEmpty()) return YtConnection.DISCONNECTED
        return YtConnection(
            cookies = cookies,
            accountName = prefs.getString(KEY_ACCOUNT_NAME, null).orEmpty().ifBlank { "Google account" },
            channelHandle = prefs.getString(KEY_CHANNEL_HANDLE, null)?.takeIf { it.isNotBlank() },
            photoUrl = prefs.getString(KEY_PHOTO_URL, null)?.takeIf { it.isNotBlank() },
            connectedAtMillis = prefs.getLong(KEY_CONNECTED_AT, 0L),
        )
    }

    fun save(connection: YtConnection) {
        val obj = org.json.JSONObject()
        for ((key, value) in connection.cookies) obj.put(key, value)
        prefs.edit()
            .putString(KEY_COOKIES, obj.toString())
            .putString(KEY_ACCOUNT_NAME, connection.accountName)
            .putString(KEY_CHANNEL_HANDLE, connection.channelHandle.orEmpty())
            .putString(KEY_PHOTO_URL, connection.photoUrl.orEmpty())
            .putLong(KEY_CONNECTED_AT, connection.connectedAtMillis)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_COOKIES = "cookies_json"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_CHANNEL_HANDLE = "channel_handle"
        const val KEY_PHOTO_URL = "photo_url"
        const val KEY_CONNECTED_AT = "connected_at"
    }
}
