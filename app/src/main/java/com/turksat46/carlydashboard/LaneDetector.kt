package com.turksat46.carlydashboard // <-- Sicherstellen, dass der Paketname stimmt

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*
import androidx.core.graphics.createBitmap

object LaneDetector {

    // --- Konfigurationsparameter (Inspiriert von MainActivity.java) ---
    private const val ROI_TOP_PERCENT_TO_IGNORE = 0.55 // Ähnlich zu `top = rows / 2.5`
    private const val LANE_CENTER_Y_FACTOR = 0.90  // Y-Position für Abweichungsberechnung
    private const val HORIZONTAL_ZONE_EDGE_FACTOR = 0.1 // Ähnlich zu `zoneX = left*2.5` wenn left=cols/8? (Angepasst)

    // HSV Farbfilter - Fokus auf SEHR HELLE Bereiche (wie Java's Threshold 210)
    private val WHITE_LOW_HSV = Scalar(0.0, 0.0, 205.0) // Sehr hoher Value-Wert
    private val WHITE_HIGH_HSV = Scalar(180.0, 50.0, 255.0)

    // Morphologie Kernel (wie in Java's Erode/Dilate)
    private val MORPH_KERNEL_SIZE = Size(3.0, 3.0)

    // Canny Edge Detection Parameter (wie in Java)
    private const val CANNY_THRESHOLD_LOW = 50.0
    private const val CANNY_THRESHOLD_HIGH = 150.0

    // HoughLinesP Parameter (wie in Java)
    private const val HOUGH_RHO = 1.0
    private const val HOUGH_THETA = Math.PI / 180
    private const val HOUGH_THRESHOLD = 50
    private const val HOUGH_MIN_LINE_LENGTH = 25.0
    private const val HOUGH_MAX_LINE_GAP = 85.0

    // Steigungsfilter (wie in Java's getAverageSlopes)
    private const val SLOPE_THRESHOLD_LOW_JAVA = 0.375
    private const val SLOPE_THRESHOLD_HIGH_JAVA = 2.6 // Absolutwert

    // Mittelungsparameter (MIN_SEGMENTS anpassen, da keine robuste Mittelung)
    private const val MIN_SEGMENTS_FOR_SIMPLE_AVERAGE = 1 // Nur 1 Segment reicht für einfachen Durchschnitt

    // Zeichnungsfarben & Dicke
    private val LANE_LINE_COLOR = Scalar(0.0, 255.0, 0.0, 255.0) // Grün
    private const val LANE_LINE_THICKNESS = 8
    private val CENTER_LINE_COLOR = Scalar(255.0, 0.0, 0.0, 255.0) // Blau (Fahrzeug)
    private val LANE_CENTER_COLOR = Scalar(0.0, 0.0, 255.0, 255.0) // Rot (Spur)
    private const val CENTER_LINE_THICKNESS = 4
    private val LANE_AREA_COLOR = Scalar(0.0, 100.0, 0.0, 80.0) // Transparent Grün

    // Zustand für temporale Glättung (Slope/Intercept)
    private var previousLeftAvgParams: DoubleArray? = null // [avg_slope, avg_interceptRoi]
    private var previousRightAvgParams: DoubleArray? = null
    private const val SMOOTHING_FACTOR = 0.4 // Kann angepasst werden

    private const val TAG = "LaneDetectorJavaStyle"

    // Optional: Debug-Flags
    var drawDebugMask = false
    var drawDebugEdges = false
    var drawDebugHoughLines = false
    private val DEBUG_HOUGH_COLOR = Scalar(255.0, 0.0, 255.0, 150.0) // Magenta

    /**
     * Hauptfunktion zur Erkennung von Fahrspuren,
     * implementiert den Algorithmus ähnlich zu MainActivity.java.
     *
     * @param inputBitmap Das Eingabebild.
     * @return Ein Paar: (Overlay-Bitmap, Normalisierte Abweichung) oder null bei Fehler.
     */
    fun detectLanes(inputBitmap: Bitmap): Pair<Bitmap?, Double?>? {
        var originalMat: Mat? = null; var roiMat: Mat? = null; var hsvMat: Mat? = null
        var whiteMask: Mat? = null; var morphMask: Mat? = null; var edges: Mat? = null
        var houghLines: Mat? = null
        var transparentOverlayMat: Mat? = null; var laneAreaMat: Mat? = null
        var outputBitmap: Bitmap? = null
        var normalizedDeviation: Double? = null
        var currentLeftAvgParams: DoubleArray? = null // [avg_slope, avg_interceptRoi]
        var currentRightAvgParams: DoubleArray? = null
        var finalLeftAvgParams: DoubleArray? = null
        var finalRightAvgParams: DoubleArray? = null
        // Liste für Debug-Zeichnung der Hough-Linien
        val debugHoughLinesToDraw = mutableListOf<DoubleArray>()


        try {
            // --- Schritt 0: Vorbereitung ---
            val startTime = System.currentTimeMillis()
            originalMat = Mat()
            Utils.bitmapToMat(inputBitmap, originalMat)
            if (originalMat.empty()) { Log.e(TAG, "Input Bitmap leer."); return null }
            // KEIN GaussianBlur auf Original hier, da Java es nur auf Grau anwendet (für HoughCircles)

            val imgHeight = originalMat.rows()
            val imgWidth = originalMat.cols()
            if (imgHeight <= 0 || imgWidth <= 0) { Log.e(TAG, "Ungültige Dimensionen."); return null }

            // --- Schritt 1: ROI ---
            val roiYStart = (imgHeight * ROI_TOP_PERCENT_TO_IGNORE).roundToInt()
            val roiHeight = imgHeight - roiYStart
            if (roiHeight <= 0 || imgWidth <= 0) { Log.w(TAG, "Ungültige ROI."); return Pair(null, null) }
            val roiRect = Rect(0, roiYStart, imgWidth, roiHeight)
            // Erstelle eine Kopie der ROI für Modifikationen, die das Original nicht beeinflussen sollen
            roiMat = Mat(originalMat, roiRect).clone() // CLONE, um Original nicht zu ändern

            // --- Schritt 2: Farbfilterung (Hoher Helligkeitswert für Weiß) ---
            hsvMat = Mat(); Imgproc.cvtColor(roiMat, hsvMat, Imgproc.COLOR_RGB2HSV)
            whiteMask = Mat(); Core.inRange(hsvMat, WHITE_LOW_HSV, WHITE_HIGH_HSV, whiteMask)

            // --- Schritt 3: Morphologische Operationen (Erode + Dilate wie in Java) ---
            morphMask = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, MORPH_KERNEL_SIZE)
            Imgproc.erode(whiteMask, morphMask, kernel)
            Imgproc.dilate(morphMask, morphMask, kernel)
            kernel.release() // Kernel freigeben

            // --- Schritt 4: Kantenerkennung (Canny auf morphMask) ---
            edges = Mat(); Imgproc.Canny(morphMask, edges, CANNY_THRESHOLD_LOW, CANNY_THRESHOLD_HIGH)

            // --- Schritt 5: Hough Linien Transformation (Parameter wie Java) ---
            houghLines = Mat()
            Imgproc.HoughLinesP(
                edges, houghLines, HOUGH_RHO, HOUGH_THETA, HOUGH_THRESHOLD,
                HOUGH_MIN_LINE_LENGTH, HOUGH_MAX_LINE_GAP
            )

            // --- Schritt 6: Linien filtern & einfacher Durchschnitt (wie Java's getAverageSlopes) ---
            val leftSegments = mutableListOf<Pair<Double, Double>>() // Pair(slope, interceptRoi)
            val rightSegments = mutableListOf<Pair<Double, Double>>()
            val zoneXLeftEdge = imgWidth * HORIZONTAL_ZONE_EDGE_FACTOR
            val zoneXRightEdge = imgWidth * (1.0 - HORIZONTAL_ZONE_EDGE_FACTOR)

            if (!houghLines.empty()) {
                for (i in 0 until houghLines.rows()) {
                    val points = houghLines[i, 0] ?: continue; if (points.size < 4) continue
                    val x1 = points[0]; val y1 = points[1]; val x2 = points[2]; val y2 = points[3]

                    if (drawDebugHoughLines) {
                        debugHoughLinesToDraw.add(doubleArrayOf(x1, y1, x2, y2)) // Speichere ROI-Koordinaten
                    }

                    if (abs(x2 - x1) < 1e-6) continue // Vertikale Linie ignorieren

                    val slope = (y2 - y1) / (x2 - x1)
                    val absSlope = abs(slope)

                    // Steigungsfilter wie in Java
                    if (slope > SLOPE_THRESHOLD_LOW_JAVA && slope < SLOPE_THRESHOLD_HIGH_JAVA) { // Rechte Spur
                        // Horizontaler Zonenfilter (prüfe, ob Linie *nicht* zu weit rechts beginnt/endet)
                        if (x1 < zoneXRightEdge || x2 < zoneXRightEdge) {
                            val interceptRoi = y1 - slope * x1
                            rightSegments.add(Pair(slope, interceptRoi))
                        }
                    } else if (slope < -SLOPE_THRESHOLD_LOW_JAVA && slope > -SLOPE_THRESHOLD_HIGH_JAVA) { // Linke Spur
                        // Horizontaler Zonenfilter (prüfe, ob Linie *nicht* zu weit links beginnt/endet)
                        if (x1 > zoneXLeftEdge || x2 > zoneXLeftEdge) {
                            val interceptRoi = y1 - slope * x1
                            leftSegments.add(Pair(slope, interceptRoi))
                        }
                    }
                }
            }

            // Berechne einfachen Durchschnitt (wie in Java)
            currentLeftAvgParams = calculateSimpleAverage(leftSegments)
            currentRightAvgParams = calculateSimpleAverage(rightSegments)

            // --- Schritt 7: Glättung der Durchschnittsparameter ---
            // Glätte, wenn mindestens ein aktueller Durchschnitt gefunden wurde
            if (currentLeftAvgParams != null || currentRightAvgParams != null) {
                finalLeftAvgParams = smoothAverageParams(currentLeftAvgParams, previousLeftAvgParams)
                finalRightAvgParams = smoothAverageParams(currentRightAvgParams, previousRightAvgParams)
            } else { // Keinen neuen Durchschnitt -> Behalte die alten
                finalLeftAvgParams = previousLeftAvgParams
                finalRightAvgParams = previousRightAvgParams
            }

            // --- Schritt 8: Abweichung berechnen (Kotlin-Methode, aber mit avgParams) ---
            normalizedDeviation = calculateDeviation(finalLeftAvgParams, finalRightAvgParams, imgWidth, imgHeight, roiYStart)

            // --- Schritt 9: Zeichnen ---
            transparentOverlayMat = Mat(imgHeight, imgWidth, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
            laneAreaMat = Mat(imgHeight, imgWidth, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))

            // Debug-Zeichnungen (Maske, Kanten, Hough-Linien)
            if (drawDebugMask) {
                val maskRgba = Mat()
                Imgproc.cvtColor(morphMask, maskRgba, Imgproc.COLOR_GRAY2RGBA)
                // Zeichne nur im ROI-Bereich
                maskRgba.copyTo(transparentOverlayMat.submat(roiRect))
                maskRgba.release()
            }
            if (drawDebugEdges) {
                val edgesRgba = Mat()
                Imgproc.cvtColor(edges, edgesRgba, Imgproc.COLOR_GRAY2RGBA)
                Core.addWeighted(transparentOverlayMat.submat(roiRect), 1.0, edgesRgba, 1.0, 0.0, transparentOverlayMat.submat(roiRect))
                edgesRgba.release()
            }
            if (drawDebugHoughLines) {
                debugHoughLinesToDraw.forEach { line ->
                    val p1 = Point(line[0], line[1] + roiYStart)
                    val p2 = Point(line[2], line[3] + roiYStart)
                    Imgproc.line(transparentOverlayMat, p1, p2, DEBUG_HOUGH_COLOR, 1)
                }
            }


            // Zeichne finale Linien (basierend auf avgParams) und Fülle Bereich
            var pTopLeft: Point? = null; var pBottomLeft: Point? = null
            var pTopRight: Point? = null; var pBottomRight: Point? = null

            finalLeftAvgParams?.let { params ->
                val points = calculateLineEndPoints(params[0], params[1], roiYStart, imgHeight, imgWidth)
                pBottomLeft = points.first; pTopLeft = points.second
                if (pBottomLeft != null && pTopLeft != null) Imgproc.line(transparentOverlayMat, pBottomLeft, pTopLeft, LANE_LINE_COLOR, LANE_LINE_THICKNESS)
            }
            finalRightAvgParams?.let { params ->
                val points = calculateLineEndPoints(params[0], params[1], roiYStart, imgHeight, imgWidth)
                pBottomRight = points.first; pTopRight = points.second
                if (pBottomRight != null && pTopRight != null) Imgproc.line(transparentOverlayMat, pBottomRight, pTopRight, LANE_LINE_COLOR, LANE_LINE_THICKNESS)
            }

            // Fülle Lane Area (Kotlin-Style Zeichnung)
            if (pTopLeft != null && pBottomLeft != null && pTopRight != null && pBottomRight != null) {
                fillLaneArea(transparentOverlayMat, laneAreaMat, pBottomLeft, pBottomRight, pTopRight, pTopLeft)
            }

            // Zeichne Zentrierung (Kotlin-Style Zeichnung, mit avgParams)
            drawCenteringInfo(transparentOverlayMat, normalizedDeviation, finalLeftAvgParams, finalRightAvgParams, imgWidth, imgHeight, roiYStart)

            // --- Schritt 10: Konvertieren & Zustand speichern ---
            outputBitmap = createBitmap(imgWidth, imgHeight)
            Utils.matToBitmap(transparentOverlayMat, outputBitmap)

            previousLeftAvgParams = finalLeftAvgParams?.clone() // Speichere die Durchschnittsparameter
            previousRightAvgParams = finalRightAvgParams?.clone()

            val endTime = System.currentTimeMillis()
            Log.d(TAG, "Processing time: ${endTime - startTime} ms")

            return Pair(outputBitmap, normalizedDeviation)

        } catch (e: CvException) { Log.e(TAG, "OpenCV Fehler: ${e.message}", e); resetState(); return Pair(null, null) }
        catch (e: Exception) { Log.e(TAG, "Allg. Fehler: ${e.message}", e); resetState(); return Pair(null, null) }
        finally {
            // --- Ressourcen freigeben ---
            originalMat?.release(); roiMat?.release(); hsvMat?.release(); whiteMask?.release()
            morphMask?.release(); edges?.release(); houghLines?.release()
            transparentOverlayMat?.release(); laneAreaMat?.release()
        }
    }

    // --- Hilfsfunktionen (Einige angepasst/neu) ---

    private fun resetState() {
        previousLeftAvgParams = null
        previousRightAvgParams = null
    }

    private fun createEmptyOverlay(width: Int, height: Int): Bitmap? {
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.TRANSPARENT) }
        } catch (e: Exception) { Log.e(TAG, "Fehler Overlay Erstellung: ${e.message}"); null }
    }

    /** Berechnet den einfachen Durchschnitt von Steigung und Achsenabschnitt. */
    private fun calculateSimpleAverage(segments: List<Pair<Double, Double>>): DoubleArray? {
        if (segments.size < MIN_SEGMENTS_FOR_SIMPLE_AVERAGE) return null

        var sumSlope = 0.0
        var sumIntercept = 0.0
        segments.forEach {
            sumSlope += it.first
            sumIntercept += it.second
        }
        val count = segments.size.toDouble()
        return doubleArrayOf(sumSlope / count, sumIntercept / count)
    }

    /** Glättet die Durchschnitts-Parameter (Steigung, Achsenabschnitt). */
    private fun smoothAverageParams(currentParams: DoubleArray?, previousParams: DoubleArray?): DoubleArray? {
        return when {
            currentParams == null -> previousParams
            previousParams == null -> currentParams
            else -> {
                doubleArrayOf(
                    SMOOTHING_FACTOR * currentParams[0] + (1.0 - SMOOTHING_FACTOR) * previousParams[0], // Slope
                    SMOOTHING_FACTOR * currentParams[1] + (1.0 - SMOOTHING_FACTOR) * previousParams[1]  // Intercept
                )
            }
        }
    }

    /** Berechnet die Endpunkte einer GERADEN Linie (unten, oben). */
    private fun calculateLineEndPoints(slope: Double, interceptRoi: Double, roiYStart: Int, imgHeight: Int, imgWidth: Int): Pair<Point, Point> {
        // Unterer Punkt (am unteren Bildrand)
        val yBottom = (imgHeight - 1).toDouble()
        val xBottom = calculateX(yBottom, slope, interceptRoi, roiYStart)

        // Oberer Punkt (am oberen Rand der ROI)
        val yTop = roiYStart.toDouble()
        val xTop = calculateX(yTop, slope, interceptRoi, roiYStart)

        val bottomPoint = Point(clipX(xBottom, imgWidth), yBottom)
        val topPoint = Point(clipX(xTop, imgWidth), yTop)
        return Pair(bottomPoint, topPoint)
    }

    /** Berechnet X für eine GERADE Linie bei gegebenem Y. */
    private fun calculateX(yAbs: Double, slope: Double, interceptRoi: Double, roiYStart: Int): Double {
        if (abs(slope) < 1e-6) return Double.NaN // Vermeide Division durch Null
        // x = (y_abs - roiYStart - intercept_roi) / slope
        return (yAbs - roiYStart - interceptRoi) / slope
    }


    /** Füllt den Bereich zwischen den Linien. */
    private fun fillLaneArea(overlay: Mat, areaMat: Mat, pbl: Point?, pbr: Point?, ptr: Point?, ptl: Point?) {
        if (pbl == null || pbr == null || ptr == null || ptl == null) return
        MatOfPoint().use { laneContour -> // .use für automatisches Freigeben
            laneContour.fromList(listOf(pbl, pbr, ptr, ptl)) // Reihenfolge wichtig
            try {
                areaMat.setTo(Scalar(0.0,0.0,0.0,0.0))
                Imgproc.fillConvexPoly(areaMat, laneContour, LANE_AREA_COLOR)
                Core.addWeighted(areaMat, 1.0, overlay, 1.0, 0.0, overlay)
            } catch (e: CvException) { Log.w(TAG, "fillConvexPoly Fehler: ${e.message}") }
        }
    }

    /** Berechnet die normalisierte Abweichung (unverändert). */
    private fun calculateDeviation(leftParams: DoubleArray?, rightParams: DoubleArray?, imgWidth: Int, imgHeight: Int, roiYStart: Int): Double? {
        if (leftParams == null || rightParams == null) return null
        val yEval = imgHeight * LANE_CENTER_Y_FACTOR
        val leftX = calculateX(yEval, leftParams[0], leftParams[1], roiYStart)
        val rightX = calculateX(yEval, rightParams[0], rightParams[1], roiYStart)
        if (leftX.isNaN() || rightX.isNaN() || leftX >= rightX) return null
        val laneCenter = (leftX + rightX) / 2.0; val vehicleCenter = imgWidth / 2.0
        val deviation = vehicleCenter - laneCenter; val halfWidth = (rightX - leftX) / 2.0
        return if (halfWidth > 5) (deviation / halfWidth).coerceIn(-1.5, 1.5) else null
    }

    /** Zeichnet Zentrierungsinformationen (unverändert). */
    private fun drawCenteringInfo(overlay: Mat, deviation: Double?, leftParams: DoubleArray?, rightParams: DoubleArray?, w: Int, h: Int, roiY: Int) {
        deviation ?: return; if (leftParams == null || rightParams == null) return
        val vcX = w / 2.0; val lcY = h * LANE_CENTER_Y_FACTOR
        val lX = calculateX(lcY, leftParams[0], leftParams[1], roiY)
        val rX = calculateX(lcY, rightParams[0], rightParams[1], roiY)
        if (!lX.isNaN() && !rX.isNaN() && lX < rX) {
            val lcX = (lX + rX) / 2.0; val yOff = 25.0
            val vtp = Point(vcX, lcY - yOff); val vbp = Point(vcX, lcY + yOff)
            val ltp = Point(lcX, lcY - yOff); val lbp = Point(lcX, lcY + yOff)
            Imgproc.line(overlay, vtp, vbp, CENTER_LINE_COLOR, CENTER_LINE_THICKNESS)
            Imgproc.line(overlay, ltp, lbp, LANE_CENTER_COLOR, CENTER_LINE_THICKNESS)
            Imgproc.line(overlay, Point(vcX, lcY), Point(lcX, lcY), LANE_CENTER_COLOR, CENTER_LINE_THICKNESS / 2)
        }
    }

    /** Clipt X-Werte (unverändert). */
    private fun clipX(x: Double, maxWidth: Int): Double {
        if (x.isNaN()) return maxWidth / 2.0
        return x.coerceIn(0.0, (maxWidth - 1).toDouble())
    }

    // Hilfsfunktion für .use auf MatOfPoint (unverändert)
    inline fun <R> MatOfPoint.use(block: (MatOfPoint) -> R): R {
        var exception: Throwable? = null
        try { return block(this) }
        catch (e: Throwable) { exception = e; throw e }
        finally { if (exception == null) this.release() else try { this.release() } catch (closeException: Throwable) {} }
    }
}