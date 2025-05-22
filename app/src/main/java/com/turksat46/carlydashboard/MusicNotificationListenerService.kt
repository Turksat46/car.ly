package com.turksat46.carlydashboard

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.turksat46.carlydashboard.other.MediaInfoHolder
import com.turksat46.carlydashboard.other.TrackInfo

class MyMediaSessionListenerService : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private val activeControllers = mutableMapOf<String, MediaController>() // packageName to Controller
    private val controllerCallbacks = mutableMapOf<String, MediaController.Callback>()

    companion object {
        private const val TAG = "MediaSessionListener"
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        Log.d(TAG, "Service created")
        // Initial scan on service start (might be better to do on onListenerConnected)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
        updateActiveMediaSessions() // Scan for active sessions when listener is ready

        // Listener für Änderungen in den aktiven Sessions
        mediaSessionManager.addOnActiveSessionsChangedListener(
            { controllers ->
                Log.d(TAG, "Active sessions changed. New count: ${controllers?.size ?: 0}")
                updateActiveMediaSessions()
            },
            ComponentName(this, MyMediaSessionListenerService::class.java)
        )
    }

    private fun updateActiveMediaSessions() {
        val currentActiveControllers = try {
            mediaSessionManager.getActiveSessions(ComponentName(this, MyMediaSessionListenerService::class.java))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting active sessions. Is permission granted?", e)
            // Hier könntest du den Nutzer informieren, dass die Berechtigung fehlt.
            return
        }

        Log.d(TAG, "Found ${currentActiveControllers?.size ?: 0} active media sessions.")

        // Entferne Callbacks von nicht mehr aktiven Controllern
        val currentControllerPackages = currentActiveControllers?.map { it.packageName }?.toSet() ?: emptySet()
        activeControllers.keys.filterNot { it in currentControllerPackages }.forEach { packageName ->
            activeControllers[packageName]?.unregisterCallback(controllerCallbacks[packageName]!!)
            activeControllers.remove(packageName)
            controllerCallbacks.remove(packageName)
            Log.d(TAG, "Unregistered callback for $packageName")
        }

        var primaryController: MediaController? = null

        currentActiveControllers?.forEach { controller ->
            val packageName = controller.packageName
            if (!activeControllers.containsKey(packageName)) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        super.onMetadataChanged(metadata)
                        Log.d(TAG, "Metadata changed for $packageName: ${metadata?.description?.title}")
                        // Entscheide, ob dies der primäre Controller ist
                        // Oft ist es der zuletzt aktive oder der, der gerade spielt
                        if (isControllerRelevant(controller)) {
                            handleMediaUpdate(controller, metadata, controller.playbackState)
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        super.onPlaybackStateChanged(state)
                        Log.d(TAG, "Playback state changed for $packageName: ${state?.state}")
                        if (isControllerRelevant(controller)) {
                            handleMediaUpdate(controller, controller.metadata, state)
                        }
                    }
                }
                try {
                    controller.registerCallback(callback)
                    activeControllers[packageName] = controller
                    controllerCallbacks[packageName] = callback
                    Log.d(TAG, "Registered callback for $packageName. Initial state: ${controller.playbackState?.state}, Title: ${controller.metadata?.description?.title}")
                    // Initial update if relevant
                    if (isControllerRelevant(controller)) {
                        handleMediaUpdate(controller, controller.metadata, controller.playbackState)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering callback for $packageName", e)
                }
            }
            // Logik um den "wichtigsten" Controller zu finden (z.B. der, der gerade spielt)
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                primaryController = controller
            } else if (primaryController == null && controller.metadata != null) {
                // Fallback: nimm den ersten mit Metadaten, wenn keiner spielt
                primaryController = controller
            }
        }

        if (primaryController != null) {
            handleMediaUpdate(primaryController!!, primaryController!!.metadata, primaryController!!.playbackState)
        } else if (currentActiveControllers?.isEmpty() == true || activeControllers.isEmpty()) {
            // Wenn keine Controller mehr aktiv sind, UI leeren
            MediaInfoHolder.updateTrack(null)
            MediaInfoHolder.updatePlaybackState(false)
            Log.d(TAG, "No active or relevant controllers, clearing UI.")
        }
    }

    // Hilfsfunktion, um zu entscheiden, ob ein Controller für die UI relevant ist
    private fun isControllerRelevant(controller: MediaController): Boolean {
        // Beispiel: Nur Controller nehmen, die gerade spielen oder Metadaten haben.
        // Du könntest auch eine Prioritätenliste von Apps haben (z.B. Spotify > YouTube Music)
        val state = controller.playbackState?.state
        return state == PlaybackState.STATE_PLAYING || (controller.metadata != null && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED)
    }


    private fun handleMediaUpdate(controller: MediaController, metadata: MediaMetadata?, playbackState: PlaybackState?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val albumArtBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) // Fallback

        val isPlayingState = playbackState?.state == PlaybackState.STATE_PLAYING
        val packageName = controller.packageName

        Log.i(TAG, "Handling update for $packageName: Title='$title', Artist='$artist', IsPlaying=$isPlayingState, HasArt=${albumArtBitmap != null}")

        if (title != null || artist != null || albumArtBitmap != null) {
            val trackInfo = TrackInfo(title, artist, album, albumArtBitmap, packageName)
            MediaInfoHolder.updateTrack(trackInfo) // Aktualisiert auch die Quell-App
            MediaInfoHolder.updatePlaybackState(isPlayingState)
        } else if (!isPlayingState && MediaInfoHolder.currentTrack.value?.packageName == packageName) {
            // Wenn der aktuelle Track von dieser App war und nun stoppt (ohne neue Metadaten)
            MediaInfoHolder.updatePlaybackState(false)
            // Optional: TrackInfo behalten, aber als pausiert markieren, oder löschen, wenn keine Infos mehr relevant sind
            // MediaInfoHolder.updateTrack(null) // Wenn gestoppt = keine Info mehr
        } else if (activeControllers.isEmpty() && MediaInfoHolder.currentTrack.value != null) {
            // Keine aktiven Controller mehr, aber noch alte Info da -> löschen
            MediaInfoHolder.clearMediaInfo()
            Log.d(TAG,"No active controllers, clearing media info from holder.")
        }
    }


    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Nicht unbedingt nötig für Media Sessions, aber der Service braucht es.
        // Könnte genutzt werden, um auf Notifications zu reagieren, die MediaSession-Tokens enthalten,
        // aber `MediaSessionManager.addOnActiveSessionsChangedListener` ist meist besser.
        super.onNotificationPosted(sbn)
        Log.v(TAG, "Notification posted: ${sbn?.packageName}")
        // Manchmal ändern sich Media Sessions nicht, aber die Notification wird aktualisiert.
        // Hier könnte man prüfen, ob die Notification von einer bekannten Medien-App ist und ggf. updateActiveMediaSessions() triggern.
        // Dies ist aber fehleranfälliger.
        // sbn?.notification?.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)?.let { token ->
        //    Log.d(TAG, "Notification contains media session token for ${sbn.packageName}")
        //    updateActiveMediaSessions() // Könnte zu häufigem Scannen führen
        // }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        Log.v(TAG, "Notification removed: ${sbn?.packageName}")
        // updateActiveMediaSessions() // Könnte auch hier nötig sein, wenn Sessions ohne API-Update enden
    }


    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Listener disconnected")
        // Callbacks entfernen
        activeControllers.forEach { (_, controller) ->
            controllerCallbacks[controller.packageName]?.let { controller.unregisterCallback(it) }
        }
        activeControllers.clear()
        controllerCallbacks.clear()
        MediaInfoHolder.clearMediaInfo() // Clear MediaInfoHolder
        mediaSessionManager.removeOnActiveSessionsChangedListener { /* Listener-Objekt hier übergeben */ }
    }
}