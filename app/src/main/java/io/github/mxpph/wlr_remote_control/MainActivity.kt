package io.github.mxpph.wlr_remote_control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.mxpph.wlr_remote_control.ui.WlrRemoteControlApp
import io.github.mxpph.wlr_remote_control.ui.theme.WlrRemoteControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WlrRemoteControlTheme {
                WlrRemoteControlApp()
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun Preview() {
    WlrRemoteControlTheme {
        WlrRemoteControlApp()
    }
}