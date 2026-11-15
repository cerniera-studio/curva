package com.cerniera.curva

import android.app.Application
import com.cerniera.kit.Crash

class CurvaApp : Application() {
    override fun onCreate() { super.onCreate(); Crash.install(this, "Curva") }
}
