package com.oleroma.gyrophone

import android.app.Application
import android.util.Log
import com.oleroma.gyrophone.runtime.GamepadRuntime

class GyroPhoneApp : Application() {
    val runtime: GamepadRuntime by lazy { GamepadRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        // Builds the runtime (profiles, settings, links) while the system starts the activity, so
        // the first frame waits less for it. Whoever needs it first waits for this build: the lazy
        // value is built once, under its lock. A failure here is left for that caller to hit.
        Thread({
            try {
                runtime
            } catch (e: Throwable) {
                Log.e("GyroPhoneApp", "building the runtime failed", e)
            }
        }, "gp-runtime-init").start()
    }
}

val android.content.Context.runtime: GamepadRuntime
    get() = (applicationContext as GyroPhoneApp).runtime
