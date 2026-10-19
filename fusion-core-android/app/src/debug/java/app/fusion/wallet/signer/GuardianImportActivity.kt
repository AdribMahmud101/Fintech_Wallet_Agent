package app.fusion.wallet.signer

import android.app.Activity
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

/** Debug source set only. No key via Intent, clipboard reads, saved state, logs or result extras. */
class GuardianImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        check(BuildConfig.DEBUG)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store = GuardianKeyStore(this)
        val status = TextView(this).apply {
            text = if (store.isImported()) "Guardian already imported. Close this screen and use Stop."
            else "Base Sepolia guardian import (debug only). Enter the operator's testnet key once. It is encrypted with Android Keystore."
        }
        val input = EditText(this).apply {
            hint = "Guardian private key (hex)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false
            filterTouchesWhenObscured = true
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isEnabled = !store.isImported()
        }
        val importButton = Button(this).apply {
            text = "Import guardian"
            isEnabled = !store.isImported()
            filterTouchesWhenObscured = true
            setOnClickListener {
                // Editable/String and EC BigInteger copies cannot be reliably zeroized on the JVM.
                // Keep their lifetime short, and never format or log them or exceptions.
                var decoded: ByteArray? = null
                try {
                    val hex = input.text.toString().trim().removePrefix("0x")
                    require(hex.length == 64) { "Invalid key length" }
                    decoded = GuardianTransaction.bytes(hex)
                    store.importOnce(decoded)
                    status.text = "Guardian imported. Close this screen and use Stop in the Agent tab."
                    input.isEnabled = false
                    isEnabled = false
                } catch (_: Exception) {
                    status.text = "Import failed. Check the deployed Base Sepolia guardian key and device Keystore."
                } finally {
                    decoded?.fill(0)
                    input.text.clear()
                }
            }
        }
        val close = Button(this).apply { text = "Close"; setOnClickListener { input.text.clear(); finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            isSaveEnabled = false
            addView(status); addView(input); addView(importButton); addView(close)
        })
    }
    override fun onSaveInstanceState(outState: Bundle) { /* Never serialize input text. */ }
}
