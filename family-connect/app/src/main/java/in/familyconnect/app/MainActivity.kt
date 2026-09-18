package com.familyconnect.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.familyconnect.app.ui.FamilyConnectApp
import com.familyconnect.app.ui.FamilyConnectTheme
import kotlinx.coroutines.flow.MutableStateFlow

object ExternalDestinationShare {
    val text = MutableStateFlow<String?>(null)

    fun consume() {
        text.value = null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingShare(intent)
        setContent {
            FamilyConnectTheme {
                FamilyConnectApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!shared.isNullOrBlank()) {
                ExternalDestinationShare.text.value = shared
            }
        }
    }
}
