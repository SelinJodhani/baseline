package app.ojas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.ojas.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.settings.value == null }
        enableEdgeToEdge()
        setContent { AppRoot(vm) }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app is the moment people want fresh numbers.
        if (vm.settings.value?.onboarded == true) vm.refresh()
    }
}
