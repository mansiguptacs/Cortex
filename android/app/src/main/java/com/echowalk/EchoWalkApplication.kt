package com.echowalk

import android.app.Application
import com.facebook.soloader.SoLoader

/** Initializes ExecuTorch native runtime (required before [org.pytorch.executorch.extension.llm.LlmModule]). */
class EchoWalkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this, false)
    }
}
