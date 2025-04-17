package com.turksat46.carlydashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas

@Composable
fun DetectionOverlay(
    modifier: Modifier = Modifier,
    detections: List<YoloV8Classifier.DetectionResult>,
    imageSize: Size
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        detections.forEach { detection ->
            val scaleX = size.width / imageSize.width
            val scaleY = size.height / imageSize.height

            val box = detection.box
            val left = box.left * scaleX
            val top = box.top * scaleY
            val right = box.right * scaleX
            val bottom = box.bottom * scaleY

            drawRect(
                color = androidx.compose.ui.graphics.Color.Red,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                "${detection.label} ${(detection.confidence * 100).toInt()}%",
                left,
                top - 10,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.RED
                    textSize = 36f
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
            )
        }
    }
}
