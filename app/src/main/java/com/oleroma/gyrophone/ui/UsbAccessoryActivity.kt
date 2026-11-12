package com.oleroma.gyrophone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.oleroma.gyrophone.runtime

/**
 * Started by the system when the PC companion switches the phone to accessory mode and the user
 * lets GyroPhone handle it (which also grants the USB permission). Tells the running link to look
 * for the cable now, brings GyroPhone to the front and closes.
 */
class UsbAccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.wifi.checkUsbNow()
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(it)
        }
        finish()
    }
}
