package com.jing.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jing.camera.camera.JingCameraController
import com.jing.camera.camera.MediaStoreSaver
import kotlinx.coroutines.launch

private const val TAG = "CameraScreen"

enum class CameraMode(val label: String) {
    PHOTO("照片"),
    PORTRAIT("人像"),
    NIGHT("夜景"),
    VIDEO("视频")
}

enum class TimerMode(val seconds: Int, val label: String) {
    OFF(0, "关闭"),
    SEC_3(3, "3秒"),
    SEC_10(10, "10秒")
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashMode by remember { mutableStateOf(JingCameraController.FlashMode.OFF) }
    var thumbnailUri by remember { mutableStateOf<Uri?>(null) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var capturing by remember { mutableStateOf(false) }
    var showSlideUp by remember { mutableStateOf(false) }
    var timerMode by remember { mutableStateOf(TimerMode.OFF) }
    var livePhotosEnabled by remember { mutableStateOf(true) }

    val cameraController = remember { JingCameraController(context) }
    val textureView = remember { TextureView(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.all { it.value }
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            hasPermission = true
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            val cameraIds = cameraController.getCameraIds()
            if (cameraIds.isNotEmpty()) {
                cameraController.openCamera(textureView, cameraIds[0])
            }
        }
    }

    LaunchedEffect(cameraController) {
        cameraController.onPhotoCaptured = { image ->
            capturing = true
            scope.launch {
                val bytes = MediaStoreSaver.imageToByteArray(image)
                val uri = MediaStoreSaver.saveJpeg(context, bytes)
                uri?.let { thumbnailUri = it }
                capturing = false
            }
        }
        cameraController.onPhotoCapturedJpeg = { jpegBytes ->
            capturing = true
            scope.launch {
                val uri = MediaStoreSaver.saveJpeg(context, jpegBytes)
                uri?.let { thumbnailUri = it }
                capturing = false
            }
        }
        cameraController.onZoomChanged = { zoom ->
            zoomLevel = zoom
        }
    }

    if (!hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("需要相机权限", color = Color.White)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Preview
        AndroidView(
            factory = { textureView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        cameraController.focusAt(
                            offset.x, offset.y, size.width.toFloat(), size.height.toFloat()
                        )
                    }
                }
        )

        // Top controls
        AnimatedVisibility(
            visible = !capturing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopControls(
                flashMode = flashMode,
                livePhotosEnabled = livePhotosEnabled,
                timerMode = timerMode,
                onFlashToggle = {
                    cameraController.nextFlashMode()
                    flashMode = cameraController.flashMode
                },
                onLivePhotosToggle = { livePhotosEnabled = !livePhotosEnabled },
                onTimerToggle = {
                    timerMode = when (timerMode) {
                        TimerMode.OFF -> TimerMode.SEC_3
                        TimerMode.SEC_3 -> TimerMode.SEC_10
                        TimerMode.SEC_10 -> TimerMode.OFF
                    }
                },
                onSlideUp = { showSlideUp = true }
            )
        }

        // Zoom indicator
        if (zoomLevel > 1.1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 100.dp)
            ) {
                Text(
                    text = String.format("%.1fx", zoomLevel),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        // Slide-up panel
        AnimatedVisibility(
            visible = showSlideUp,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SlideUpControls(
                flashMode = flashMode,
                livePhotosEnabled = livePhotosEnabled,
                timerMode = timerMode,
                onFlashToggle = {
                    cameraController.nextFlashMode()
                    flashMode = cameraController.flashMode
                },
                onLivePhotosToggle = { livePhotosEnabled = !livePhotosEnabled },
                onTimerToggle = {
                    timerMode = when (timerMode) {
                        TimerMode.OFF -> TimerMode.SEC_3
                        TimerMode.SEC_3 -> TimerMode.SEC_10
                        TimerMode.SEC_10 -> TimerMode.OFF
                    }
                },
                onClose = { showSlideUp = false }
            )
        }

        // Bottom area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Mode carousel
            ModeCarousel(
                currentMode = currentMode,
                onModeSelected = { mode ->
                    currentMode = mode
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Shutter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                        .clickable {
                            thumbnailUri?.let {
                                // TODO: open in gallery
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = if (thumbnailUri != null) Color.White else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Shutter button
                ShutterButton(
                    mode = currentMode,
                    capturing = capturing,
                    onShutterClick = {
                        if (!capturing) {
                            when (currentMode) {
                                CameraMode.PHOTO -> cameraController.captureHdrPhoto()
                                CameraMode.PORTRAIT -> cameraController.capturePhoto()
                                CameraMode.NIGHT -> cameraController.captureHdrPhoto()
                                CameraMode.VIDEO -> { /* TODO */ }
                            }
                        }
                    }
                )

                // Camera switch
                IconButton(
                    onClick = { /* TODO: switch camera */ },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "切换镜头",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Capturing flash overlay
        AnimatedVisibility(
            visible = capturing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
fun TopControls(
    flashMode: JingCameraController.FlashMode,
    livePhotosEnabled: Boolean,
    timerMode: TimerMode,
    onFlashToggle: () -> Unit,
    onLivePhotosToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onSlideUp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash
        IconButton(onClick = onFlashToggle) {
            Icon(
                imageVector = when (flashMode) {
                    JingCameraController.FlashMode.OFF -> Icons.Default.FlashOff
                    JingCameraController.FlashMode.ON -> Icons.Default.FlashOn
                    JingCameraController.FlashMode.AUTO -> Icons.Default.FlashAuto
                },
                contentDescription = "闪光灯",
                tint = Color.White
            )
        }

        // Live Photos
        Text(
            text = "LIVE",
            color = if (livePhotosEnabled) Color.White else Color.White.copy(alpha = 0.3f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onLivePhotosToggle() }
        )

        // Timer
        IconButton(onClick = onTimerToggle) {
            Text(
                text = if (timerMode == TimerMode.OFF) "⏱" else "${timerMode.seconds}",
                color = if (timerMode == TimerMode.OFF) Color.White.copy(alpha = 0.5f) else Color(0xFFFFCC00),
                fontSize = if (timerMode == TimerMode.OFF) 20.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Slide up arrow
        IconButton(onClick = onSlideUp) {
            Text(
                text = "▲",
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun SlideUpControls(
    flashMode: JingCameraController.FlashMode,
    livePhotosEnabled: Boolean,
    timerMode: TimerMode,
    onFlashToggle: () -> Unit,
    onLivePhotosToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(24.dp)
    ) {
        // Close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Control grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Flash
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onFlashToggle() }
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (flashMode) {
                            JingCameraController.FlashMode.OFF -> Icons.Default.FlashOff
                            JingCameraController.FlashMode.ON -> Icons.Default.FlashOn
                            JingCameraController.FlashMode.AUTO -> Icons.Default.FlashAuto
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("闪光灯", color = Color.White, fontSize = 12.sp)
            }

            // Live Photos
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onLivePhotosToggle() }
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (livePhotosEnabled) Color(0xFFFFCC00) else Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "LIVE",
                        color = if (livePhotosEnabled) Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("实况", color = Color.White, fontSize = 12.sp)
            }

            // Timer
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onTimerToggle() }
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (timerMode != TimerMode.OFF) Color(0xFFFFCC00) else Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (timerMode == TimerMode.OFF) "⏱" else "${timerMode.seconds}",
                        color = if (timerMode == TimerMode.OFF) Color.White else Color.Black,
                        fontSize = if (timerMode == TimerMode.OFF) 24.sp else 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("定时器", color = Color.White, fontSize = 12.sp)
            }

            // Aspect ratio (placeholder)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { }
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "4:3",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("比例", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun ModeCarousel(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit
) {
    val modes = CameraMode.entries
    val currentIndex = modes.indexOf(currentMode)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount < -30 && currentIndex < modes.size - 1) {
                        onModeSelected(modes[currentIndex + 1])
                    } else if (dragAmount > 30 && currentIndex > 0) {
                        onModeSelected(modes[currentIndex - 1])
                    }
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEachIndexed { index, mode ->
            val isSelected = mode == currentMode
            val distance = kotlin.math.abs(index - currentIndex)
            val alpha = if (distance <= 1) 1f else 0.3f

            Box(
                modifier = Modifier
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = mode.label,
                    color = if (isSelected) Color(0xFFFFCC00) else Color.White.copy(alpha = alpha),
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun ShutterButton(
    mode: CameraMode,
    capturing: Boolean,
    onShutterClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "shutterScale"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.White)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                        onShutterClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(
                    when (mode) {
                        CameraMode.PHOTO -> Color.White
                        CameraMode.PORTRAIT -> Color.White
                        CameraMode.NIGHT -> Color(0xFF444444)
                        CameraMode.VIDEO -> Color.Red
                    }
                )
        )
    }
}
