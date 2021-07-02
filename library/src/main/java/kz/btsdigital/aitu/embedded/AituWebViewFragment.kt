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
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kz.btsdigital.aitu.embedded.internal.ContactPhoneBookProvider
import kz.btsdigital.aitu.embedded.internal.PermissionDeniedException

private const val EXTRA_URL = "EXTRA_URL"
private const val EXTRA_IS_DEBUG = "EXTRA_IS_DEBUG"

private const val DEFAULT_URL = "https://aitu.io/"

class AituWebViewFragment : Fragment() {

    companion object {
        private const val TAG = "AituWebViewFragment"

        private var authTokenProvider: (() -> String)? = null

        fun create(
            url: String,
            authTokenProvider: (() -> String),
            isDebug: Boolean = false,
        ): AituWebViewFragment {
            this.authTokenProvider = authTokenProvider

            return AituWebViewFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_URL, url)
                    putBoolean(EXTRA_IS_DEBUG, isDebug)
                }
            }
        }
    }

    private var url: String = DEFAULT_URL
    private var isDebug: Boolean = false

    private val contactPhoneBookProvider by lazy { ContactPhoneBookProvider(requireContext()) }

    // Временная переменная для ожидания ответа на запрос пермишенов Контактов
    private var pendingContactsRequestId: String? = null

    // Временная переменная для ожидания возврата в миниаппку после открытия системных настроек
    private var pendingSettingsRequestId: String? = null

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        url = arguments?.getString(EXTRA_URL, DEFAULT_URL) ?: DEFAULT_URL
        isDebug = arguments?.getBoolean(EXTRA_IS_DEBUG, false) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.aitu_webview_fragment, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.webView)

        WebView.setWebContentsDebuggingEnabled(isDebug)
        webView.settings.javaScriptEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.domStorageEnabled = true
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
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

    private fun getDownloadListener(): DownloadListener = object : DownloadListener {
        override fun onDownloadStart(
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimetype: String?,
            contentLength: Long,
        ) {
            if (isDebug) Log.v(
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
            authTokenProvider?.invoke()
        } catch (e: Exception) {
            null
        }
        if (authToken.isNullOrEmpty()) {
            postResult(requestId, Result.failure(Exception("AuthToken is nul or empty")))
        } else {
            val tokenJson = """{"authToken": "$authToken"}"""
            postResult(requestId, Result.success(tokenJson))
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
            if (isDebug) Log.w(TAG, "Не удалось открыть системные Настройки, $e")
            postResult(requestId, Result.failure(Exception("Не удалось открыть системные Настройки")))
        }
    }

    @JavascriptInterface
    fun getContacts(requestId: String) {
        pendingContactsRequestId = requestId
        checkReadContactsPermissions()
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
            if (isDebug) Log.e(TAG, "Ошибка, pendingContactsRequestId пуст")
            return
        }
        lifecycleScope.launchWhenResumed {
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
            postResult(requestId, Result.success(contactsJson))
        }
    }

    private fun onReadContactsPermissionDenied() {
        val requestId = pendingContactsRequestId
        if (requestId.isNullOrBlank()) {
            if (isDebug) Log.e(TAG, "Ошибка, pendingContactsRequestId пуст")
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
            if (isDebug) Log.d(TAG, js)
            webView.evaluateJavascript(js, null)
        }
    }
}