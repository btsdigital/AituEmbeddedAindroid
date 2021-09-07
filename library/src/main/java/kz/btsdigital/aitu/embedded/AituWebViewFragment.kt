package kz.btsdigital.aitu.embedded

import android.Manifest.permission.READ_CONTACTS
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import kz.btsdigital.aitu.embedded.internal.ContactPhoneBookProvider
import kz.btsdigital.aitu.embedded.internal.PermissionDeniedException

private const val EXTRA_URL = "EXTRA_URL"

private const val DEFAULT_URL = "https://aitu.io/"

class AituWebViewFragment : Fragment() {

    companion object {
        private const val TAG = "AituWebViewFragment"

        /**
         * Перед созданием нужно настроить [AituBridgeSettings.setup]
         */
        fun create(
            url: String,
        ) = AituWebViewFragment().apply {
            arguments = Bundle().apply {
                putString(EXTRA_URL, url)
            }
        }
    }

    private var url: String = DEFAULT_URL

    private val contactPhoneBookProvider by lazy { ContactPhoneBookProvider(requireContext()) }

    // Временная переменная для ожидания ответа на запрос пермишенов Контактов
    private var pendingContactsRequestId: String? = null

    // Временная переменная для ожидания возврата в миниаппку после открытия системных настроек
    private var pendingSettingsRequestId: String? = null

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        url = arguments?.getString(EXTRA_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.aitu_webview_fragment, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.webView)

        view.findViewById<TextView>(R.id.versionTextView).apply {
            visibility = if (AituBridgeSettings.settings.isDebug) View.VISIBLE else View.GONE
            text = BuildConfig.VERSION_NAME
        }

        WebView.setWebContentsDebuggingEnabled(AituBridgeSettings.settings.isDebug)
        webView.settings.javaScriptEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.domStorageEnabled = true
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = getWebChromeClient()
        webView.setDownloadListener(getDownloadListener())

        webView.removeJavascriptInterface("aitu_embedded_bridge")
        webView.addJavascriptInterface(this, "aitu_embedded_bridge")

        webView.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        if (pendingSettingsRequestId != null) {
            postResult(pendingSettingsRequestId!!, Result.success("\"OK\""))
            pendingSettingsRequestId = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView.removeJavascriptInterface("aitu_embedded_bridge")
    }

    private fun getWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val url = view?.hitTestResult?.extra ?: return false
            val suffixes = listOf(
                ".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp4", ".webm", ".mp3", ".pdf", ".rtf", ".txt",
                ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".fb2", ".epub", ".zip", ".rar"
            )
            if (suffixes.any { url.endsWith(it) }) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)))
                    return true
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Не удалось открыть Url = $url", Toast.LENGTH_SHORT).show()
                    if (AituBridgeSettings.settings.isDebug) Log.w(TAG, "Не удалось открыть Url = $url, $e")
                }
            }
            return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
        }
    }

    private fun getDownloadListener(): DownloadListener = object : DownloadListener {
        override fun onDownloadStart(
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimetype: String?,
            contentLength: Long,
        ) {
            if (AituBridgeSettings.settings.isDebug) Log.v(
                TAG,
                "onDownloadStart(url=$url, contentDisposition=$contentDisposition, mimeType=$mimetype)"
            )
            if (url == null) return

            val source = Uri.parse(url)
            val lastSep = url.lastIndexOf("//")
            val dlFileName = when {
                contentDisposition == null -> url.substring(lastSep)
                contentDisposition.contains("attachment; filename*=UTF-8''") ->
                    contentDisposition.replace("attachment; filename*=UTF-8''", "").trim()
                contentDisposition.contains("Content-Disposition: attachment; filename=") ->
                    contentDisposition.replace("Content-Disposition: attachment; filename=", "").trim()
                else -> url.substring(lastSep)
            }
            val context = context ?: return
            val request = DownloadManager.Request(source)
            request.setTitle(dlFileName)
            val cookies = CookieManager.getInstance().getCookie(url)
            request.addRequestHeader("cookie", cookies)
            request.addRequestHeader("User-Agent", userAgent)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, dlFileName)
            request.setMimeType(mimetype)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            Toast.makeText(context, R.string.downloading, Toast.LENGTH_SHORT).show()
            dm.enqueue(request)
        }
    }

    // region JavascriptInterface
    @JavascriptInterface
    fun getKundelikAuthToken(requestId: String) {
        val authToken = try {
            AituBridgeSettings.settings.authTokenProvider?.invoke()
        } catch (e: Exception) {
            null
        }
        if (authToken.isNullOrEmpty()) {
            postResult(requestId, Result.failure(Exception("AuthToken is null or empty")))
        } else {
            val tokenJson = """{"authToken": "$authToken"}"""
            postResult(requestId, Result.success(tokenJson))
        }
    }

    @JavascriptInterface
    fun getKundelikUserInfo(requestId: String) {
        val userInfo = try {
            AituBridgeSettings.settings.userInfoProvider?.invoke()
        } catch (e: Exception) {
            postResult(requestId, Result.failure(Exception("userInfo is null, ${e.message}")))
            return
        }
        if (userInfo == null) {
            postResult(requestId, Result.failure(Exception("userInfo is null")))
            return
        }

        val json = """{
                |"kundelikUserId": "${userInfo.userId}",
                |"role" : "${userInfo.role}",
                |"classId": ${if (userInfo.classId.isNullOrBlank()) "null" else "\"${userInfo.classId}\""}
                |}""".trimMargin()
        postResult(requestId, Result.success(json))
    }

    @JavascriptInterface
    fun showNewMessengerEvent(requestId: String, `data`: String) {
        // {"eventType":"new_message"}

        val eventTypeRegex = Regex("\"eventType\"\\s*:\\s*\"(.*?)\"")
        val findResult = eventTypeRegex.find(data) ?: run {
            postResult(requestId, Result.failure(Exception("Illegal format, event_type not found")))
            return
        }

        val event = when (findResult.groupValues[1]) {
            "new_message" -> NewMessage
            else -> UnknownEvent(type = findResult.groupValues[1])
        }
        try {
            AituBridgeSettings.settings.showNewMessengerEvent?.invoke(event)
            postResult(requestId, Result.success("\"OK\""))
        } catch (e: Exception) {
            postResult(requestId, Result.failure(Exception("error calling showNewMessengerEvent")))
        }
    }

    @JavascriptInterface
    fun openSettings(requestId: String) {
        pendingSettingsRequestId = requestId
        try {
            requireContext().startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", requireContext().packageName, null))
            )
            // результат вернем в onResume
        } catch (e: Exception) {
            if (AituBridgeSettings.settings.isDebug) Log.w(TAG, "Не удалось открыть системные Настройки, $e")
            postResult(requestId, Result.failure(Exception("Не удалось открыть системные Настройки")))
        }
    }

    @JavascriptInterface
    fun getContacts(requestId: String) {
        pendingContactsRequestId = requestId
        checkReadContactsPermissions()
    }

    @JavascriptInterface
    fun getContactsVersion(requestId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || requireActivity().checkSelfPermission(READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        ) {
            val contactsVersion = contactPhoneBookProvider.getPhoneBookContactsVersion()
            val contactsVersionHash = contactsVersion.hashCode()
            postResult(requestId, Result.success("\"$contactsVersionHash\""))
        } else {
            postResult(requestId, Result.failure(PermissionDeniedException()))
        }
    }
    // endregion

    // region Permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) onReadContactsPermissionGranted()
        else onReadContactsPermissionDenied()
    }

    private fun checkReadContactsPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            onReadContactsPermissionGranted()
        } else {
            when {
                requireActivity().checkSelfPermission(READ_CONTACTS) == PackageManager.PERMISSION_GRANTED ->
                    onReadContactsPermissionGranted()
                requireActivity().checkSelfPermission(READ_CONTACTS) == PackageManager.PERMISSION_DENIED ||
                    requireActivity().shouldShowRequestPermissionRationale(READ_CONTACTS) ->
                    requestPermissionLauncher.launch(READ_CONTACTS)
                else -> onReadContactsPermissionDenied()
            }
        }
    }

    private fun onReadContactsPermissionGranted() {
        val requestId = pendingContactsRequestId
        if (requestId.isNullOrBlank()) {
            if (AituBridgeSettings.settings.isDebug) Log.e(TAG, "Ошибка, pendingContactsRequestId пуст")
            return
        }
        val contacts = contactPhoneBookProvider.getContacts()
        val contactsJson = "${
            contacts.map {
                """{
                        |"first_name": "${it.firstName}", 
                        |"last_name": "${it.lastName}", 
                        |"phone": "${it.phoneNumber}"
                        |}""".trimMargin()
            }
        }"
        val fullContactsJson = """ { "contacts" : $contactsJson }"""
        postResult(requestId, Result.success(fullContactsJson))
    }

    private fun onReadContactsPermissionDenied() {
        val requestId = pendingContactsRequestId
        if (requestId.isNullOrBlank()) {
            if (AituBridgeSettings.settings.isDebug) Log.e(TAG, "Ошибка, pendingContactsRequestId пуст")
            return
        }
        postResult(requestId, Result.failure(PermissionDeniedException()))
    }
    // endregion

    /**
     * Вернуть ответ в JS
     */
    private fun postResult(requestId: String, result: Result<String>) {
        val data = result.getOrNull() ?: "null"

        val error = result.exceptionOrNull()?.let { error ->
            when (error) {
                is PermissionDeniedException -> {
                    """{
                        |"code": "PERMISSION_DENIED",
                        |"msg": "permission deny can't retry",
                        |}""".trimMargin()
                }
                else -> """{ "code": "UNEXPECTED", "msg": "${error.message}"}"""
            }
        } ?: "null"

        webView.post {
            val event = """
                        new CustomEvent(
                            'aituEvents', {
                                "detail": {
                                    "requestId": "$requestId",
                                    "data": $data,
                                    "error": $error
                                }
                            }
                        )
                        """.trimIndent()
            val js = "javascript:window.dispatchEvent($event)"
            if (AituBridgeSettings.settings.isDebug) Log.d(TAG, js)
            webView.evaluateJavascript(js, null)
        }
    }
}