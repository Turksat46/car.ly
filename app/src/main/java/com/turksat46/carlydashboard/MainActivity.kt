package com.turksat46.carlydashboard // <-- Dein Paketname hier

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.* // Import für Matrix etc. in imageProxyToBitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.util.Size // Für TargetResolution
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy // Import für ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.*
import com.turksat46.carlydashboard.ui.theme.CarlyDashboardTheme // Dein Theme
import kotlinx.coroutines.delay
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream // Import für imageProxyToBitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt


enum class WarningLevel {
    None, Soft, Hard
}

class MainActivity : ComponentActivity(), Detector.DetectorListener {

    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var orientationEventListener: OrientationEventListener? = null

    private lateinit var laneSoftPlayer: MediaPlayer
    private lateinit var laneHardPlayer: MediaPlayer

    // --- UI States ---
    private val boundingBoxesState = mutableStateOf<List<BoundingBox>>(emptyList())
    private val laneBitmapState = mutableStateOf<Bitmap?>(null)
    private val laneDeviationState = mutableStateOf<Double?>(null)
    private val inferenceTimeState = mutableStateOf(0L)
    private val isGpuEnabledState = mutableStateOf(true)
    private val isLaneDetectionEnabledState = mutableStateOf(true)
    private val isDetectorInitialized = mutableStateOf(false)
    private val speedState = mutableStateOf(0.0f)
    private val physicalOrientationState = mutableStateOf(Configuration.ORIENTATION_PORTRAIT)
    private val warningLevelState = mutableStateOf(WarningLevel.None)
    private val isDebugModeState = mutableStateOf(false)

    // --- Neu: Speichert die Dimensionen des letzten analysierten Bildes ---
    // Wird benötigt, um dem OverlayView die korrekte Basisgröße mitzuteilen
    private var lastAnalyzedBitmapWidth = mutableStateOf(1)
    private var lastAnalyzedBitmapHeight = mutableStateOf(1)


    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        initializeDetector()
        createLocationCallback()
        createOrientationListener()
        initializeMediaPlayers()

        // OpenCV laden
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            // Hier könntest du versuchen, den OpenCV Manager zu nutzen oder eine Fehlermeldung anzeigen.
            // Für diese Demo gehen wir davon aus, dass die statische Initialisierung funktioniert.
            showError("OpenCV konnte nicht geladen werden!")
        } else {
            Log.i("OpenCV", "OpenCV loaded successfully!")
        }

        setContent {
            CarlyDashboardTheme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
                LaunchedEffect(Unit) {
                    if (!permissionsState.allPermissionsGranted) {
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        permissionsState.allPermissionsGranted && isDetectorInitialized.value -> {
                            CameraDetectionScreen(
                                boundingBoxes = boundingBoxesState.value,
                                inferenceTime = inferenceTimeState.value,
                                isGpuEnabled = isGpuEnabledState.value,
                                isLaneDetectionEnabled = isLaneDetectionEnabledState.value,
                                speed = speedState.value,
                                physicalOrientation = physicalOrientationState.value,
                                isDebuggingEnabled = isDebugModeState.value,
                                onGpuToggle = ::toggleGpu, // Vereinfachte Übergabe
                                onLaneDetectionToggle = ::toggleLaneDetection,
                                onDebugViewToggle = ::toggleDebugView,
                                onBitmapAnalyzed = ::analyzeBitmap, // Direkte Referenz
                                analysisExecutor = cameraExecutor,
                                laneBitmap = laneBitmapState.value,
                                laneDeviation = laneDeviationState.value,
                                warningLevel = warningLevelState.value,
                                // Übergebe die Dimensionen an das Composable
                                analyzedBitmapWidth = lastAnalyzedBitmapWidth.value,
                                analyzedBitmapHeight = lastAnalyzedBitmapHeight.value
                            )
                        }
                        permissionsState.allPermissionsGranted && !isDetectorInitialized.value -> {
                            LoadingIndicator("Initialisiere Detektor...")
                        }
                        else -> {
                            LoadingIndicator("Kamera- & Standortberechtigung erforderlich.")
                        }
                    }
                }

                // Lifecycle Management
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, permissionsState.allPermissionsGranted) {
                    val observer = LifecycleEventObserver { _, event ->
                        handleLifecycleEvent(event, permissionsState.allPermissionsGranted)
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    if (permissionsState.allPermissionsGranted) { // Initial start if already granted
                        orientationEventListener?.enable()
                        startLocationUpdates()
                    }
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        orientationEventListener?.disable()
                        stopLocationUpdates()
                    }
                }

                // Warn-Flash Reset
                LaunchedEffect(warningLevelState.value) {
                    if (warningLevelState.value != WarningLevel.None) {
                        delay(350L)
                        if (warningLevelState.value != WarningLevel.None) { // Erneute Prüfung, falls sich Zustand geändert hat
                            warningLevelState.value = WarningLevel.None
                        }
                    }
                }
            }
        }
    }

    private fun initializeMediaPlayers() {
        try {
            laneSoftPlayer = MediaPlayer.create(this, R.raw.lanesoftwarning)
            laneHardPlayer = MediaPlayer.create(this, R.raw.lanehardwarning)
            laneSoftPlayer.setVolume(1f, 1f)
            laneHardPlayer.setVolume(1f, 1f)
            // Start ist hier nicht ideal, besser bei Bedarf in analyzeBitmap
            // laneSoftPlayer.prepareAsync() // Besser prepareAsync oder create verwenden
            // laneHardPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e("MediaPlayer", "Error initializing media players", e)
            showError("Warn Töne konnten nicht geladen werden.")
        }
    }

    private fun showError(message: String) {
        // Implementiere eine Methode, um dem Benutzer Fehler anzuzeigen
        // z.B. mit einem Toast oder einer Snackbar
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun handleLifecycleEvent(event: Lifecycle.Event, hasPermissions: Boolean) {
        if (hasPermissions) {
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    orientationEventListener?.enable()
                    startLocationUpdates()
                    // MediaPlayers ggf. neu starten oder vorbereiten, wenn sie gestoppt wurden
                }
                Lifecycle.Event.ON_PAUSE -> {
                    orientationEventListener?.disable()
                    stopLocationUpdates()
                    // MediaPlayers stoppen und freigeben, um Ressourcen zu sparen
                }
                Lifecycle.Event.ON_DESTROY -> {
                    releaseMediaPlayers()
                }
                else -> {}
            }
        } else { // Keine Berechtigungen
            orientationEventListener?.disable()
            stopLocationUpdates()
            releaseMediaPlayers()
        }
    }

    private fun releaseMediaPlayers() {
        if (::laneSoftPlayer.isInitialized) {
            try { laneSoftPlayer.release() } catch (e: Exception) {Log.e("MediaPlayer", "Error releasing soft player", e)}
        }
        if (::laneHardPlayer.isInitialized) {
            try { laneHardPlayer.release() } catch (e: Exception) {Log.e("MediaPlayer", "Error releasing hard player", e)}
        }
    }

    private fun initializeDetector() {
        if (!isDetectorInitialized.value) { // Nur initialisieren, wenn noch nicht geschehen
            cameraExecutor.execute {
                try {
                    detector = Detector(
                        context = baseContext,
                        modelPath = Constants.MODEL_PATH,
                        labelPath = Constants.LABELS_PATH,
                        detectorListener = this
                    )
                    runOnUiThread {
                        isDetectorInitialized.value = true
                        Log.d("MainActivity", "Detector initialized successfully.")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error initializing Detector", e)
                    runOnUiThread { showError("Fehler beim Initialisieren des Detektors.") }
                }
            }
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    if (location.hasSpeed()) {
                        speedState.value = location.speed
                    }
                }
            }
            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    Log.w("Location", "Location not available.")
                    speedState.value = 0.0f
                }
            }
        }
    }

    private fun createOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newOrientation = when (orientation) {
                    in 45..134 -> Configuration.ORIENTATION_LANDSCAPE
                    in 225..314 -> Configuration.ORIENTATION_LANDSCAPE
                    else -> Configuration.ORIENTATION_PORTRAIT
                }
                if (newOrientation != physicalOrientationState.value) {
                    physicalOrientationState.value = newOrientation
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500).build()
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.d("Location", "Location updates requested.")
        } catch (e: SecurityException) { Log.e("Location", "Failed to start location updates.", e) }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d("Location", "Location updates stopped.")
        } catch (e: Exception) { Log.e("Location", "Error stopping location updates", e) }
    }

    private fun toggleGpu(enabled: Boolean) {
        if (isGpuEnabledState.value != enabled) { // Nur neu starten, wenn Wert sich ändert
            isGpuEnabledState.value = enabled
            boundingBoxesState.value = emptyList() // Reset UI states on toggle
            inferenceTimeState.value = 0L
            laneBitmapState.value = null
            laneDeviationState.value = null
            cameraExecutor.submit {
                if (::detector.isInitialized) {
                    try {
                        Log.d("MainActivity", "Restarting detector with GPU=${enabled}...")
                        detector.restart(enabled)
                        Log.d("MainActivity", "Detector restarted.")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error restarting detector", e)
                        runOnUiThread { showError("Fehler beim Umschalten der GPU.") }
                    }
                }
            }
        }
    }


    private fun toggleLaneDetection(enabled: Boolean) {
        isLaneDetectionEnabledState.value = enabled
        if (!enabled) { // Wenn deaktiviert, Overlay löschen
            laneBitmapState.value = null
            laneDeviationState.value = null
            warningLevelState.value = WarningLevel.None
        }
    }

    private fun toggleDebugView(enabled: Boolean) {
        isDebugModeState.value = enabled
        // Setze die Debug-Flags im LaneDetector entsprechend
        // Diese Flags müssen im LaneDetector existieren und public sein (oder über eine Methode setzbar)
        LaneDetector.drawDebugMask = enabled
        LaneDetector.drawDebugEdges = enabled
        LaneDetector.drawDebugHoughLines = enabled
        // LaneDetector.drawDebugWindows = enabled // Falls du den Polyfit-Code nutzt
        // LaneDetector.drawPixelPoints = enabled // Falls du den Polyfit-Code nutzt
    }

    // Wird von CameraX aufgerufen für jeden Frame
    private fun analyzeBitmap(bitmap: Bitmap) {
        // Speichere die Dimensionen dieses Bitmaps für das OverlayView
        lastAnalyzedBitmapWidth.value = bitmap.width
        lastAnalyzedBitmapHeight.value = bitmap.height

        if (::detector.isInitialized && !cameraExecutor.isShutdown) {
            // Objekterkennung ausführen
            detector.detect(bitmap) // Diese löst onDetect/onEmptyDetect aus

            // Spurerkennung ausführen (wenn aktiviert)
            if (isLaneDetectionEnabledState.value) {
                // Führe die Spurerkennung in einem Hintergrundthread aus,
                // aber aktualisiere den State im UI-Thread.
                // cameraExecutor.execute { // Optional: Wenn LaneDetector zu langsam ist
                val laneDetectionResult: Pair<Bitmap?, Double?>? = try {
                    LaneDetector.detectLanes(bitmap)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error calling LaneDetector.detectLanes", e)
                    null
                }

                // Aktualisiere UI State im UI Thread
                runOnUiThread {
                    laneBitmapState.value = laneDetectionResult?.first
                    laneDeviationState.value = laneDetectionResult?.second
                    handleLaneDeviation(laneDetectionResult?.second)
                }
                // } // Ende von cameraExecutor.execute (optional)
            } else {
                // Stelle sicher, dass die Spurdaten gelöscht werden, wenn deaktiviert
                if (laneBitmapState.value != null || laneDeviationState.value != null) {
                    runOnUiThread {
                        laneBitmapState.value = null
                        laneDeviationState.value = null
                        warningLevelState.value = WarningLevel.None
                    }
                }
            }
        }
        // Recycle nicht das Bitmap hier, es wird evtl. noch von LaneDetector oder UI gebraucht
    }

    // Hilfsfunktion zur Handhabung der Spurerkennungsergebnisse
    private fun handleLaneDeviation(deviation: Double?) {
        try {
            if (deviation != null) {
                val absDeviation = abs(deviation)
                var newWarningLevel = WarningLevel.None
                var playHard = false
                var playSoft = false

                if (absDeviation > 0.14) { // Harte Warnung Schwelle
                    Log.d("MainActivity", "Lane deviation: $deviation critical!")
                    newWarningLevel = WarningLevel.Hard
                    playHard = true
                } else if (absDeviation > 0.07) { // Weiche Warnung Schwelle
                    Log.d("MainActivity", "Lane deviation: $deviation moderate.")
                    // Nur weiche Warnung, wenn nicht schon hart
                    if (warningLevelState.value != WarningLevel.Hard) {
                        newWarningLevel = WarningLevel.Soft
                        playSoft = true
                    } else {
                        newWarningLevel = WarningLevel.Hard // Bleibe bei Hard, wenn schon aktiv
                    }
                }

                // Spiele Sounds nur, wenn nötig und Player bereit
                if (playHard && ::laneHardPlayer.isInitialized) { // && laneHardPlayer.isReady ?
                    if (!laneHardPlayer.isPlaying) {
                        try {
                            laneHardPlayer.seekTo(0) // Zurückspulen vor dem Start
                            laneHardPlayer.start()
                        } catch (e: IllegalStateException){ Log.e("MediaPlayer", "Hard player start failed", e)}
                    }
                    // Wenn Hard spielt, Soft nicht starten
                    if (::laneSoftPlayer.isInitialized && laneSoftPlayer.isPlaying) {
                        try { laneSoftPlayer.pause() } catch(e: IllegalStateException){ Log.e("MediaPlayer", "Soft player pause failed", e)}
                    }
                } else if (playSoft && ::laneSoftPlayer.isInitialized) { // && laneSoftPlayer.isReady ?
                    // Spiele Soft nur, wenn Hard nicht spielt
                    if (::laneHardPlayer.isInitialized && !laneHardPlayer.isPlaying && !laneSoftPlayer.isPlaying) {
                        try {
                            laneSoftPlayer.seekTo(0)
                            laneSoftPlayer.start()
                        } catch (e: IllegalStateException){ Log.e("MediaPlayer", "Soft player start failed", e)}
                    }
                }

                // Aktualisiere den WarningLevel State nur, wenn er sich ändert
                if (warningLevelState.value != newWarningLevel) {
                    warningLevelState.value = newWarningLevel
                }

            } else {
                // Keine Abweichung erkannt, setze Warnung zurück (wird durch LaunchedEffect erledigt)
                // warningLevelState.value = WarningLevel.None // Nicht hier, LaunchedEffect macht das
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Error checking lane deviation or playing sound.", e)
            // warningLevelState.value = WarningLevel.None // Sicherstellen, dass bei Fehler kein alter Zustand bleibt? Nein, LA machts.
        }
    }


    // --- Detector.DetectorListener Implementierung ---
    override fun onEmptyDetect() {
        // Wird vom Detector aufgerufen, wenn nichts erkannt wird
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                boundingBoxesState.value = emptyList()
                // Hier NICHT die Lane-States zurücksetzen, die kommen von analyzeBitmap
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        // Wird vom Detector aufgerufen, wenn Objekte erkannt wurden
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                boundingBoxesState.value = boundingBoxes
                inferenceTimeState.value = inferenceTime
                // Hier NICHT die Lane-States beeinflussen
            }
        }
    }

    // --- Activity Lifecycle ---
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        orientationEventListener?.disable()
        releaseMediaPlayers() // MediaPlayers freigeben
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
            Log.d("MainActivity", "CameraExecutor shutdown.")
        }
        if (::detector.isInitialized) {
            try {
                detector.close()
                Log.d("MainActivity", "Detector closed.")
            } catch (e: Exception) { Log.e("MainActivity", "Error closing detector", e) }
        }
        Log.d("MainActivity", "onDestroy completed.")
    }
}

// -------------------- Composables --------------------

@Composable
fun LoadingIndicator(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { // Column hinzugefügt
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp)) // Abstand
            Text(text)
        }
    }
}


@Composable
fun CameraDetectionScreen(
    boundingBoxes: List<BoundingBox>,
    inferenceTime: Long,
    laneBitmap: Bitmap?,
    laneDeviation: Double?,
    isGpuEnabled: Boolean,
    isLaneDetectionEnabled: Boolean,
    isDebuggingEnabled: Boolean,
    speed: Float,
    physicalOrientation: Int,
    warningLevel: WarningLevel,
    // Neu: Übergebe die Dimensionen des analysierten Bitmaps
    analyzedBitmapWidth: Int,
    analyzedBitmapHeight: Int,
    onGpuToggle: (Boolean) -> Unit,
    onLaneDetectionToggle: (Boolean) -> Unit,
    onDebugViewToggle: (Boolean) -> Unit,
    onBitmapAnalyzed: (Bitmap) -> Unit,
    analysisExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Erstelle OverlayView nur einmal
    val overlayView = remember { OverlayView(context, null) }

    LaunchedEffect(cameraProviderFuture, previewView) {
        previewView?.let { pv ->
            val cameraProvider = try { cameraProviderFuture.get() } catch (e: Exception) { null }
            cameraProvider?.let {
                bindCameraUseCases(context, it, lifecycleOwner, pv, onBitmapAnalyzed, analysisExecutor)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "WarningPulse")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = if(warningLevel == WarningLevel.None) 0.05f else if (warningLevel == WarningLevel.Soft) 0.4f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = LinearEasing), // Dauer leicht erhöht
            repeatMode = RepeatMode.Reverse
        ), label = "WarningAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { previewView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Detection Overlay
        AndroidView(
            factory = { overlayView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // *** WICHTIG: Übergebe die Dimensionen an OverlayView ***
                view.setResults(boundingBoxes, analyzedBitmapWidth, analyzedBitmapHeight)
                view.setLaneBitmap(laneBitmap, analyzedBitmapWidth, analyzedBitmapHeight)
            }
        )

        // Pulsating Warning Overlay
        if (warningLevel != WarningLevel.None) {
            val baseColor = if (warningLevel == WarningLevel.Soft) Color.Yellow else Color.Red
            val gradient = Brush.horizontalGradient(
                colors = listOf(baseColor.copy(alpha = animatedAlpha), Color.Transparent, baseColor.copy(alpha = animatedAlpha))
            )
            Box(modifier = Modifier.fillMaxSize().background(gradient))
        }

        // UI Elemente (Inferenzzeit, Geschwindigkeit, Toggles, Deviation Indicator)
        InfoAndControlsOverlay(
            inferenceTime = inferenceTime,
            speed = speed,
            laneDeviation = laneDeviation,
            isGpuEnabled = isGpuEnabled,
            isLaneDetectionEnabled = isLaneDetectionEnabled,
            isDebuggingEnabled = isDebuggingEnabled,
            physicalOrientation = physicalOrientation,
            onGpuToggle = onGpuToggle,
            onLaneDetectionToggle = onLaneDetectionToggle,
            onDebugViewToggle = onDebugViewToggle
        )
    }
}

@Composable
fun BoxScope.InfoAndControlsOverlay( // Use BoxScope for alignment
    inferenceTime: Long,
    speed: Float,
    laneDeviation: Double?,
    isGpuEnabled: Boolean,
    isLaneDetectionEnabled: Boolean,
    isDebuggingEnabled: Boolean,
    physicalOrientation: Int,
    onGpuToggle: (Boolean) -> Unit,
    onLaneDetectionToggle: (Boolean) -> Unit,
    onDebugViewToggle: (Boolean) -> Unit
) {
    // --- Inferenzzeit ---
    if (inferenceTime > 0) {
        Text(
            text = "$inferenceTime ms",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)) // Kleinere Rundung
                .padding(horizontal = 6.dp, vertical = 2.dp), // Weniger Padding
            color = Color.White,
            fontSize = 12.sp
        )
    }

    // --- Geschwindigkeit ---
    val speedAlignment = if (physicalOrientation == Configuration.ORIENTATION_LANDSCAPE)
        Alignment.TopStart else Alignment.TopCenter
    Card(
        modifier = Modifier
            .align(speedAlignment)
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp), // Etwas rundere Ecken
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)) // Dunklerer Hintergrund
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), // Angepasstes Padding
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val speedKmh = (speed * 3.6f).roundToInt()
            Text(
                text = "$speedKmh",
                color = Color.White,
                fontSize = 32.sp, // Etwas kleiner
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "km/h", // Einheit separat
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
            // Optional: Limit hinzufügen, wenn verfügbar
            // Text(text = "Limit: --", ...)
        }
    }

    // --- Spurabweichungsanzeige ---
    laneDeviation?.let { deviation ->
        LaneCenterIndicator(
            deviation = deviation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp) // Etwas höher, um Platz für Controls zu machen
                .fillMaxWidth(0.5f) // Etwas schmaler
                .height(16.dp) // Etwas flacher
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
        )
    }

    // --- Steuerungs-Toggles ---
    val controlsAlignment = if (physicalOrientation == Configuration.ORIENTATION_LANDSCAPE)
        Alignment.BottomStart else Alignment.BottomCenter
    Card(
        modifier = Modifier
            .align(controlsAlignment)
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
    ) {
        // Flexibles Layout für Controls (horizontal im Querformat, vertikal im Hochformat?)
        // Hier einfach Column belassen, passt meistens.
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            ControlToggleRow("GPU", isGpuEnabled, onGpuToggle)
            ControlToggleRow("Spur", isLaneDetectionEnabled, onLaneDetectionToggle)
            ControlToggleRow("Debug", isDebuggingEnabled, onDebugViewToggle)
        }
    }
}


// Hilfs-Composable für einen einzelnen Toggle
@Composable
fun ControlToggleRow(label: String, isChecked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 0.dp) // Weniger vertikales Padding
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(50.dp)) // Feste Breite für Label
        Spacer(modifier = Modifier.width(4.dp))
        Switch(
            checked = isChecked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary, // Theme-Farbe
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.DarkGray.copy(alpha = 0.5f)
            ),
            modifier = Modifier.size(width = 40.dp, height = 20.dp) // Kompakterer Switch
        )
    }
}


@Composable
fun LaneCenterIndicator(
    deviation: Double, // -1.0 (links) to 1.0 (rechts)
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Mittellinie
        Divider(
            color = Color.White.copy(alpha = 0.4f), // Heller
            thickness = 1.dp, // Dünner
            modifier = Modifier.fillMaxHeight().width(1.dp).align(Alignment.Center)
        )
        // Indikator
        val horizontalBias = deviation.coerceIn(-1.0, 1.0).toFloat()
        Box(
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = horizontalBias, verticalBias = 0f))
                .width(6.dp) // Schmaler
                .fillMaxHeight(0.7f)
                .background(Color.Yellow, CircleShape) // Kreis bleibt gut
        )
    }
}
// -------------------- CameraX Binding Function --------------------

private fun bindCameraUseCases(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onBitmapAnalyzed: (Bitmap) -> Unit,
    executor: ExecutorService
) {
    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .build()

    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
    }

    val imageAnalysis = ImageAnalysis.Builder()
        // Zielauflösung kann helfen, Performance zu stabilisieren, falls nötig
        // .setTargetResolution(Size(1280, 720))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Explizit für YUV
        .build()

    imageAnalysis.setAnalyzer(executor) { imageProxy ->
        // Die Konvertierung + Analyse geschieht hier
        val bitmap = imageProxyToBitmap(imageProxy) // imageProxy wird IN dieser Funktion geschlossen!

        if (bitmap != null) {
            onBitmapAnalyzed(bitmap) // Rufe die Analysefunktion der Activity auf
        } else {
            Log.w("bindCameraUseCases", "Bitmap conversion failed or imageProxy already closed.")
        }
    }

    try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner, cameraSelector, preview, imageAnalysis
        )
        Log.d("bindCameraUseCases", "Camera use cases bound.")
    } catch (exc: Exception) {
        Log.e("bindCameraUseCases", "Use case binding failed", exc)
    }
}

