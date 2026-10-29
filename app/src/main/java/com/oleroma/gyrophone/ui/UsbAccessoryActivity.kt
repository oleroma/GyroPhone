package com.oleroma.gyrophone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Started by the system when the PC companion switches the phone to accessory mode and the user
 * lets GyroPhone handle it (which also grants the USB permission). Brings GyroPhone to the front,
 * where the running link picks the cable up within a second, and closes.
 */
class UsbAccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(it)
        }
        finish()
    }
}
