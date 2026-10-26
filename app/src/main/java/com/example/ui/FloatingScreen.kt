package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.engine.GutiMove
import com.example.engine.Piece
import com.example.engine.SholoGutiBoard
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingScreen(
    viewModel: FloatingViewModel,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val isAccessibilityGranted by viewModel.isAccessibilityGranted.collectAsStateWithLifecycle()
    val isOverlayGranted by viewModel.isOverlayGranted.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isScreenCaptureActive by viewModel.isScreenCaptureActive.collectAsStateWithLifecycle()

    val boardState by viewModel.boardState.collectAsStateWithLifecycle()
    val bestMove by viewModel.bestMove.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val countdownSec by viewModel.analysisCountdownSec.collectAsStateWithLifecycle()
    val lastActionMessage by viewModel.lastActionMessage.collectAsStateWithLifecycle()

    // Alignment settings
    val vOffset by viewModel.boardVerticalOffsetFactor.collectAsStateWithLifecycle()
    val hOffset by viewModel.boardHorizontalOffsetFactor.collectAsStateWithLifecycle()
    val scaleFactor by viewModel.boardScaleFactor.collectAsStateWithLifecycle()
    val vScale by viewModel.boardVerticalScaleFactor.collectAsStateWithLifecycle()
    val triRatio by viewModel.triangleRatio.collectAsStateWithLifecycle()
    val triWidthRatio by viewModel.triangleWidthRatio.collectAsStateWithLifecycle()
    val nodeRadiusFactor by viewModel.nodeRadiusFactor.collectAsStateWithLifecycle()
    val isAlignModeActive by viewModel.isAlignModeActive.collectAsStateWithLifecycle()
    val isAutoHintEnabled by viewModel.isAutoHintEnabled.collectAsStateWithLifecycle()
    val isAutoClickerEnabled by viewModel.isAutoClickerEnabled.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "16 Ghuti Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isServiceRunning) "● Floating Overlay Active" else "Ready to Launch",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isServiceRunning) Color(0xFF00E676) else Color(0xFF94A3B8)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshPermissions(context) },
                        modifier = Modifier.testTag("refresh_status_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Status",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color(0xFF0A0F1D),
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Primary Action: Launch Floating Assistant
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(
                        1.dp,
                        if (isServiceRunning) Color(0xFF00E676) else Color(0xFF334155)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isServiceRunning) "Assistant is Running" else "Start Assistant",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "100% transparent overlay with glowing hint vector",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (isServiceRunning) Color(0x3300E676) else Color(0x3364748B),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isServiceRunning) Icons.Default.PlayArrow else Icons.Default.Adjust,
                                        contentDescription = null,
                                        tint = if (isServiceRunning) Color(0xFF00E676) else Color(0xFF94A3B8),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (!isOverlayGranted) {
                                    viewModel.requestOverlay(context)
                                } else {
                                    viewModel.toggleService(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServiceRunning) Color(0xFFEF4444) else Color(0xFF00E676)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("start_floating_service_button")
                        ) {
                            Icon(
                                imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isServiceRunning) Color.White else Color(0xFF003314)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isServiceRunning) "Stop Floating Overlay" else "Launch Floating Assistant",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (isServiceRunning) Color.White else Color(0xFF003314)
                            )
                        }
                    }
                }
            }

            // 2. Permissions Card
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    border = BorderStroke(1.dp, Color(0xFF24334C)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Required Permissions",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        PermissionRow(
                            title = "Display Over Other Apps",
                            description = "Draws the glowing hint vector over your 16 Ghuti game",
                            isGranted = isOverlayGranted,
                            onGrantClick = { viewModel.requestOverlay(context) },
                            testTag = "grant_overlay_button"
                        )

                        PermissionRow(
                            title = "Accessibility Permission",
                            description = "Allows system-wide interaction across the phone",
                            isGranted = isAccessibilityGranted,
                            onGrantClick = { viewModel.requestAccessibility(context) },
                            testTag = "grant_accessibility_button"
                        )

                        PermissionRow(
                            title = "Screen Capture Reader",
                            description = "Accurately scans the game board from live screen",
                            isGranted = isScreenCaptureActive,
                            onGrantClick = onRequestScreenCapture,
                            testTag = "grant_screencap_button",
                            icon = Icons.Default.Videocam
                        )
                    }
                }
            }

            // Auto-Trigger Opponent Move Detection Card
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    border = BorderStroke(
                        1.dp,
                        if (isAutoHintEnabled) Color(0xFF00B0FF).copy(alpha = 0.5f) else Color(0xFF24334C)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Auto-Trigger on Opponent Move",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAutoHintEnabled) "3s delay" else "Off",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAutoHintEnabled) Color(0xFF00E676) else Color(0xFF94A3B8),
                                    modifier = Modifier
                                        .background(
                                            if (isAutoHintEnabled) Color(0xFF003314) else Color(0xFF1E293B),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Screen reader detects opponent move, waits 3 seconds for animation stability, then auto-calculates the 10-second hint.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isAutoHintEnabled,
                            onCheckedChange = { viewModel.toggleAutoHint() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00B0FF),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1E293B)
                            ),
                            modifier = Modifier.testTag("auto_hint_toggle_switch")
                        )
                    }
                }
            }

            // Auto-Clicker Feature Card
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    border = BorderStroke(
                        1.dp,
                        if (isAutoClickerEnabled) Color(0xFF00E676).copy(alpha = 0.5f) else Color(0xFF24334C)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Auto-Clicker (Tap Automation)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAutoClickerEnabled) "Active" else "Off",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAutoClickerEnabled) Color(0xFF00E676) else Color(0xFF94A3B8),
                                    modifier = Modifier
                                        .background(
                                            if (isAutoClickerEnabled) Color(0xFF003314) else Color(0xFF1E293B),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "When hint calculation completes, automatically taps the source piece, waits 400ms, then taps the destination square via AccessibilityService.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                            if (!isAccessibilityGranted) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "⚠️ Accessibility Permission required for tap automation.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFACC15)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isAutoClickerEnabled,
                            onCheckedChange = { viewModel.toggleAutoClicker() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00E676),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF1E293B)
                            ),
                            modifier = Modifier.testTag("auto_clicker_toggle_switch")
                        )
                    }
                }
            }

            // 3. 37-Point Board Alignment & Interactive Tool Card
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    border = BorderStroke(
                        1.dp,
                        if (isAlignModeActive) Color(0xFFFACC15) else Color(0xFF24334C)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "🎯 37-Point Board Alignment",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFACC15)
                                )
                                Text(
                                    text = "100% Proportional grid with on-screen drag & resize",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = null,
                                tint = Color(0xFFFACC15),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Launch Interactive Align Tool button
                        Button(
                            onClick = {
                                if (!isServiceRunning) {
                                    viewModel.toggleService(context)
                                }
                                viewModel.toggleAlignMode()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAlignModeActive) Color(0xFFFACC15) else Color(0xFF1E293B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFACC15)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("toggle_align_mode_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = null,
                                tint = if (isAlignModeActive) Color(0xFF332000) else Color(0xFFFACC15)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAlignModeActive) "Align Mode ON (Drag & Pinch Screen)" else "Launch Interactive Align Tool",
                                fontWeight = FontWeight.Bold,
                                color = if (isAlignModeActive) Color(0xFF332000) else Color(0xFFFACC15)
                            )
                        }

                        // Presets Row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.applyPreset("AlignIt") },
                                label = { Text("AlignIt 16 Guti", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF1E293B),
                                    labelColor = Color(0xFF38BDF8)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF0284C7))
                            )
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.applyPreset("Compact") },
                                label = { Text("Compact", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF1E293B),
                                    labelColor = Color(0xFF38BDF8)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF0284C7))
                            )
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.applyPreset("Expanded") },
                                label = { Text("Expanded", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF1E293B),
                                    labelColor = Color(0xFF38BDF8)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF0284C7))
                            )
                        }

                        // Alignment Adjustment Sliders
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CalibrationSlider(
                                label = "Board Scale",
                                value = scaleFactor,
                                range = 0.50f..1.30f,
                                displayValue = "${(scaleFactor * 100).toInt()}%",
                                onValueChange = { viewModel.setBoardScale(it) }
                            )

                            CalibrationSlider(
                                label = "Vertical Scale (Height)",
                                value = vScale,
                                range = 0.70f..1.30f,
                                displayValue = "${(vScale * 100).toInt()}%",
                                onValueChange = { viewModel.setBoardVerticalScale(it) }
                            )

                            CalibrationSlider(
                                label = "Vertical Position (Y)",
                                value = vOffset,
                                range = 0.20f..0.80f,
                                displayValue = "${(vOffset * 100).toInt()}%",
                                onValueChange = { viewModel.setBoardVerticalOffset(it) }
                            )

                            CalibrationSlider(
                                label = "Horizontal Position (X)",
                                value = hOffset,
                                range = 0.30f..0.70f,
                                displayValue = "${(hOffset * 100).toInt()}%",
                                onValueChange = { viewModel.setBoardHorizontalOffset(it) }
                            )

                            CalibrationSlider(
                                label = "Triangle Wings Height",
                                value = triRatio,
                                range = 0.50f..1.50f,
                                displayValue = "${(triRatio * 100).toInt()}%",
                                onValueChange = { viewModel.setTriangleRatio(it) }
                            )

                            CalibrationSlider(
                                label = "Triangle Wings Width",
                                value = triWidthRatio,
                                range = 0.50f..1.50f,
                                displayValue = "${(triWidthRatio * 100).toInt()}%",
                                onValueChange = { viewModel.setTriangleWidthRatio(it) }
                            )

                            CalibrationSlider(
                                label = "Node / Piece Size (Scale)",
                                value = nodeRadiusFactor,
                                range = 0.50f..1.80f,
                                displayValue = "${(nodeRadiusFactor * 100).toInt()}%",
                                onValueChange = { viewModel.setNodeRadiusFactor(it) }
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.resetCalibrationToDefault() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF475569)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Reset Calibration to Default",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // 4. Interactive 16 Ghuti Board Preview & Engine Test
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    border = BorderStroke(1.dp, Color(0xFF24334C)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "16 Ghuti Board Preview",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Tap node to toggle (Blue / Red / Empty)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Row {
                                OutlinedButton(
                                    onClick = { viewModel.resetBoard() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Reset", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedButton(
                                    onClick = { viewModel.clearBoard() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Clear", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Board Canvas
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.85f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0A0F1D))
                        ) {
                            SholoGutiCanvas(
                                pieces = boardState,
                                bestMove = bestMove,
                                scaleFactor = scaleFactor,
                                verticalOffset = vOffset,
                                horizontalOffset = hOffset,
                                verticalScale = vScale,
                                triangleRatio = triRatio,
                                triangleWidthRatio = triWidthRatio,
                                nodeRadiusFactor = nodeRadiusFactor,
                                onNodeClick = { viewModel.cycleNodePiece(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Test AI Engine Buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { viewModel.triggerAnalysis() },
                                enabled = !isAnalyzing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E676)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("test_hint_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    tint = Color(0xFF003314)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAnalyzing) "Thinking (${countdownSec}s)..." else "Calculate 10s Hint",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF003314),
                                    fontSize = 13.sp
                                )
                            }

                            if (bestMove != null) {
                                Button(
                                    onClick = { viewModel.applyBestMove() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF38BDF8)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(46.dp)
                                ) {
                                    Text("Apply Move", fontWeight = FontWeight.Bold, color = Color(0xFF002238))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Status: $lastActionMessage",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit,
    testTag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Security
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF00E676) else Color(0xFF94A3B8),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0x2200E676),
                border = BorderStroke(1.dp, Color(0xFF00E676))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Granted", fontSize = 11.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag(testTag)
            ) {
                Text("Grant", fontSize = 12.sp, color = Color(0xFF002238), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
            Text(text = displayValue, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFACC15), fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFACC15),
                activeTrackColor = Color(0xFFFACC15),
                inactiveTrackColor = Color(0xFF334155)
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
private fun SholoGutiCanvas(
    pieces: Array<Piece>,
    bestMove: GutiMove?,
    scaleFactor: Float = 0.886f,
    verticalOffset: Float = 0.4764f,
    horizontalOffset: Float = 0.50f,
    verticalScale: Float = 1.00f,
    triangleRatio: Float = 1.00f,
    triangleWidthRatio: Float = 1.00f,
    nodeRadiusFactor: Float = 1.00f,
    onNodeClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseOffset"
    )

    Canvas(
        modifier = modifier.pointerInput(scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio, nodeRadiusFactor) {
            detectTapGestures { tapOffset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val u = w * (scaleFactor / 4.0f)
                val hitRadius = (u * 0.35f * nodeRadiusFactor).coerceIn(16f, 60f)

                for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
                    val (nx, ny) = SholoGutiBoard.calculateNodeScreenPosition(
                        nodeIndex = i,
                        screenWidth = w,
                        screenHeight = h,
                        scaleFactor = scaleFactor,
                        verticalOffset = verticalOffset,
                        horizontalOffset = horizontalOffset,
                        verticalScale = verticalScale,
                        triangleRatio = triangleRatio,
                        triangleWidthRatio = triangleWidthRatio
                    )
                    if (hypot(tapOffset.x - nx, tapOffset.y - ny) <= hitRadius) {
                        onNodeClick(i)
                        break
                    }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val u = w * (scaleFactor / 4.0f)
        val nodeRadius = (u * 0.24f * nodeRadiusFactor).coerceIn(8f, 50f)

        // Draw connecting board lines
        val drawn = HashSet<String>()
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (x1, y1) = SholoGutiBoard.calculateNodeScreenPosition(
                i, w, h, scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio
            )
            for (neighbor in SholoGutiBoard.ADJACENCY[i]) {
                val key = if (i < neighbor) "$i-$neighbor" else "$neighbor-$i"
                if (drawn.add(key)) {
                    val (x2, y2) = SholoGutiBoard.calculateNodeScreenPosition(
                        neighbor, w, h, scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio
                    )
                    drawLine(
                        color = Color(0xFF334155),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 2.5f
                    )
                }
            }
        }

        // Draw nodes and pieces
        for (i in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (cx, cy) = SholoGutiBoard.calculateNodeScreenPosition(
                i, w, h, scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio
            )
            val piece = pieces[i]

            when (piece) {
                Piece.BLUE -> {
                    drawCircle(color = Color(0xFF2979FF), radius = nodeRadius, center = Offset(cx, cy))
                    drawCircle(color = Color.White, radius = nodeRadius, center = Offset(cx, cy), style = Stroke(width = 2f))
                }
                Piece.RED -> {
                    drawCircle(color = Color(0xFFFF1744), radius = nodeRadius, center = Offset(cx, cy))
                    drawCircle(color = Color.White, radius = nodeRadius, center = Offset(cx, cy), style = Stroke(width = 2f))
                }
                Piece.EMPTY -> {
                    drawCircle(color = Color(0xFF1E293B), radius = nodeRadius * 0.5f, center = Offset(cx, cy))
                    drawCircle(color = Color(0xFF475569), radius = nodeRadius * 0.5f, center = Offset(cx, cy), style = Stroke(width = 1.5f))
                }
            }
        }

        // Draw best move vector if present
        val move = bestMove
        if (move != null && move.from in 0 until SholoGutiBoard.TOTAL_NODES && move.to in 0 until SholoGutiBoard.TOTAL_NODES) {
            val (fromX, fromY) = SholoGutiBoard.calculateNodeScreenPosition(
                move.from, w, h, scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio
            )
            val (toX, toY) = SholoGutiBoard.calculateNodeScreenPosition(
                move.to, w, h, scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio
            )

            drawCircle(
                color = Color(0xFF00E676),
                radius = nodeRadius + pulseOffset,
                center = Offset(fromX, fromY),
                style = Stroke(width = 3f)
            )

            drawCircle(
                color = Color(0xFF00B0FF).copy(alpha = 0.5f),
                radius = nodeRadius,
                center = Offset(toX, toY)
            )
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = nodeRadius,
                center = Offset(toX, toY),
                style = Stroke(width = 3f)
            )

            val dx = toX - fromX
            val dy = toY - fromY
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist > 10f) {
                val unitX = dx / dist
                val unitY = dy / dist
                val headLen = (nodeRadius * 1.15f).coerceAtMost(dist * 0.45f)
                val tipX = toX - unitX * (nodeRadius * 0.25f)
                val tipY = toY - unitY * (nodeRadius * 0.25f)
                val shaftEndX = tipX - unitX * (headLen * 0.70f)
                val shaftEndY = tipY - unitY * (headLen * 0.70f)
                val startX = fromX + unitX * (nodeRadius * 0.90f)
                val startY = fromY + unitY * (nodeRadius * 0.90f)

                drawLine(
                    color = Color(0xFF00E676),
                    start = Offset(startX, startY),
                    end = Offset(shaftEndX, shaftEndY),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )

                val angle = atan2(dy.toDouble(), dx.toDouble())
                val headPath = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(
                        (tipX - headLen * cos(angle - Math.PI / 6.2)).toFloat(),
                        (tipY - headLen * sin(angle - Math.PI / 6.2)).toFloat()
                    )
                    lineTo(
                        (tipX - (headLen * 0.65f) * cos(angle)).toFloat(),
                        (tipY - (headLen * 0.65f) * sin(angle)).toFloat()
                    )
                    lineTo(
                        (tipX - headLen * cos(angle + Math.PI / 6.2)).toFloat(),
                        (tipY - headLen * sin(angle + Math.PI / 6.2)).toFloat()
                    )
                    close()
                }
                drawPath(headPath, Color(0xFF00E676))
            }

            if (move.isCapture && move.captured in 0 until SholoGutiBoard.TOTAL_NODES) {
                val (capX, capY) = SholoGutiBoard.calculateNodeScreenPosition(
                    move.captured, w, h, scaleFactor, verticalOffset, horizontalOffset, verticalScale, triangleRatio, triangleWidthRatio
                )
                val cSize = nodeRadius * 0.8f
                drawLine(
                    color = Color(0xFFFF1744),
                    start = Offset(capX - cSize, capY - cSize),
                    end = Offset(capX + cSize, capY + cSize),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFFFF1744),
                    start = Offset(capX + cSize, capY - cSize),
                    end = Offset(capX - cSize, capY + cSize),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
