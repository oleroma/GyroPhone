package com.oleroma.gyrophone

import android.app.Application
import com.oleroma.gyrophone.runtime.GamepadRuntime

class GyroPhoneApp : Application() {
    val runtime: GamepadRuntime by lazy { GamepadRuntime(this) }
}

val android.content.Context.runtime: GamepadRuntime
    get() = (applicationContext as GyroPhoneApp).runtime
