package com.turksat46.carlydashboard

import android.annotation.SuppressLint
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


// --- Beispiel: imageProxyToBitmap mit Rotationskorrektur ---
// !!! Stelle sicher, dass diese Funktion korrekt ist und zum ImageFormat passt !!!
@SuppressLint("UnsafeOptInUsageError") // Nötig für imageProxy.image
fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    // Prüfe, ob das Bild gültig ist (manchmal kann es null sein)
    val image = imageProxy.image ?: run {
        Log.w("imageProxyToBitmap", "ImageProxy has no image")
        imageProxy.close() // Schließen, wenn kein Bild da ist
        return null
    }

    // Nur YUV_420_888 unterstützen
    if (image.format != ImageFormat.YUV_420_888) {
        Log.e("imageProxyToBitmap", "Unsupported image format: ${image.format}")
        imageProxy.close() // Schließen bei falschem Format
        return null
    }

    try {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y
        yBuffer.get(nv21, 0, ySize)

        // Copy VU (Interleaved)
        val vPixelStride = image.planes[2].pixelStride // Normalerweise 2 für NV21
        val uPixelStride = image.planes[1].pixelStride // Normalerweise 2 für NV21
        val vRowStride = image.planes[2].rowStride
        val uRowStride = image.planes[1].rowStride

        val uvWidth = image.width / 2
        val uvHeight = image.height / 2
        var nv21Offset = ySize

        // Kopiere V und U interleaved ins NV21 Array
        for (row in 0 until uvHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until uvWidth) {
                val vIndex = vRowStart + col * vPixelStride
                val uIndex = uRowStart + col * uPixelStride

                // Stelle sicher, dass wir nicht über die Buffergrenzen lesen (Defensiv)
                if (vIndex < vSize && uIndex < uSize && nv21Offset + 1 < nv21.size) {
                    nv21[nv21Offset++] = vBuffer[vIndex]
                    nv21[nv21Offset++] = uBuffer[uIndex]
                } else {
                    Log.w("imageProxyToBitmap", "Buffer overflow detected during VU copy at row $row, col $col")
                    // Breche hier ab oder handle den Fehler - NV21 ist jetzt unvollständig
                    break // Breche innere Schleife ab
                }
            }
            if(nv21Offset >= nv21.size) break // Breche äußere Schleife ab, falls schon voll
        }


        // Erstelle YuvImage
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out) // Qualität 95 ist gut
        val imageBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // --- ROTATION ANWENDEN ---
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != 0 && bitmap != null) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) { // Nur recyceln, wenn neues Bitmap erstellt wurde
                bitmap.recycle()
            }
            bitmap = rotatedBitmap
        }
        return bitmap // Gibt das (ggf. rotierte) Bitmap zurück

    } catch (e: Exception) {
        Log.e("imageProxyToBitmap", "Error converting YUV to Bitmap", e)
        return null // Gib null zurück bei Fehlern
    } finally {
        imageProxy.close() // *** WICHTIG: ImageProxy IMMER schließen ***
    }
}