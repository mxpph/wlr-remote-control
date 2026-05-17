package com.example.wlr_remote_control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import com.example.wlr_remote_control.network.WlrDtlsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LEFT_BUTTON = 0x110
private const val RIGHT_BUTTON = 0x111
private const val BUTTON_PRESSED = 1
private const val BUTTON_RELEASED = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WlrRemoteControlApp(modifier: Modifier = Modifier) {
    val dtlsClient = remember { WlrDtlsClient() }
    val scope = rememberCoroutineScope()

    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Not connected") }

    DisposableEffect(Unit) {
        onDispose {
            dtlsClient.close()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("WLR Remote") }, expandedHeight = 32.dp) }
    ) { innerPadding ->
        TouchpadScreen(
            modifier = Modifier.padding(innerPadding),
            enabled = isConnected,
            status = connectionStatus,
            onDrag = { dx, dy ->
                scope.launch(Dispatchers.IO) {
                    val sent = dtlsClient.sendMousePacket(dx.toInt(), dy.toInt(), 0, 0)
                    if (!sent) {
                        isConnected = false
                        connectionStatus = "Connection lost"
                    }
                }
            },
            onLeftClick = {
                scope.launch(Dispatchers.IO) {
                    val down = dtlsClient.sendMousePacket(0, 0, LEFT_BUTTON, BUTTON_PRESSED)
                    val up = dtlsClient.sendMousePacket(0, 0, LEFT_BUTTON, BUTTON_RELEASED)
                    if (!down || !up) {
                        isConnected = false
                        connectionStatus = "Connection lost"
                    }
                }
            },
            onRightClick = {
                scope.launch(Dispatchers.IO) {
                    val down = dtlsClient.sendMousePacket(0, 0, RIGHT_BUTTON, BUTTON_PRESSED)
                    val up = dtlsClient.sendMousePacket(0, 0, RIGHT_BUTTON, BUTTON_RELEASED)
                    if (!down || !up) {
                        isConnected = false
                        connectionStatus = "Connection lost"
                    }
                }
            }
        )

        if (!isConnected) {
            ConnectionDialog(
                isConnecting = isConnecting,
                onConnect = { ip, port, psk ->
                    isConnecting = true
                    connectionStatus = "Connecting..."
                    scope.launch {
                        val connected = dtlsClient.connect(ip, port, psk)
                        isConnected = connected
                        isConnecting = false
                        connectionStatus = if (connected) {
                            "Connected to $ip:$port"
                        } else {
                            "Failed to connect"
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun TouchpadScreen(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    status: String,
    onDrag: (Float, Float) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().padding( 12.dp, 0.dp, 12.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = status)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) Color.DarkGray else Color.Gray)
                .pointerInput(enabled) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (enabled) "Touchpad" else "Connect to enable touchpad",
                color = Color.White
            )
        }

        Row(modifier = Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enabled = enabled,
                onClick = onLeftClick
            ) {
                Text("Left Click")
            }
            Button(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enabled = enabled,
                onClick = onRightClick
            ) {
                Text("Right Click")
            }
        }
    }
}

@Composable
private fun ConnectionDialog(
    isConnecting: Boolean,
    onConnect: (String, Int, String) -> Unit
) {
    var ip by rememberSaveable { mutableStateOf("127.0.0.1") }
    var portText by rememberSaveable { mutableStateOf("39076") }
    var psk by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val port = portText.toIntOrNull()
    val canConnect = !isConnecting && !psk.isBlank() && !ip.isBlank() && port != null

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Server Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP Address") },
                    singleLine = true,
                    enabled = !isConnecting
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.trim() },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isConnecting,
                    isError = portText.isNotBlank() && port == null
                )
                OutlinedTextField(
                    value = psk,
                    onValueChange = { psk = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !isConnecting
                )

                if (isConnecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canConnect,
                onClick = {
                    focusManager.clearFocus()
                    onConnect(ip, port ?: return@Button, psk)
                }
            ) {
                Text("Connect")
            }
        }
    )
}

