package com.example.wlr_remote_control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.example.wlr_remote_control.R
import com.example.wlr_remote_control.network.DiscoveredService
import com.example.wlr_remote_control.network.WlrDtlsClient
import com.example.wlr_remote_control.network.rememberDiscoveredServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Button(val code: Int) {
    LEFT_BUTTON(0x110),
    RIGHT_BUTTON(0x111),
}

private enum class ButtonState {
    BUTTON_RELEASED,
    BUTTON_PRESSED,
}

private sealed interface DragSignal {
    data class Delta(val dx: Float, val dy: Float) : DragSignal
    data object Stop : DragSignal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WlrRemoteControlApp(modifier: Modifier = Modifier) {
    val dtlsClient = remember { WlrDtlsClient() }
    val scope = rememberCoroutineScope()

    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Not connected") }
    var dragSenderJob by remember { mutableStateOf<Job?>(null) }
    var dragSignalChannel by remember { mutableStateOf<Channel<DragSignal>?>(null) }

    fun stopDragSender() {
        dragSenderJob?.cancel()
        dragSignalChannel?.trySend(DragSignal.Stop)
        dragSignalChannel?.close()
        dragSignalChannel = null
        dragSenderJob = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stopDragSender()
            dtlsClient.close()
        }
    }

    fun sendMouseButton(button: Button, buttonState: ButtonState) {
        scope.launch(Dispatchers.IO) {
            val success = dtlsClient.sendMousePacket(0, 0, button.code, buttonState.ordinal)
            if (!success) {
                withContext(Dispatchers.Main) {
                    isConnected = false
                    connectionStatus = "Connection lost"
                }
            }
        }
    }

    fun handleTouchpadDrag() {
        val signalChannel = Channel<DragSignal>(capacity = Channel.UNLIMITED)
        dragSignalChannel = signalChannel
        dragSenderJob = scope.launch(Dispatchers.IO) {
            var accDx = 0f
            var accDy = 0f
            var running = true
            while (isActive && running) {
                withFrameMillis { /* sync to device refresh rate */ }
                while (true) {
                    val signal = signalChannel.tryReceive().getOrNull() ?: break
                    when (signal) {
                        is DragSignal.Delta -> {
                            accDx += signal.dx
                            accDy += signal.dy
                        }
                        DragSignal.Stop -> {
                            running = false
                            break
                        }
                    }
                }
                val sendDx = accDx.toInt()
                val sendDy = accDy.toInt()
                accDx -= sendDx
                accDy -= sendDy
                if (running && (sendDx != 0 || sendDy != 0)) {
                    val sent = dtlsClient.sendMousePacket(sendDx, sendDy, 0, 0)
                    if (!sent) {
                        withContext(Dispatchers.Main) {
                            isConnected = false
                            connectionStatus = "Connection lost"
                            stopDragSender()
                        }
                        running = false
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        TouchpadScreen(
            modifier = Modifier.padding(innerPadding),
            enabled = isConnected,
            status = connectionStatus,
            onDragStart = {
                if (!isConnected) {
                    return@TouchpadScreen
                }
                stopDragSender()
                handleTouchpadDrag()
            },
            onDrag = { dx, dy ->
                dragSignalChannel?.trySend(DragSignal.Delta(dx, dy))
            },
            onDragStop = {
                stopDragSender()
            },
            onLeftPress = { sendMouseButton(Button.LEFT_BUTTON, ButtonState.BUTTON_PRESSED) },
            onLeftRelease = { sendMouseButton(Button.LEFT_BUTTON, ButtonState.BUTTON_RELEASED) },
            onRightPress = { sendMouseButton(Button.RIGHT_BUTTON, ButtonState.BUTTON_PRESSED) },
            onRightRelease = { sendMouseButton(Button.RIGHT_BUTTON, ButtonState.BUTTON_RELEASED) },
        )

        if (!isConnected) {
            ConnectionDialog(
                isConnecting = isConnecting,
                onConnect = { ip, port, psk ->
                    isConnecting = true
                    connectionStatus = "Connecting..."
                    scope.launch {
                        stopDragSender()
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
fun PressReleaseButton(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    onPress()
                }
                is PressInteraction.Release -> {
                    onRelease()
                }
                is PressInteraction.Cancel -> {
                    onRelease()
                }
            }
        }
    }

    Button(
        onClick = { },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        content = content
    )
}

@Composable
private fun TouchpadScreen(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    status: String,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragStop: () -> Unit,
    onLeftPress: () -> Unit,
    onLeftRelease: () -> Unit,
    onRightPress: () -> Unit,
    onRightRelease: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().padding( 12.dp, 0.dp, 12.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = status, textAlign = TextAlign.Center)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(if (enabled) Color.DarkGray else Color.Gray)
                .pointerInput(enabled) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragStop() },
                        onDragCancel = { onDragStop() }
                    ) { change, dragAmount ->
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
            PressReleaseButton(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enabled = enabled,
                onPress = onLeftPress,
                onRelease = onLeftRelease,
            ) {
                Text("Left Click")
            }
            PressReleaseButton(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enabled = enabled,
                onPress = onRightPress,
                onRelease = onRightRelease,
            ) {
                Text("Right Click")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordTextField(state: TextFieldState, enabled: Boolean = true) {
    var passwordHidden by rememberSaveable { mutableStateOf(true) }
    OutlinedSecureTextField(
        state = state,
        label = { Text("Password") },
        enabled = enabled,
        textObfuscationMode =
            if (passwordHidden) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
        trailingIcon = {
            val description = if (passwordHidden) "Show password" else "Hide password"
            TooltipBox(
                positionProvider =
                    TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(description) } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = { passwordHidden = !passwordHidden }) {
                    val visibilityIcon = painterResource(
                        if (passwordHidden) R.drawable.visibility_24px else R.drawable.visibility_off_24px
                    )
                    Icon(painter = visibilityIcon, contentDescription = description)
                }
            }
        },
    )
}

@Composable
private fun ConnectionDialog(
    isConnecting: Boolean,
    onConnect: (String, Int, String) -> Unit
) {
    val psk = rememberTextFieldState()
    var showManual by rememberSaveable { mutableStateOf(false) }

    if (showManual) {
        ManualConnectionDialog(
            isConnecting = isConnecting,
            psk = psk,
            onConnect = onConnect,
            onBack = { showManual = false }
        )
    } else {
        DiscoveryConnectionDialog(
            isConnecting = isConnecting,
            psk = psk,
            onConnect = onConnect,
            onManual = { showManual = true }
        )
    }
}

@Composable
private fun DiscoveryConnectionDialog(
    isConnecting: Boolean,
    psk: TextFieldState,
    onConnect: (String, Int, String) -> Unit,
    onManual: () -> Unit,
) {
    val services = rememberDiscoveredServices()
    val focusManager = LocalFocusManager.current
    val canConnect = !isConnecting && !psk.text.isBlank()

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Connect to Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordTextField(state = psk, enabled = !isConnecting)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (services.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                        Text("Scanning…")
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        services.forEach { service ->
                            DiscoveredServiceRow(
                                service = service,
                                enabled = canConnect,
                                onConnect = {
                                    focusManager.clearFocus()
                                    onConnect(service.host, service.port, psk.text.toString())
                                }
                            )
                        }
                    }
                }

                if (isConnecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onManual, enabled = !isConnecting) {
                Text("Manual Input")
            }
        }
    )
}

@Composable
private fun DiscoveredServiceRow(
    service: DiscoveredService,
    enabled: Boolean,
    onConnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = service.name)
                Text(
                    text = "${service.host}:${service.port}",
                    color = Color.Gray,
                )
            }
            Button(onClick = onConnect, enabled = enabled) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun ManualConnectionDialog(
    isConnecting: Boolean,
    psk: TextFieldState,
    onConnect: (String, Int, String) -> Unit,
    onBack: () -> Unit,
) {
    val ip = rememberTextFieldState()
    val portText = rememberTextFieldState("39076")
    val focusManager = LocalFocusManager.current

    val port = portText.text.toString().toIntOrNull()
    val canConnect = !isConnecting && !psk.text.isBlank() && !ip.text.isBlank() && port != null

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Manual Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state = ip,
                    label = { Text("IP Address (v4 or v6)") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    enabled = !isConnecting,
                    placeholder = { Text("e.g. 192.168.1.153") },
                    inputTransformation = InputTransformation {
                        val newText = asCharSequence().toString()
                        if (newText.isBlank()) {
                            return@InputTransformation
                        }
                        val isValidChar = newText.all { char ->
                            char.isDigit() ||
                            char == '.' ||
                            char == ':' ||
                            char in 'a'..'f' ||
                            char in 'A'..'F'
                        }
                        if (!isValidChar || newText.length > 45) {
                            revertAllChanges()
                        }
                    }
                )
                OutlinedTextField(
                    state = portText,
                    label = { Text("Port") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isConnecting,
                    isError = (portText.text.isNotBlank() && port == null) || (port != null && port > 65535),
                    inputTransformation = InputTransformation {
                        val newText = asCharSequence().toString()
                        if (newText.isBlank()) {
                            return@InputTransformation
                        }
                        if (!newText.all { it.isDigit() }) {
                            revertAllChanges()
                        }
                    },
                )
                PasswordTextField(state = psk, enabled = !isConnecting)

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
                    onConnect(ip.text.toString(), port ?: return@Button, psk.text.toString())
                }
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onBack, enabled = !isConnecting) {
                Text("Back")
            }
        }
    )
}

