package com.novareader.edge_tts

import android.app.Activity
import android.os.Bundle

/** Network voices — nothing to download. Just succeed and close. */
class InstallVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
