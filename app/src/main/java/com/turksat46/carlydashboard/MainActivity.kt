package com.turksat46.carlydashboard // <-- Dein Paketname hier

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import android.view.WindowManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.*
import com.turksat46.carlydashboard.ui.theme.CarlyDashboardTheme // Dein Theme
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), Detector.DetectorListener {

    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var orientationEventListener: OrientationEventListener? = null

    // State für die UI-Updates
    private val boundingBoxesState = mutableStateOf<List<BoundingBox>>(emptyList())
    private val inferenceTimeState = mutableStateOf(0L)
    private val isGpuEnabledState = mutableStateOf(true)
    private val isDetectorInitialized = mutableStateOf(false)
    private val speedState = mutableStateOf(0.0f) // Geschwindigkeit in m/s
    private val physicalOrientationState = mutableStateOf(Configuration.ORIENTATION_PORTRAIT)

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
                                speed = speedState.value,
                                physicalOrientation = physicalOrientationState.value,
                                onGpuToggle = { enabled -> toggleGpu(enabled) },
                                onBitmapAnalyzed = { bitmap -> analyzeBitmap(bitmap) },
                                analysisExecutor = cameraExecutor
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
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                    orientationEventListener?.enable()
                                    startLocationUpdates()
                                }
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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

    private fun analyzeBitmap(bitmap: Bitmap) {
        if (::detector.isInitialized) {
            detector.detect(bitmap)
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
    isGpuEnabled: Boolean,
    speed: Float,
    physicalOrientation: Int,
    onGpuToggle: (Boolean) -> Unit,
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { previewView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Detection Overlay
        AndroidView(
            factory = { overlayView },
            modifier = Modifier.fillMaxSize(),
            update = { view -> view.setResults(boundingBoxes) }
        )

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

        Card(
            modifier = Modifier
                .align(gpuAlignment)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0x99000000))
        ) {
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
        }
    }
}
// -------------------- CameraX Binding Function --------------------

private fun bindCameraUseCases(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
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


