package app.baseline

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.baseline.ui.AppRoot
import app.baseline.widget.EXTRA_TAB

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.settings.value == null }
        enableEdgeToEdge()
        setContent { AppRoot(vm) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_TAB, -1) ?: -1
        if (tab >= 0) vm.openTab(tab)
        val data = intent?.data
        if (data?.scheme == "baseline" && data.host == "connect") {
            val url = data.getQueryParameter("url").orEmpty()
            if (url.startsWith("https://") || url.startsWith("http://")) vm.pair(url, data.getQueryParameter("token").orEmpty())
            intent.data = null // don't re-apply on rotation
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app is the moment people want fresh numbers.
        if (vm.settings.value?.onboarded == true) vm.onResumed()
        app.baseline.update.Updater.resumePendingInstall(this)
        lifecycleScope.launch { app.baseline.update.Updater.autoCheck((application as BaselineApp).store) }
    }
}
