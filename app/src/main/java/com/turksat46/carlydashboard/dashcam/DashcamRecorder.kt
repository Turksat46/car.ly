package com.turksat46.carlydashboard.dashcam


import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService

class DashcamRecorder(
    private val context: Context,
    private val cameraExecutor: ExecutorService // Du kannst den cameraExecutor von deiner MainActivity wiederverwenden
) {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    var isRecording: Boolean = false
        private set

    companion object {
        private const val TAG = "DashcamRecorder"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        lifecycleOwner: LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector // Du kannst den gleichen Selector wie für Preview/Analysis nehmen
    ) {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress.")
            return
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST)) // Oder eine andere Qualität wählen
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Kamera binden (nur VideoCapture, oder zusammen mit Preview/Analysis)
        // Diese Zeile ist ein Platzhalter. Du musst die Bindung in deiner Activity verwalten,
        // idealerweise zusammen mit deinen anderen UseCases (Preview, ImageAnalysis).
        // cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture /*, preview, imageAnalysis */)
        // Siehe Hinweis zur Integration unten.

        val name = "Dashcam-${SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CarlyDashboard") // Speicherort
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        currentRecording = videoCapture?.output
            ?.prepareRecording(context, mediaStoreOutputOptions)
            // Hier Audio aktivieren, falls benötigt und Berechtigung vorhanden
            // .withAudioEnabled()
            ?.start(ContextCompat.getMainExecutor(context), videoRecordEventListener)

        isRecording = true
        Log.d(TAG, "Recording started.")
        Toast.makeText(context, "Dashcam-Aufnahme gestartet", Toast.LENGTH_SHORT).show()
    }

    private val videoRecordEventListener = Consumer<VideoRecordEvent> { event ->
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "Recording started event")
            }
            is VideoRecordEvent.Finalize -> {
                if (!event.hasError()) {
                    val msg = "Videoaufnahme erfolgreich: ${event.outputResults.outputUri}"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    Log.d(TAG, msg)
                } else {
                    currentRecording?.close()
                    currentRecording = null
                    val msg = "Videoaufnahme Fehler: ${event.error}"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, msg)
                }
                isRecording = false // Setze isRecording hier zurück
            }
            is VideoRecordEvent.Status -> {
                // Gibt den aktuellen Status der Aufnahme aus, z.B. wie viele Bytes geschrieben wurden
                // Log.v(TAG, "Recording status: ${event.recordingStats.numBytesRecorded} bytes")
            }
            is VideoRecordEvent.Pause -> {
                Log.d(TAG, "Recording paused event")
            }
            is VideoRecordEvent.Resume -> {
                Log.d(TAG, "Recording resumed event")
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress to stop.")
            return
        }
        currentRecording?.stop()
        // currentRecording wird in VideoRecordEvent.Finalize auf null gesetzt und isRecording auf false
        Log.d(TAG, "Recording stopping...")
    }

    fun toggleRecording(
        lifecycleOwner: LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector
    ) {
        if (isRecording) {
            stopRecording()
        } else {
            // Wichtig: Stelle sicher, dass die Kamera korrekt gebunden wird, bevor startRecording aufgerufen wird.
            // Dies ist der knifflige Teil, wenn du Preview und ImageAnalysis bereits gebunden hast.
            // Du musst entweder alle UseCases (Preview, ImageAnalysis, VideoCapture) zusammen binden
            // oder die Kamera neu binden nur mit VideoCapture (was Preview unterbrechen würde).
            // Der ideale Weg ist, VideoCapture von Anfang an mit zu binden.
            rebindCameraForVideo(lifecycleOwner, cameraProvider, cameraSelector) {
                startRecording(lifecycleOwner, cameraProvider, cameraSelector)
            }
        }
    }

    // Diese Funktion ist ein Vorschlag, wie du die Kamera neu binden könntest.
    // Du musst sie an deine bestehende `bindCameraUseCases` Logik anpassen.
    private fun rebindCameraForVideo(
        lifecycleOwner: LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector,
        onBound: () -> Unit
    ) {
        // Unbind all first (oder spezifische UseCases)
        // cameraProvider.unbindAll() // Vorsicht: Dies stoppt auch dein Preview und ImageAnalysis

        // Erstelle den Recorder und VideoCapture UseCase
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            // Binde VideoCapture. Idealerweise bindest du hier AUCH deine bestehenden
            // Preview und ImageAnalysis UseCases, falls sie nicht schon gebunden sind
            // oder wenn du sie mit VideoCapture zusammen neu binden musst.
            // Für eine *einfache* Dashcam könntest du hier nur VideoCapture binden,
            // aber das stoppt andere UseCases.
            Log.d(TAG, "Attempting to bind VideoCapture...")

            // Dies ist der entscheidende Punkt: Wie bindest du VideoCapture,
            // ohne dein bestehendes Setup zu stören, oder indem du es bewusst neu konfigurierst.
            // Für die beste Nutzererfahrung sollten Preview, ImageAnalysis UND VideoCapture
            // zusammen gebunden werden, wenn die Dashcam-Aufnahme gestartet wird.
            // cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture, previewUseCase, imageAnalysisUseCase)

            // In deiner MainActivity hast du `bindCameraUseCases`. Du müsstest diese Funktion erweitern,
            // sodass sie optional auch einen VideoCapture UseCase akzeptiert und bindet.
            // Für den Moment gehe ich davon aus, dass du eine Methode hast, die das handhaben kann.
            // Wenn du *nur* Video aufnehmen willst und Preview/Analysis währenddessen nicht brauchst:
            // cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture)
            // Dies wird aber deine anderen UseCases (Preview, Detector) stoppen.

            // **WICHTIGER HINWEIS für die Integration:**
            // Die beste Methode ist, deine `bindCameraUseCases` in `MainActivity` so zu ändern,
            // dass sie `videoCapture` als optionalen Parameter nimmt und mitbindet.
            // Wenn `videoCapture` null ist, wird es nicht gebunden. Wenn es gesetzt ist, wird es gebunden.
            // Dann würdest du vor dem Aufruf von `startRecording` `videoCapture` initialisieren
            // und `bindCameraUseCases` erneut aufrufen (oder eine dedizierte Funktion dafür haben).

            // Hier simulieren wir, dass die Bindung extern gehandhabt wird.
            // In einer echten Integration würdest du hier `cameraProvider.bindToLifecycle(...)` aufrufen
            // mit *allen* benötigten UseCases.

            Log.i(TAG, "VideoCapture use case should be bound by MainActivity's logic now.")
            onBound() // Rufe onBound auf, damit startRecording fortfahren kann.

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed for VideoCapture", exc)
            Toast.makeText(context, "Dashcam konnte nicht gestartet werden: Bindungsfehler", Toast.LENGTH_LONG).show()
        }
    }


    fun getVideoCapture(): VideoCapture<Recorder>? {
        if (videoCapture == null) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
        }
        return videoCapture
    }

    fun releaseVideoCapture() {
        // videoCapture?.output?.close() // Schließt die Aufnahme, falls eine läuft
        videoCapture = null // Erlaube, dass es neu erstellt wird
    }
}