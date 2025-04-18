package com.turksat46.carlydashboard // <-- Dein Paketname hier

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.*
import com.turksat46.carlydashboard.ui.theme.CarlyDashboardTheme // Dein Theme
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

import androidx.compose.ui.graphics.Brush // <-- NEU: Import für Gradient
import kotlinx.coroutines.delay // <-- NEU: Import für delay in LaunchedEffect

import org.opencv.android.OpenCVLoader // Import hinzufügen

enum class WarningLevel {
    None, Soft, Hard
}

class MainActivity : ComponentActivity(), Detector.DetectorListener {

    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var orientationEventListener: OrientationEventListener? = null

    lateinit var laneSoftPlayer:MediaPlayer
    lateinit var laneHardPlayer:MediaPlayer

    // State für die UI-Updates
    private val boundingBoxesState = mutableStateOf<List<BoundingBox>>(emptyList())
    private val laneBitmapState = mutableStateOf<Bitmap?>(null) // <-- NEU: State für das Spur-Bitmap
    private val laneDeviationState = mutableStateOf<Double?>(null) // <-- NEU: State für Abweichung
    private val inferenceTimeState = mutableStateOf(0L)
    private val isGpuEnabledState = mutableStateOf(true)
    private val isLaneDetectionEnabledState = mutableStateOf(true)
    private val isDetectorInitialized = mutableStateOf(false)
    private val speedState = mutableStateOf(0.0f) // Geschwindigkeit in m/s
    private val physicalOrientationState = mutableStateOf(Configuration.ORIENTATION_PORTRAIT)
    private val warningLevelState = mutableStateOf(WarningLevel.None)


    @OptIn(ExperimentalPermissionsApi::class) // Benötigt für rememberMultiplePermissionsState
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Initialisierungen
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        initializeDetector()
        createLocationCallback()
        createOrientationListener()


        laneSoftPlayer = MediaPlayer.create(this, R.raw.lanesoftwarning)
        laneHardPlayer = MediaPlayer.create(this, R.raw.lanehardwarning)
        laneSoftPlayer.setVolume(1f, 1f)
        laneHardPlayer.setVolume(1f, 1f)
        laneSoftPlayer.start() // no need to call prepare(); create() does that for you
        laneHardPlayer.start()




        if (OpenCVLoader.initDebug()) {
            Log.i("OpenCV", "OpenCV loaded successfully")
            // Hier kannst du fortfahren, da OpenCV geladen ist.
            // Deine restlichen Initialisierungen können hier folgen.
        } else {
            Log.e("OpenCV", "OpenCV load failed!")
            // Hier solltest du den Fehler behandeln.
            // Vielleicht eine Meldung anzeigen oder OpenCV-Funktionen deaktivieren.
            // Fürs Erste reicht das Logging. Die App wird wahrscheinlich abstürzen,
            // wenn sie versucht, OpenCV ohne geladene Bibliothek zu verwenden.
        }

        setContent {
            CarlyDashboardTheme {
                // State für Berechtigungen
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )

                // Berechtigungen anfordern
                LaunchedEffect(Unit) {
                    if (!permissionsState.allPermissionsGranted) {
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // UI basierend auf Berechtigungen und Initialisierungsstatus anzeigen
                    when {
                        permissionsState.allPermissionsGranted && isDetectorInitialized.value -> {
                            CameraDetectionScreen(
                                boundingBoxes = boundingBoxesState.value,
                                inferenceTime = inferenceTimeState.value,
                                isGpuEnabled = isGpuEnabledState.value,
                                isLaneDetectionEnabled = isLaneDetectionEnabledState.value,
                                speed = speedState.value,
                                physicalOrientation = physicalOrientationState.value,
                                onGpuToggle = { enabled -> toggleGpu(enabled) },
                                onLaneDetectionToggle = { enabled -> toggleLaneDetection(enabled) },
                                onBitmapAnalyzed = { bitmap -> analyzeBitmap(bitmap) },
                                analysisExecutor = cameraExecutor,
                                laneBitmap = laneBitmapState.value,
                                laneDeviation = laneDeviationState.value,
                                warningLevel = warningLevelState.value // <-- NEU: WarningLevel übergeben
                            )
                        }
                        permissionsState.allPermissionsGranted && !isDetectorInitialized.value -> {
                            LoadingIndicator("Initialisiere Detektor...")
                        }
                        else -> {
                            LoadingIndicator("Kamera- und Standortberechtigung erforderlich.")
                        }
                    }
                }

                // Lifecycle-Management für Listener und Updates
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, permissionsState.allPermissionsGranted) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (permissionsState.allPermissionsGranted) { // Nur wenn Berechtigungen da sind
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> {
                                    orientationEventListener?.enable()
                                    startLocationUpdates()
                                }
                                Lifecycle.Event.ON_PAUSE -> {
                                    orientationEventListener?.disable()
                                    stopLocationUpdates()
                                }
                                else -> {}
                            }
                        } else {
                            orientationEventListener?.disable()
                            stopLocationUpdates()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)

                    // Initiales Starten, wenn Berechtigungen bereits erteilt sind
                    if (permissionsState.allPermissionsGranted) {
                        orientationEventListener?.enable()
                        startLocationUpdates()
                    }

                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        orientationEventListener?.disable()
                        stopLocationUpdates()
                    }
                }

                // NEU: LaunchedEffect zum Zurücksetzen des Warn-Flashs
                LaunchedEffect(warningLevelState.value) {
                    if (warningLevelState.value != WarningLevel.None) {
                        // Dauer des Aufleuchtens in Millisekunden
                        delay(350L) // <-- Anpassen nach Bedarf (z.B. 300ms - 500ms)
                        warningLevelState.value = WarningLevel.None // Zurücksetzen
                    }
                }
            }
        }
    }

    private fun initializeDetector() {
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
                // Optional: Fehler im UI anzeigen
            }
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    if (location.hasSpeed()) {
                        speedState.value = location.speed // Geschwindigkeit in m/s
                    }
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    Log.w("Location", "Location not available.")
                    speedState.value = 0.0f // Geschwindigkeit zurücksetzen
                }
            }
        }
    }

    private fun createOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val newOrientation = when (orientation) {
                    in 45..134 -> Configuration.ORIENTATION_LANDSCAPE // Landscape Right (Reverse)
                    in 135..224 -> Configuration.ORIENTATION_PORTRAIT // Upside down Portrait (Ignored for alignment usually)
                    in 225..314 -> Configuration.ORIENTATION_LANDSCAPE // Landscape Left
                    else -> Configuration.ORIENTATION_PORTRAIT // Normal Portrait
                }

                // Aktualisiere nur, wenn sich die Hauptausrichtung (Hoch/Quer) ändert
                if (newOrientation != physicalOrientationState.value) {
                    physicalOrientationState.value = newOrientation
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Location", "Permission check failed in startLocationUpdates")
            return // Sollte nicht passieren wegen Check im Effect, aber sicher ist sicher
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d("Location", "Location updates requested.")
        } catch (e: SecurityException) {
            Log.e("Location", "Failed to start location updates due to security exception.", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d("Location", "Location updates stopped.")
        } catch (e: Exception) {
            Log.e("Location", "Error stopping location updates", e)
        }
    }

    private fun toggleGpu(enabled: Boolean) {
        isGpuEnabledState.value = enabled
        cameraExecutor.submit {
            if (::detector.isInitialized) {
                try {
                    detector.restart(enabled)
                    Log.d("MainActivity", "Detector restarted with GPU=${enabled}")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error restarting detector", e)
                }
            }
        }
        boundingBoxesState.value = emptyList()
        inferenceTimeState.value = 0L
    }

    private fun toggleLaneDetection(enabled: Boolean){
        isLaneDetectionEnabledState.value = enabled
    }


    private fun analyzeBitmap(bitmap: Bitmap) {
        if (::detector.isInitialized) {
            detector.detect(bitmap)
            if(isLaneDetectionEnabledState.value) {
                val laneDetectionResult: Pair<Bitmap?, Double?>? = try {
                    LaneDetector.detectLanes(bitmap)

                } catch (e: Exception) {
                    Log.e("MainActivity", "Error calling LaneDetector.detectLanes", e)
                    null
                } finally {
                    // bitmapCopyForLanes.recycle() // Normalerweise nicht nötig/sicher
                }


                laneBitmapState.value = laneDetectionResult?.first  // Das Bitmap geht in den State
                laneDeviationState.value =
                    laneDetectionResult?.second // Die Abweichung geht in den State


                // Logik zum Setzen des WarningLevels und Abspielen der Sounds
                try {
                    val deviation = laneDeviationState.value
                    if (deviation != null) {
                        val absDeviation = kotlin.math.abs(deviation)
                        if (absDeviation > 0.14) {
                            Log.d("MainActivity", "Lane deviation: $deviation critical!")
                            if (warningLevelState.value != WarningLevel.Hard) { // Nur setzen, wenn nicht schon Hard
                                warningLevelState.value = WarningLevel.Hard
                            }
                            // Sound kann weiterhin hier oder im LaunchedEffect ausgelöst werden
                            if (!laneHardPlayer.isPlaying) laneHardPlayer.start()
                        } else if (absDeviation > 0.07) {
                            Log.d("MainActivity", "Lane deviation: $deviation moderate.")
                            // Nur setzen, wenn nicht schon Hard (Soft überschreibt Hard nicht)
                            if (warningLevelState.value == WarningLevel.None) {
                                warningLevelState.value = WarningLevel.Soft
                            }
                            if (!laneSoftPlayer.isPlaying && !laneHardPlayer.isPlaying) laneSoftPlayer.start() // Soft nur wenn nicht schon Hard spielt
                        } else {
                            // Kein kritisches Level, kein Flash nötig (wird durch LaunchedEffect zurückgesetzt)
                            // warningLevelState.value = WarningLevel.None // --> Nicht hier setzen, das macht der LaunchedEffect
                        }
                    } else {
                        // warningLevelState.value = WarningLevel.None // --> Nicht hier setzen
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Error checking lane deviation or playing sound.", e)
                    // warningLevelState.value = WarningLevel.None // --> Nicht hier setzen
                }
            }else{
                warningLevelState.value = WarningLevel.None
                laneBitmapState.value = null
                laneDeviationState.value = null

            }



        }
    }

    // --- Detector.DetectorListener Implementierung ---
    override fun onEmptyDetect() {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                boundingBoxesState.value = emptyList()
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                boundingBoxesState.value = boundingBoxes
                inferenceTimeState.value = inferenceTime
            }
        }
    }

    // --- Activity Lifecycle ---
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        orientationEventListener?.disable()
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
            Log.d("MainActivity", "CameraExecutor shutdown.")
        }
        if (::detector.isInitialized) {
            try {
                detector.close()
                Log.d("MainActivity", "Detector closed.")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error closing detector", e)
            }
        }
    }
}

// -------------------- Composables --------------------

@Composable
fun LoadingIndicator(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp)) // Ladekreis hinzugefügt
        Text(text, modifier = Modifier.padding(top = 60.dp)) // Text unter dem Kreis
    }
}

@Composable
fun CameraDetectionScreen(
    boundingBoxes: List<BoundingBox>,
    inferenceTime: Long,
    laneBitmap: Bitmap?,
    laneDeviation: Double?, // <-- NEU: Parameter für Abweichung
    isGpuEnabled: Boolean,
    isLaneDetectionEnabled: Boolean,
    speed: Float,
    physicalOrientation: Int,
    warningLevel: WarningLevel,
    onGpuToggle: (Boolean) -> Unit,
    onLaneDetectionToggle: (Boolean) -> Unit,
    onBitmapAnalyzed: (Bitmap) -> Unit,
    analysisExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val overlayView = remember { OverlayView(context, null) }

    LaunchedEffect(cameraProviderFuture, previewView) {
        if (previewView != null) {
            val cameraProvider = try { cameraProviderFuture.get() } catch (e: Exception) { null }
            if (cameraProvider != null) {
                bindCameraUseCases(
                    context = context,
                    cameraProvider = cameraProvider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView!!,
                    onBitmapAnalyzed = onBitmapAnalyzed,
                    executor = analysisExecutor
                )
            }
        }
    }

    // --- Animation Setup ---
    val infiniteTransition = rememberInfiniteTransition(label = "WarningPulse")

    // Animate alpha based on the warning level.
    // We only run the animation when warningLevel is not None.
    // When it's None, alpha is effectively 0 because the Box won't be composed.
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f, // Start slightly visible
        targetValue = when (warningLevel) {
            // Determine max brightness of pulse
            WarningLevel.Soft -> 0.4f // Yellow pulses less intensely
            WarningLevel.Hard -> 0.8f  // Red pulses more intensely
            WarningLevel.None -> 0.05f // Target doesn't matter when not pulsing, but keep it low
        },
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 200, easing = LinearEasing), // Duration of one fade in/out cycle
            repeatMode = RepeatMode.Reverse // Fade in, then fade out
        ),
        label = "WarningAlphaPulse"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
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
                view.setResults(boundingBoxes)
                view.setLaneBitmap(laneBitmap)
            }
        )

        // --- Pulsating Visual Warning Overlay ---
        if (warningLevel != WarningLevel.None) {
            val baseColor = when (warningLevel) {
                WarningLevel.Soft -> Color.Yellow
                WarningLevel.Hard -> Color.Red
                WarningLevel.None -> Color.Transparent // Should not happen due to if
            }

            // Use the animatedAlpha for the gradient colors
            val gradient = Brush.horizontalGradient(
                colors = listOf(
                    baseColor.copy(alpha = animatedAlpha), // Use animated alpha
                    Color.Transparent,
                    baseColor.copy(alpha = animatedAlpha)  // Use animated alpha
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradient)
            )
        }
        // --- End Pulsating Overlay ---

        // Inferenzzeit
        if (inferenceTime > 0) {
            Text(
                text = "$inferenceTime ms",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = 12.sp
            )
        }

        // Geschwindigkeit
        val speedAlignment = if (physicalOrientation == Configuration.ORIENTATION_LANDSCAPE)
            Alignment.TopStart else Alignment.TopCenter

        Card(
            modifier = Modifier
                .align(speedAlignment)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0x99000000))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val speedKmh = (speed * 3.6f).roundToInt()
                Text(
                    text = "$speedKmh km/h",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Limit: --",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp
                )
            }
        }

        // GPU Toggle
        val gpuAlignment = if (physicalOrientation == Configuration.ORIENTATION_LANDSCAPE)
            Alignment.BottomStart else Alignment.BottomCenter

        laneDeviation?.let { deviation ->
            LaneCenterIndicator(
                deviation = deviation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp) // Position anpassen
                    .fillMaxWidth(0.6f) // Breite anpassen
                    .height(20.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            )
        }

        Card(
            modifier = Modifier
                .align(gpuAlignment)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0x99000000))
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GPU", color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isGpuEnabled,
                        onCheckedChange = onGpuToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Green,
                            uncheckedThumbColor = Color.LightGray
                        )
                    )
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically){
                    Text("Lane Detection", color= Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isLaneDetectionEnabled,
                        onCheckedChange = onLaneDetectionToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Green,
                        )
                    )
                }
            }

        }
    }
}

@Composable
fun LaneCenterIndicator(
    deviation: Double, // Erwartet Wert ca. -1.0 (ganz links) bis 1.0 (ganz rechts)
    modifier: Modifier = Modifier // Dieser Modifier bekommt die Größenbeschränkungen (z.B. .fillMaxWidth(0.6f))
) {
    // Der äußere Box definiert den Bereich, in dem der Indikator platziert wird.
    // Der übergebene 'modifier' steuert die Größe dieses Bereichs.
    Box(modifier = modifier) {

        // Mittellinie (Ziel) - Wird im Zentrum des äußeren Box platziert
        Divider(
            color = Color.White.copy(alpha = 0.5f),
            thickness = 2.dp,
            modifier = Modifier
                .fillMaxHeight() // Nimmt die volle Höhe des äußeren Box ein
                .width(2.dp)
                .align(Alignment.Center) // Zentriert die Divider-Linie selbst
        )

        // Aktuelle Position (Indikator)
        // Wir verwenden BiasAlignment, um diesen inneren Box relativ zum äußeren Box zu positionieren.
        // deviation (-1 bis 1) passt direkt zum horizontalBias.
        val horizontalBias = deviation.coerceIn(-1.0, 1.0).toFloat() // Sicherstellen, dass der Wert im Bereich ist

        Box(
            modifier = Modifier
                // Richtet den *Mittelpunkt* dieses Indikator-Boxes horizontal
                // basierend auf dem Bias aus (-1 = links, 0 = mitte, 1 = rechts).
                // Vertikal wird er zentriert (verticalBias = 0f).
                .align(BiasAlignment(horizontalBias = horizontalBias, verticalBias = 0f))
                // Gibt dem Indikator selbst eine Größe.
                .width(8.dp) // Breite des gelben Punktes/Strichs
                .fillMaxHeight(0.8f) // Höhe des Punktes (etwas kleiner als der Hintergrund)
                .background(Color.Yellow, CircleShape) // Gelber Kreis als Indikator
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
        // Wähle eine moderate Auflösung für die Analyse, falls nötig, um Performance zu verbessern
        // .setTargetResolution(android.util.Size(1280, 720))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        // Kein Output Format setzen, damit YUV für imageProxyToBitmap kommt
        .build()

    imageAnalysis.setAnalyzer(executor) { imageProxy ->
        val bitmap = imageProxyToBitmap(imageProxy) // Diese Funktion muss existieren!
        // imageProxy wird innerhalb von imageProxyToBitmap geschlossen oder direkt danach
        // imageProxy.close() // --> Schließen erfolgt jetzt im finally von imageProxyToBitmap

        if (bitmap != null) {
            onBitmapAnalyzed(bitmap)
        } else {
            Log.w("bindCameraUseCases", "Bitmap conversion failed.")
            // Wichtig: imageProxy trotzdem schließen, falls Konvertierung fehlschlägt
            // Dies sollte idealerweise in einem finally-Block in imageProxyToBitmap geschehen.
            // Wenn nicht, hier schließen:
            // imageProxy.close()
        }
    }

    try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageAnalysis
        )
        Log.d("bindCameraUseCases", "Camera use cases bound successfully.")
    } catch (exc: Exception) {
        Log.e("bindCameraUseCases", "Use case binding failed", exc)
    }
}


