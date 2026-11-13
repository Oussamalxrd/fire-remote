package app.fireremote.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvLink.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            RemoteApp()
            // Status and navigation bar icons follow the app's theme.
            val dark = C.dark
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvLink.setForeground(true)
        if (!TvLink.userStopped) connect(this)
    }

    override fun onStop() {
        TvLink.setForeground(false)
        super.onStop()
    }

    // The phone's volume buttons change the TV's volume while the remote is open and connected.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            TvLink.isOnline && UiPrefs.volumeKeys
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                TvLink.key(code)
                buzz(window.decorView)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun connect(c: Context) {
            TvLink.start(Store.host(c), Store.port(c))
            RemoteService.start(c)
        }
    }
}
