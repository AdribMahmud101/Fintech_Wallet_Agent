package app.fusion.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.fusion.wallet.ui.FusionWalletApp
import app.fusion.wallet.ui.theme.FusionWalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the platform splash is already up when the
        // first frame is requested; calling it later leaves a blank window in between.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FusionWalletTheme {
                FusionWalletApp()
            }
        }
    }
}