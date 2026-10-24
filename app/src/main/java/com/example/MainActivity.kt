package com.example

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.service.FloatingStateHolder
import com.example.service.ScreenCaptureManager
import com.example.ui.FloatingScreen
import com.example.ui.FloatingViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: FloatingViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            com.example.service.FloatingOverlayService.startWithMediaProjection(this, result.resultCode, result.data!!)
            Toast.makeText(this, "Screen Capture active! Assistant ready ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen Capture permission was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    FloatingStateHolder.setAppInForeground(true)
                    viewModel.refreshPermissions(this)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    FloatingStateHolder.setAppInForeground(false)
                }
                else -> {}
            }
        })

        setContent {
            MyApplicationTheme {
                FloatingScreen(
                    viewModel = viewModel,
                    onRequestScreenCapture = {
                        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                        if (mpManager != null) {
                            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        } else {
                            Toast.makeText(this, "Screen Capture not supported on this device", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
