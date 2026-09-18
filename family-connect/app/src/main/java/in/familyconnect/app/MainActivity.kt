package com.familyconnect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.familyconnect.app.ui.FamilyConnectApp
import com.familyconnect.app.ui.FamilyConnectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamilyConnectTheme {
                FamilyConnectApp()
            }
        }
    }
}
