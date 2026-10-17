package app.fusion.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.fusion.wallet.ui.FusionWalletApp
import app.fusion.wallet.ui.theme.FusionWalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the platform splash is already up when the
        // first frame is requested; calling it later leaves a blank window in between.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app is dark-only; pin light system-bar icons so they stay legible when the phone itself
        // is in light mode (auto-detect left dark icons on the dark canvas).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            FusionWalletTheme {
                FusionWalletApp()
            }
        }
    }
}