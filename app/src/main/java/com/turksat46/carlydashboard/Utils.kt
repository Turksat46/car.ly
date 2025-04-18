package com.turksat46.carlydashboard

import android.graphics.*
import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

// Funktion zum Konvertieren von YUV_420_888 zu NV21 (Hilfsfunktion)
private fun YUV_420_888toNV21(image: android.media.Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = width * height / 4

    val nv21 = ByteArray(ySize + uvSize * 2)

    val yBuffer = image.planes[0].buffer // Y
    val uBuffer = image.planes[1].buffer // U
    val vBuffer = image.planes[2].buffer // V

    var rowStride = image.planes[0].rowStride
    assert(image.planes[0].pixelStride == 1)

    var pos = 0
    if (rowStride == width) { // Fallback to copy by row for non-contiguous planes
        yBuffer.get(nv21, 0, ySize)
        pos += ySize
    } else {
        var yBufferPos = -rowStride // not an actual position
        while (pos < ySize) {
            yBufferPos += rowStride
            yBuffer.position(yBufferPos)
            yBuffer.get(nv21, pos, width)
            pos += width
        }
    }

    rowStride = image.planes[2].rowStride
    val pixelStride = image.planes[2].pixelStride

    assert(rowStride == image.planes[1].rowStride)
    assert(pixelStride == image.planes[1].pixelStride)

    if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
        // maybe V an U planes overlap UVIFF format
        val v = vBuffer.get()
        vBuffer.rewind()
        vBuffer.get(nv21, ySize, uvSize * 2 - 1)
        nv21[ySize + uvSize * 2 - 1] = v // reuse the last V byte previously read
    } else {
        // Copy V channel
        var rowStart = ySize
        for (row in 0 until height / 2) {
            var vBufferPos = row * rowStride
            for (col in 0 until width / 2) {
                nv21[rowStart + col * 2] = vBuffer.get(vBufferPos)
                vBufferPos += pixelStride
            }
            rowStart += width
        }
        // Copy U channel
        rowStart = ySize + 1
        for (row in 0 until height / 2) {
            var uBufferPos = row * rowStride
            for (col in 0 until width / 2) {
                nv21[rowStart + col * 2] = uBuffer.get(uBufferPos)
                uBufferPos += pixelStride
            }
            rowStart += width
        }
    }
    return nv21
}


// Hauptfunktion zum Konvertieren von ImageProxy zu Bitmap
@OptIn(ExperimentalGetImage::class)
fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    var bitmap: Bitmap? = null
    try {
        val image = imageProxy.image ?: return null

        // Stelle sicher, dass das Format YUV_420_888 ist
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e("imageProxyToBitmap", "Unsupported image format: ${image.format}")
            // Fallback oder Fehlerbehandlung
            // try { return imageProxy.toBitmap() } catch (e: Exception) { return null }
            return null
        }

        val nv21 = YUV_420_888toNV21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null // Wichtig: Check auf null

        // Rotation berücksichtigen
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees == 0) {
            bitmap = decodedBitmap
        } else {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(
                decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true
            )
            // Gib das Original-Bitmap frei, wenn ein neues erstellt wurde
            if (bitmap != decodedBitmap) {
                // decodedBitmap.recycle() // Vorsicht mit recycle(), kann zu Problemen führen, wenn es noch referenziert wird.
                // Sicherer ist es oft, das nicht explizit zu tun und dem GC zu überlassen.
            }
        }
    } catch (e: Exception) {
        Log.e("imageProxyToBitmap", "Error converting ImageProxy to Bitmap", e)
        bitmap = null // Stelle sicher, dass bei Fehlern null zurückgegeben wird
    } finally {
        // WICHTIG: Schließe ImageProxy IMMER, egal ob erfolgreich oder nicht!
        try {
            imageProxy.close()
        } catch (e: Exception) {
            Log.e("imageProxyToBitmap", "Error closing ImageProxy", e)
        }
    }
    return bitmap
}