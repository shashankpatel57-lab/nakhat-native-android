package in.familyconnect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import in.familyconnect.app.ui.FamilyConnectApp
import in.familyconnect.app.ui.FamilyConnectTheme

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
