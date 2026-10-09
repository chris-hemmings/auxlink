package au.chris.smonowplaying

import android.app.Activity
import android.os.Bundle

/**
 * Receives "XIAO plugged in" so that ticking "Always" once makes Android
 * remember the USB permission (that only works through an activity), but
 * shows nothing and closes at once: the app no longer pops up on every
 * plug-in. NowPlayingService notices the device by itself and reconnects.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        overridePendingTransition(0, 0)
    }
}
