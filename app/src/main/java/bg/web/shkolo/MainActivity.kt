package bg.web.shkolo

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.app.AlertDialog
import android.widget.TextView
import android.widget.RelativeLayout
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShkoloWeb"
        private const val START_URL = "https://app.shkolo.bg/"
        private const val HOST = "shkolo.bg"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout

    /** Throttles auto-login so a failed attempt cannot loop. */
    private var lastAutoFillAt = 0L

    /** Guards the one-shot credential reset on the logout redirect. */
    private var logoutHandled = false

    // ----- file chooser plumbing -----
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private val REQ_FILE = 1001

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        webView = findViewById(R.id.webview)

        // Credential bridge for the login form (see Bridge below).
        webView.addJavascriptInterface(Bridge(), "ShkoloBridge")

        // Cookie + DOM-storage persistence across restarts.
        // NOTE: views must exist before touching CookieManager with a WebView.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Pinch-to-zoom: builtInZoomControls enables the gesture,
            // displayZoomControls hides the old +/- buttons.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString ShkoloWeb/1.0"
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (isLogoutUrl(uri.toString())) onLogoutDetected()
                val host = uri.host ?: return false

                // Keep Shkolo navigation inside the WebView.
                if (host.endsWith(HOST) || host.endsWith("microsoftonline.com") ||
                    host.endsWith("accounts.google.com") ||
                    host.endsWith("appleid.apple.com")
                ) {
                    return false
                }
                // Everything else goes to the system browser.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No app to open this link", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isLogoutUrl(url)) onLogoutDetected()
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                injectViewport(view)
                injectLoginHelper(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    progress.visibility = View.VISIBLE
                    progress.progress = newProgress
                } else {
                    progress.visibility = View.GONE
                }
            }

            // ---------- FILE UPLOAD ----------
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback

                val intent = buildFileChooserIntent(fileChooserParams)
                startActivityForResult(
                    Intent.createChooser(intent, "Choose file"), REQ_FILE
                )
                return true
            }

            // ---------- GEOLOCATION ----------
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                    return
                }
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }

            override fun onGeolocationPermissionsHidePrompt() {
                pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
                pendingGeoCallback = null
                pendingGeoOrigin = null
            }

            // ---------- CAMERA / MIC from the page ----------
            override fun onPermissionRequest(request: PermissionRequest) {
                val needed = mutableListOf<String>()
                request.resources.forEach {
                    when (it) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            needed += Manifest.permission.CAMERA
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            needed += Manifest.permission.RECORD_AUDIO
                    }
                }
                if (needed.isEmpty()) {
                    request.deny()
                    return
                }
                val missing = needed.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest = request
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }

        // ---------- FILE DOWNLOAD ----------
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                // Authenticated downloads must go through the WebView's cookies,
                // so hand the cookie header to DownloadManager.
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                request.setTitle(name)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        swipe.setOnRefreshListener { webView.reload() }
        swipe.setColorSchemeColors(0xFF1E88E5.toInt())

        requestBasePermissions()

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }


    // ------------------------------------------------------------------ login

    /** The site ships user-scalable=no; rewrite it so pinch-zoom works. */
    private fun injectViewport(view: WebView) {
        view.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "var c='width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes';" +
                "if(m){m.setAttribute('content',c);}else{" +
                "var e=document.createElement('meta');e.name='viewport';e.content=c;" +
                "document.head.appendChild(e);}})();", null
        )
    }

    /**
     * Captures the credentials when the user logs in by hand, and replays them
     * whenever a password field appears (Shkolo's own "remember me" is broken).
     */
    private fun injectLoginHelper(view: WebView) {
        view.evaluateJavascript(JS_LOGIN_HELPER, null)
    }

    /** Only the real Shkolo origin may touch the credential bridge. */
    private fun trustedOrigin(): Boolean {
        val u = webView.url ?: return false
        return u.startsWith("https://app.shkolo.bg") ||
            u.startsWith("https://shkolo.bg") ||
            u.startsWith("https://www.shkolo.bg")
    }

    /** Exposed to the page as window.ShkoloBridge. */
    inner class Bridge {
        @JavascriptInterface
        fun save(user: String?, pass: String?) {
            if (!trustedOrigin()) return
            if (user.isNullOrBlank() || pass.isNullOrEmpty()) return
            CredStore.save(this@MainActivity, user, pass)
        }

        @JavascriptInterface
        fun load(): String? {
            if (!trustedOrigin()) return null
            val now = System.currentTimeMillis()
            if (now - lastAutoFillAt < 10_000L) return null
            val c = CredStore.load(this@MainActivity) ?: return null
            lastAutoFillAt = now
            return c.first + "\n" + c.second
        }

        @JavascriptInterface
        fun log(msg: String?) {
            Log.i(TAG, "js: " + msg)
        }
    }

    // ------------------------------------------------------------------ logout

    /**
     * Shkolo redirects to /auth/logout when the user logs out (and typing that
     * URL by hand does the same). Seeing it means the session is over, so the
     * saved credentials go too; otherwise the next launch would log back in.
     */
    private fun isLogoutUrl(url: String?): Boolean =
        url != null && url.contains("/auth/logout")

    private fun onLogoutDetected() {
        if (!logoutHandled) {
            logoutHandled = true
            CredStore.clear(this)
            lastAutoFillAt = 0L
        }
        // Let the site finish its own logout before dropping the local session.
        webView.postDelayed({
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            logoutHandled = false
        }, 1500)
    }

    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val content = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            params.acceptTypes?.firstOrNull { it.isNotBlank() }?.let { type = it }
        }

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, content)
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent()))
        }
        return chooser
    }

    private fun cameraIntent(): Intent? {
        val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) return null

        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )
        file.parentFile?.mkdirs()
        cameraImageUri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun requestBasePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.READ_MEDIA_IMAGES
            wanted += Manifest.permission.READ_MEDIA_VIDEO
        }
        wanted += Manifest.permission.CAMERA
        wanted += Manifest.permission.RECORD_AUDIO

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCallback ?: return
            fileCallback = null
            if (resultCode != RESULT_OK) {
                cb.onReceiveValue(null)
                return
            }
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
            cb.onReceiveValue(uris)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { webView.loadUrl(it.toString()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

}

private const val JS_LOGIN_HELPER = """
(function(){
  if (window.__shkoloLogin) return;
  var NAMES = ['visitor_id','user_agent','timezone','webgl_vendor',
               'fonts','canvas_hash','audio_hash','browser_hash'];

  function fields(){
    return {
      form: document.querySelector('form.login-form'),
      u:    document.querySelector('input[name=login]'),
      p:    document.querySelector('input[name=password]')
    };
  }
  function fpReady(){
    var f = fields();
    if (!f.form || !f.u || !f.p) return 'noform';
    for (var i = 0; i < NAMES.length; i++) {
      var el = document.querySelector('input[name="fingerprint[' + NAMES[i] + ']"]');
      if (!el || !el.value) return 'missing:' + NAMES[i];
    }
    return 'ready';
  }

  // Learn the credentials whenever the user logs in by hand.
  function grab(){
    var f = fields();
    if (!f.u || !f.p || !f.u.value || !f.p.value) return;
    ShkoloBridge.save(f.u.value, f.p.value);
  }
  document.addEventListener('submit', grab, true);
  document.addEventListener('click', function(e){
    var t = e.target; if (!t) return;
    if ((t.type || '') === 'submit' || (t.tagName || '').toLowerCase() === 'button') grab();
  }, true);

  var f = fields();
  if (!f.p || f.p.value) return;          // not the login form, or already filled
  var raw = ShkoloBridge.load();
  if (!raw) return;
  var nl = raw.indexOf('\n');
  if (nl <= 0) return;
  var user = raw.substring(0, nl);
  var pass = raw.substring(nl + 1);

  window.__shkoloLogin = true;
  var tries = 0;
  var timer = setInterval(function(){
    tries++;
    var st = fpReady();
    if (st === 'ready') {
      clearInterval(timer);
      var g = fields();
      g.u.value = user;
      g.p.value = pass;
      g.u.dispatchEvent(new Event('input', {bubbles:true}));
      g.p.dispatchEvent(new Event('input', {bubbles:true}));
      ShkoloBridge.log('fingerprint ready after ' + tries + ' ticks, submitting');
      // form.submit() bypasses Cloudflare Rocket Loader's onsubmit guard.
      g.form.submit();
    } else if (tries > 30) {
      clearInterval(timer);
      ShkoloBridge.log('fingerprint not ready: ' + st);
    }
  }, 500);
})();
"""
