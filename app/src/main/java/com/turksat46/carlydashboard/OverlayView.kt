package com.turksat46.carlydashboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()
    private var laneBitmap: Bitmap? = null

    // Matrix für die Transformation des Overlays zur Anpassung an PreviewView
    private val imageTransformMatrix = Matrix()

    // Dimensionen des Originalbildes (wird von MainActivity gesetzt)
    private var sourceImageWidth: Int = 1
    private var sourceImageHeight: Int = 1

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        laneBitmap = null
        // Paints müssen nicht zurückgesetzt werden
        invalidate() // Fordere Neuzeichnen an
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 30f // Kleinere Textgröße für BBox

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 30f // Kleinere Textgröße

        // Versuche, die Farbe sicher zu laden, mit Fallback
        val boxColor = try {
            ContextCompat.getColor(context!!, R.color.black) // Definiere bbox_color in colors.xml (z.B. #FF00FF00 für Grün)
        } catch (e: Exception) {
            Log.w("OverlayView", "R.color.bbox_color nicht gefunden, verwende Gelb als Fallback.")
            Color.YELLOW // Fallback-Farbe
        }
        boxPaint.color = boxColor
        boxPaint.strokeWidth = 3F // Dünnere Linien
        boxPaint.style = Paint.Style.STROKE
    }

    /**
     * Setzt die Dimensionen des Bildes, auf das sich die Overlay-Daten beziehen.
     * Wird von MainActivity aufgerufen.
     */
    private fun setSourceImageDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            if (sourceImageWidth != width || sourceImageHeight != height) {
                sourceImageWidth = width
                sourceImageHeight = height
                // Keine Neuzeichnung hier anfordern, passiert beim Setzen der Daten
            }
        } else {
            // Setze auf Standardwerte, wenn ungültig
            sourceImageWidth = 1
            sourceImageHeight = 1
        }
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Berechne die Transformationsmatrix basierend auf aktuellen View-Größen
        // und den Source-Image-Dimensionen.
        updateTransformationMatrix()

        // *** 1. Spur-Overlay zeichnen (mit Transformation) ***
        laneBitmap?.let { bmp ->
            if (!bmp.isRecycled) { // Zusätzliche Sicherheitsprüfung
                canvas.drawBitmap(bmp, imageTransformMatrix, null)
            } else {
                Log.w("OverlayView", "Attempted to draw recycled laneBitmap.")
            }
        }

        // *** 2. Bounding Boxes zeichnen (mit Transformation) ***
        results.forEach { box ->
            // Skaliere die normalisierten Koordinaten (0.0-1.0) auf die *Source*-Dimensionen
            val origLeft = box.x1 * sourceImageWidth
            val origTop = box.y1 * sourceImageHeight
            val origRight = box.x2 * sourceImageWidth
            val origBottom = box.y2 * sourceImageHeight

            // Erstelle ein Rechteck mit diesen Original-Pixelkoordinaten
            val rectF = RectF(origLeft, origTop, origRight, origBottom)

            // Wende die berechnete Transformation auf dieses Rechteck an,
            // um die korrekten Koordinaten für die *View* zu erhalten.
            imageTransformMatrix.mapRect(rectF)

            // Zeichne das transformierte Rechteck
            canvas.drawRect(rectF, boxPaint)

            // Zeichne den Klassennamen über dem transformierten Rechteck
            val drawableText = box.clsName
            textPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()

            // Positioniere Text-Hintergrund und Text relativ zur transformierten Box
            canvas.drawRect(
                rectF.left,
                rectF.top - textHeight - BOUNDING_RECT_TEXT_PADDING, // Positioniere über der Box
                rectF.left + textWidth + (BOUNDING_RECT_TEXT_PADDING * 2), // Breite anpassen
                rectF.top,
                textBackgroundPaint
            )
            canvas.drawText(
                drawableText,
                rectF.left + BOUNDING_RECT_TEXT_PADDING, // Kleiner Einzug
                rectF.top - BOUNDING_RECT_TEXT_PADDING, // Positioniere über der Box
                textPaint
            )
        }
    }

    /**
     * Berechnet die Transformationsmatrix, um das 'sourceImage' an die
     * aktuelle View-Größe anzupassen, entsprechend dem Verhalten von ScaleType.FILL_CENTER.
     */
    private fun updateTransformationMatrix() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Prüfe auf gültige Dimensionen, um Fehler zu vermeiden
        if (viewWidth <= 0f || viewHeight <= 0f || sourceImageWidth <= 0 || sourceImageHeight <= 0) {
            imageTransformMatrix.reset() // Setze Matrix zurück, wenn Dimensionen ungültig sind
            return
        }

        val imgWidth = sourceImageWidth.toFloat()
        val imgHeight = sourceImageHeight.toFloat()

        val scaleFactor: Float
        var postTranslateX = 0f
        var postTranslateY = 0f

        // Berechne Skalierungsfaktor und Offset, um FILL_CENTER zu simulieren
        if (imgWidth * viewHeight > viewWidth * imgHeight) {
            // Bild ist (relativ) breiter als die View -> Höhe der View bestimmt die Skalierung
            scaleFactor = viewHeight / imgHeight
            postTranslateX = (viewWidth - imgWidth * scaleFactor) * 0.5f // Zentriere horizontal
            postTranslateY = 0f
        } else {
            // Bild ist (relativ) höher als die View -> Breite der View bestimmt die Skalierung
            scaleFactor = viewWidth / imgWidth
            postTranslateX = 0f
            postTranslateY = (viewHeight - imgHeight * scaleFactor) * 0.5f // Zentriere vertikal
        }

        // Setze die Matrix: zuerst skalieren, dann verschieben
        imageTransformMatrix.reset()
        imageTransformMatrix.setScale(scaleFactor, scaleFactor)
        imageTransformMatrix.postTranslate(postTranslateX, postTranslateY)
    }


    /**
     * Setzt die Ergebnisse der Objekterkennung und die Dimensionen des Bildes,
     * auf dem die Erkennung stattfand.
     */
    fun setResults(boundingBoxes: List<BoundingBox>, imageWidth: Int, imageHeight: Int) {
        results = boundingBoxes
        setSourceImageDimensions(imageWidth, imageHeight) // Speichere die Dimensionen
        postInvalidate() // Fordere Neuzeichnen im UI-Thread an
    }

    /**
     * Setzt das Spur-Overlay-Bitmap und die Dimensionen des Bildes,
     * auf dem die Spurerkennung stattfand.
     */
    fun setLaneBitmap(bitmap: Bitmap?, imageWidth: Int, imageHeight: Int) {
        // Optional: Altes Bitmap recyceln, wenn es nicht mehr gebraucht wird? Vorsicht!
        // laneBitmap?.recycle()
        laneBitmap = bitmap
        setSourceImageDimensions(imageWidth, imageHeight) // Speichere die Dimensionen
        postInvalidate() // Fordere Neuzeichnen im UI-Thread an
    }


    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 4 // Kleinere Padding für Text
    }
}