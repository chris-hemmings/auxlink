package au.chris.auxlink

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
        // The Pi's USB-C link appeared (plug-in, or the Pi re-plugging a link
        // that went quiet): make sure the service that talks to it is running.
        NowPlayingService.rebindIfDown(this, "USB attached")
        finish()
        overridePendingTransition(0, 0)
    }
}
