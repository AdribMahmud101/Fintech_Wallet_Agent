package app.fusion.wallet.advisor

import android.app.Activity
import android.net.LocalServerSocket
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.fusion.wallet.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only provisioning of the DeepInfra key for the demo phone, so nobody types it. Main process,
 * because the Keystore-wrapped store lives there. No key through intents, files, clipboard, logs
 * or saved state; the socket accepts shell/root peers only and only while this screen is open.
 * Release builds do not contain this class.
 */
class AdvisorKeyImportActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: LocalServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null); check(BuildConfig.DEBUG)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val keys = AdvisorServices.get(this).keyStore
        val status = TextView(this).apply {
            text = if (keys.hasKey()) "Advisor key already stored. Remove it in Settings to replace it."
            else "Cloud advisor key import (debug only). Stored encrypted with Android Keystore."
        }
        val input = EditText(this).apply {
            hint = "DeepInfra API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false; filterTouchesWhenObscured = true; isEnabled = !keys.hasKey()
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val importButton = Button(this).apply {
            text = "Import key"; isEnabled = !keys.hasKey(); filterTouchesWhenObscured = true
            setOnClickListener {
                val ok = keys.save(input.text.toString())
                input.text.clear()
                status.text = if (ok) "Advisor key imported. Turn the advisor on in Settings." else "Import failed: not a DeepInfra key."
                if (ok) { input.isEnabled = false; isEnabled = false; runCatching { server?.close() } }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32); isSaveEnabled = false
            addView(status); addView(input); addView(importButton)
            addView(Button(this@AdvisorKeyImportActivity).apply { text = "Close"; setOnClickListener { input.text.clear(); finish() } })
        })
        // Transient adb transport: the raw key (or a DEEPINFRA_API_KEY= line) over a local socket, EOF-terminated.
        if (!keys.hasKey()) scope.launch {
            try {
                val listener = LocalServerSocket(SOCKET); server = listener
                listener.accept().use { socket ->
                    check(socket.peerCredentials.uid in setOf(0, 2000))
                    val buf = ByteArray(MAX_BYTES)
                    try {
                        var n = 0
                        val inp = socket.inputStream
                        while (n < buf.size) { val r = inp.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                        val ok = keys.save(String(buf, 0, n, Charsets.UTF_8))
                        socket.outputStream.write((if (ok) "IMPORTED\n" else "REJECTED\n").toByteArray())
                        if (ok) withContext(Dispatchers.Main) {
                            status.text = "Advisor key imported. Turn the advisor on in Settings."; input.isEnabled = false; importButton.isEnabled = false
                        }
                    } finally { buf.fill(0) }
                }
            } catch (_: Exception) { /* No exception text or input bytes enter logs. Manual path remains. */ }
            finally { runCatching { server?.close() }; server = null }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { /* Never serialize input. */ }
    override fun onDestroy() { runCatching { server?.close() }; scope.cancel(); super.onDestroy() }

    private companion object {
        const val SOCKET = "fusion.advisor.key.import.v1"
        const val MAX_BYTES = 512
    }
}
