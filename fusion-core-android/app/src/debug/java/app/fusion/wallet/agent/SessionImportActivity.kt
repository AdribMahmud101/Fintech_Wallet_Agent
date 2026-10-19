package app.fusion.wallet.agent

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.net.LocalServerSocket
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import app.fusion.wallet.BuildConfig
import kotlinx.coroutines.*

/** Debug-only, :signer only. No key through intents, files, clipboard, logs or saved state. */
class SessionImportActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: LocalServerSocket? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null); check(BuildConfig.DEBUG)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val keys = SessionKeys(this)
        val status = TextView(this).apply { text = if (keys.imported()) "Session already imported." else "Base Sepolia session import (debug only). Wrapped by Android Keystore, separate from guardian." }
        val input = EditText(this).apply {
            hint = "Session private key (hex)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false; filterTouchesWhenObscured = true; isEnabled = !keys.imported()
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val importButton = Button(this).apply {
            text = "Import session"; isEnabled = !keys.imported(); filterTouchesWhenObscured = true
            setOnClickListener {
                var decoded: ByteArray? = null
                try {
                    val text = input.text.toString().trim().removePrefix("0x"); require(text.length == 64)
                    decoded = AgentCrypto.bytes(text); keys.importOnce(decoded)
                    status.text = "Session imported."; input.isEnabled = false; isEnabled = false; server?.close()
                } catch (_: Exception) { status.text = "Session import failed. Check the pinned session address and Keystore." }
                finally { decoded?.fill(0); input.text.clear() }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32); isSaveEnabled = false
            addView(status); addView(input); addView(importButton)
            addView(Button(this@SessionImportActivity).apply { text = "Close"; setOnClickListener { input.text.clear(); finish() } })
        })
        // Transient adb transport for automation: raw bytes over a local socket,
        // only while this secure debug activity is open. Peer must be shell/root.
        if (!keys.imported()) scope.launch {
            try {
                val listener = LocalServerSocket("fusion.session.import.v1"); server = listener
                listener.accept().use { socket ->
                    check(socket.peerCredentials.uid in setOf(0, 2000))
                    val key = ByteArray(32)
                    try {
                        socket.inputStream.readNBytesCompat(key)
                        keys.importOnce(key)
                        socket.outputStream.write("IMPORTED\n".toByteArray())
                        withContext(Dispatchers.Main) { status.text = "Session imported."; input.isEnabled = false; importButton.isEnabled = false }
                    } finally { key.fill(0) }
                }
            } catch (_: Exception) { /* No exception or input bytes enter logs. Manual path remains. */ }
            finally { runCatching { server?.close() }; server = null }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { /* Never serialize input. */ }
    override fun onDestroy() { runCatching { server?.close() }; scope.cancel(); super.onDestroy() }
}
private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) { val n = read(buffer, offset, buffer.size-offset); check(n > 0); offset += n }
}
