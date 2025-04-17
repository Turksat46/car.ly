package com.turksat46.carlydashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import com.turksat46.carlydashboard.ui.theme.CarlyDashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarlyDashboardTheme {
                val yoloClassifier = remember { YoloV8Classifier(this, "best-int8.tflite") }
                val detections = remember { mutableStateOf(emptyList<YoloV8Classifier.DetectionResult>()) }
                val imageSize = remember { mutableStateOf(Size(416f, 416f)) }

                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreviewWithDetection(yoloClassifier) { results, size ->
                        detections.value = results
                        imageSize.value = size
                    }
                    DetectionOverlay(
                        modifier = Modifier.fillMaxSize(),
                        detections = detections.value,
                        imageSize = imageSize.value
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CarlyDashboardTheme {

        Box(modifier = Modifier.fillMaxSize()) {

        }
    }
}