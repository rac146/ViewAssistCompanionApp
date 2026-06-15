package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.net.toUri
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import timber.log.Timber
import kotlin.random.Random

data class AuthToken(val tokenType: String = "", val accessToken: String = "", val expires: Long = 0, val refreshToken: String = "")

class AuthUtils(val config: APPConfig) {

    // Add external auth callback for HA authentication
    val externalAuthCallback = object : ExternalAuthCallback {
        override fun onRequestExternalAuth(view: WebView, payload: String) {
            val json = Json { ignoreUnknownKeys = true }
            val payloadJson = json.parseToJsonElement(payload).jsonObject
            val force = payloadJson["force"]?.jsonPrimitive?.boolean ?: false

            log.d("External auth callback in progress...")

            if (!Helpers.isNetworkAvailable(config.context)) {
                Timber.w("Network unavailable.  Not performing authorisation ")
                setAuthStage(view, PageLoadingStage.AUTH_REQUIRED)
                return
            }

            setAuthStage(view, PageLoadingStage.AUTHORISING)
            if (config.refreshToken == "") {
                Timber.d("No refresh token.  Proceeding to login screen")
                loadUrl(
                    view,
                    getAuthUrl(getHAUrl(config, withDashboardPath = false)),
                    clearCache = true
                )
                setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                return
            } else if (System.currentTimeMillis() > (config.tokenExpiry - 120) || force) {
                // Token will expire in less than 2 mins, consider expired
                // Need to get new access token as it has expired
                if (force) {
                    Timber.d("HA forcing new token using refresh token")
                } else {
                    Timber.d("Auth token has expired.  Requesting new token using refresh token")
                }
                val success: Boolean = reAuthWithRefreshToken()
                if (success) {
                    Timber.d("Authorising with new token")
                    callAuthJS(view)
                    setAuthStage(view, PageLoadingStage.AUTHORISED)
                } else {
                    Timber.d("Failed to refresh auth token.  Proceeding to login screen")
                    setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                    loadUrl(view, getAuthUrl(getHAUrl(config, withDashboardPath = false)), clearCache = true)
                }
            } else if (config.accessToken != "") {
                Timber.d("Auth token is still valid - authorising")
                setAuthStage(view, PageLoadingStage.AUTHORISED)
                callAuthJS(view)
            }
        }

        override fun onRequestRevokeExternalAuth(view: WebView) {
            Timber.d("External auth revoke callback in progress...")
            config.accessToken = ""
            config.refreshToken = ""
            config.tokenExpiry = 0
            setAuthStage(view, PageLoadingStage.AUTH_FAILED)
            loadUrl(view, getAuthUrl(getHAUrl(config)))
        }

        private fun setAuthStage(view: WebView, stage: PageLoadingStage) {
            Handler(Looper.getMainLooper()).post({
                val w = view as CustomWebView
                w.setPageLoadingState(stage)
            })
        }

        private fun loadUrl(view: WebView, url: String, clearCache: Boolean = false) {
            Timber.d("Loading URL: $url")
            Handler(Looper.getMainLooper()).post({
                if (clearCache) {
                    view.clearCache(true)
                }
                view.loadUrl(url)
            })
        }

        private fun callAuthJS(view: WebView) {
            Handler(Looper.getMainLooper()).post({
                view.evaluateJavascript(
                    "window.externalAuthSetToken(true, {\n" +
                            "\"access_token\": \"${config.accessToken}\",\n" +
                            "\"expires_in\": 1800\n" +
                            "});",
                    null
                )
            })
        }

        private fun reAuthWithRefreshToken(): Boolean {
            Timber.d("Requesting new token using refresh token")
            val auth = refreshAccessToken(
                getHAUrl(config),
                config.refreshToken,
                !config.ignoreSSLErrors
            )
            if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                log.d("Received new auth token")
                config.accessToken = auth.accessToken
                config.tokenExpiry = auth.expires
                return true
            } else {
                return false
            }
        }
    }

    companion object {
        val log = Logger()
        const val CLIENT_URL = "vaca.homeassistant"
        var state: String = ""

        fun getHAUrl(config: APPConfig, withDashboardPath: Boolean = true): String {
            val url = if (config.homeAssistantURL == "") {
                "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
            } else {
                config.homeAssistantURL.removeSuffix("/")
            }

            if (withDashboardPath && config.homeAssistantDashboard != "") {
                return url + "/" + config.homeAssistantDashboard.removePrefix("/")
            }
            return url
        }

        fun getURL(baseUrl: String): String {
            log.d("Getting URL for $baseUrl")
            val url = baseUrl.toUri()
                .buildUpon()
                .appendQueryParameter("external_auth", "1")
                .build()
            return url.toString()
        }

        fun getAuthUrl(baseUrl: String): String {
            log.d("Getting Auth URL for $baseUrl")
            val url = baseUrl.toUri()
                .buildUpon()
                .path("")
                .appendPath("auth")
                .appendPath("authorize")
                .appendQueryParameter("client_id", getClientId())
                .appendQueryParameter("redirect_uri", getRedirectUri())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("state", generateState())
                .appendQueryParameter("scope", "homeassistant")
                .build()
            return url.toString()
        }

        fun getTokenUrl(baseUrl: String): String {
            val url = baseUrl.toUri()
                .buildUpon()
                .path("")
                .appendPath("auth")
                .appendPath("token")
            return url.build().toString()
        }

        private fun generateState(): String {
            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            state = buildString(32) {
                repeat(32) { append(charset[Random.Default.nextInt(charset.length)]) }
            }
            return state
        }

        private fun getClientId(): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.authority(CLIENT_URL)
            return builder.build().toString()
        }

        private fun getRedirectUri(): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.authority(CLIENT_URL)
            builder.appendQueryParameter("auth_callback","1")
            return builder.build().toString()

        }

        fun validateAuthResponse(url: String): Boolean {
            val uri = url.toUri()
            return uri.authority == CLIENT_URL && uri.getQueryParameter("state") == state
        }

        fun getReturnAuthCode(url: String): String {
            if (validateAuthResponse(url)) {
                return url.toUri().getQueryParameter("code")!!
            } else {
                return ""
            }
        }

        fun authoriseWithAuthCode(baseUrl: String, authCode: String, verifySSL: Boolean = true): AuthToken {
            val url: String = getTokenUrl(baseUrl)
            val map: HashMap<String, String> = hashMapOf(
                "grant_type" to "authorization_code",
                "client_id" to getClientId(),
                "code" to authCode
            )
            log.d("URL: $url Auth code: $authCode, client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = Json.parseToJsonElement(response).jsonObject
                val accessToken = json["access_token"]?.jsonPrimitive?.content ?: ""
                val tokenType = json["token_type"]?.jsonPrimitive?.content ?: ""
                val refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: ""
                
                if (accessToken.isEmpty() || tokenType.isEmpty()) {
                    log.e("Authentication failed: Required token fields missing")
                    return AuthToken()
                }

                val expiresInSeconds = json["expires_in"]?.jsonPrimitive?.intOrNull
                if (expiresInSeconds == null || expiresInSeconds <= 0) {
                    log.e("Authentication failed: invalid expires_in")
                    return AuthToken()
                }
                
                val expiresIn = System.currentTimeMillis() + (expiresInSeconds * 1000)

                return AuthToken(
                    tokenType,
                    accessToken,
                    expiresIn,
                    refreshToken
                )
            } catch (e: Exception) {
                log.e("Failed to parse auth response: ${e.message}")
                return AuthToken()
            }
        }

        fun refreshAccessToken(host: String, refreshToken: String, verifySSL: Boolean = true): AuthToken {
            val url: String = getTokenUrl(host)
            val map: HashMap<String, String> = hashMapOf(
                "grant_type" to "refresh_token",
                "client_id" to getClientId(),
                "refresh_token" to refreshToken
            )
            Timber.d("URL: $url Refresh token: ${refreshToken.substring(20)}..., client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = Json.parseToJsonElement(response).jsonObject
                
                val accessToken = json["access_token"]?.jsonPrimitive?.content ?: ""
                val tokenType = json["token_type"]?.jsonPrimitive?.content ?: ""
                val expiresInSeconds = json["expires_in"]?.jsonPrimitive?.intOrNull
                
                if (accessToken.isEmpty() || tokenType.isEmpty()) {
                    Timber.e("Token refresh failed: access_token or token_type fields missing")
                    return AuthToken()
                }

                if (expiresInSeconds == null || expiresInSeconds <= 0) {
                    Timber.e("Token refresh failed: invalid expires_in")
                    return AuthToken()
                }

                val expiresIn = System.currentTimeMillis() + (expiresInSeconds * 1000)

                return AuthToken(
                    tokenType,
                    accessToken,
                    expiresIn,
                )
            } catch (e: Exception) {
                Timber.e("Failed to parse refresh response: ${e.message}")
                return AuthToken()
            }

        }

        fun httpPOST(url: String, parameters: HashMap<String, String>, verifySSL: Boolean = true): String {
            val client = if (verifySSL) httpClient else httpClientTrustAll
            val builder = FormBody.Builder()
            val it = parameters.entries.iterator()

            while (it.hasNext()) {
                val pair = it.next() as Map.Entry<*, *>
                builder.add(pair.key.toString(), pair.value.toString())
            }

            val formBody = builder.build()
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.e("Unexpected code $response")
                        return ""
                    }
                    return response.body.string()
                }
            } catch (e: Exception) {
                log.e("Error authorising with HA: ${e.message.toString()}")
                return ""
            }
        }

        val httpClient: OkHttpClient = OkHttpClient()
        val httpClientTrustAll: OkHttpClient by lazy {
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }

        val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate?>?,
                authType: String?
            ) {}

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate?>?,
                authType: String?
            ) {}

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate?> {
                return arrayOf<java.security.cert.X509Certificate?>()
            }
        })
    }
}