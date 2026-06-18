package app.dogrouter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.dogrouter.ui.dogs.DogListScreen
import app.dogrouter.ui.theme.DogRouterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DogRouterTheme {
                DogListScreen()
            }
        }
    }
}
