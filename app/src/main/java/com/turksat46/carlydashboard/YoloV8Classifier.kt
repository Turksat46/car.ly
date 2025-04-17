package com.turksat46.carlydashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloV8Classifier(context: Context, modelPath: String) {
    private val interpreter: Interpreter

    init {
        val assetFile = context.assets.openFd(modelPath)
        val model = FileUtil.loadMappedFile(assetFile)
        interpreter = Interpreter(model, Interpreter.Options().apply {
            setNumThreads(4)
        })
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val input = preprocess(bitmap)
        val output = HashMap<Int, Any>()
        val outputBuffer = Array(1) { Array(8400) { FloatArray(7) } } // YOLOv8 output shape
        output[0] = outputBuffer
        interpreter.runForMultipleInputsOutputs(arrayOf(input), output)
        return postprocess(outputBuffer[0])
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val inputSize = 416
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = scaledBitmap.getPixel(x, y)
                buffer.putFloat(Color.red(px) / 255f)
                buffer.putFloat(Color.green(px) / 255f)
                buffer.putFloat(Color.blue(px) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun postprocess(output: Array<FloatArray>): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        for (i in output.indices) {
            val confidence = output[i][4]
            if (confidence > 0.5) {
                val x = output[i][0]
                val y = output[i][1]
                val w = output[i][2]
                val h = output[i][3]
                val left = x - w / 2
                val top = y - h / 2
                val right = x + w / 2
                val bottom = y + h / 2
                val classId = output[i][5].toInt()
                results.add(
                    DetectionResult(
                        label = "Class $classId",
                        confidence = confidence,
                        box = RectF(left, top, right, bottom)
                    )
                )
            }
        }
        return results
    }

    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val box: RectF
    )
}
