package com.turksat46.carlydashboard

import android.app.Notification // Wichtig für Notification.EXTRA_...
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle // Für Extras
import android.util.Log
import android.widget.RemoteViews // Für den Fall, dass wir tiefer graben müssten (aber vermeiden wir es erstmal)
import com.turksat46.carlydashboard.other.MediaInfoHolder
import com.turksat46.carlydashboard.other.NavigationInfo
import com.turksat46.carlydashboard.other.NavigationInfoHolder
import com.turksat46.carlydashboard.other.TrackInfo

class MyMediaSessionListenerService : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private val activeControllers = mutableMapOf<String, MediaController>()
    private val controllerCallbacks = mutableMapOf<String, MediaController.Callback>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active sessions changed. New count: ${controllers?.size ?: 0}")
        updateActiveMediaSessions()
    }

    companion object {
        private const val TAG = "MediaSessionListener"
        private const val NAV_TAG = "NavigationListener" // Eigener Tag für Navigations-Logs
        private const val GOOGLE_MAPS_PACKAGE_NAME = "com.google.android.apps.maps"
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        Log.d(TAG, "Service created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
        updateActiveMediaSessions()

        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionListener,
                ComponentName(this, MyMediaSessionListenerService::class.java)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException adding session listener. Is permission granted?", e)
        }
    }

    // ... (updateActiveMediaSessions, isPotentiallyNewPrimary, isControllerRelevant, handleMediaUpdate bleiben unverändert) ...
    private fun updateActiveMediaSessions() {
        val currentActiveControllers = try {
            mediaSessionManager.getActiveSessions(ComponentName(this, MyMediaSessionListenerService::class.java))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting active sessions. Is permission granted?", e)
            return
        }

        //Log.d(TAG, "Found ${currentActiveControllers?.size ?: 0} active media sessions.")

        val currentControllerPackages = currentActiveControllers?.map { it.packageName }?.toSet() ?: emptySet()
        activeControllers.keys.filterNot { it in currentControllerPackages }.forEach { packageName ->
            activeControllers[packageName]?.unregisterCallback(controllerCallbacks[packageName]!!)
            activeControllers.remove(packageName)
            controllerCallbacks.remove(packageName)
            // Log.d(TAG, "Unregistered callback for $packageName")
        }

        var primaryController: MediaController? = null

        currentActiveControllers?.forEach { controller ->
            val packageName = controller.packageName
            if (!activeControllers.containsKey(packageName)) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        super.onMetadataChanged(metadata)
                        //Log.d(TAG, "Metadata changed for $packageName: ${metadata?.description?.title}")
                        if (isControllerRelevant(controller)) {
                            val currentTrackPackage = MediaInfoHolder.currentTrack.value?.packageName
                            if (packageName == currentTrackPackage || isPotentiallyNewPrimary(controller)) {
                                handleMediaUpdate(controller, metadata, controller.playbackState)
                            }
                        } else if (MediaInfoHolder.currentTrack.value?.packageName == packageName) {
                            handleMediaUpdate(controller, metadata, controller.playbackState)
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        super.onPlaybackStateChanged(state)
                        //Log.d(TAG, "Playback state changed for $packageName: ${state?.state}")
                        if (isControllerRelevant(controller)) {
                            val currentTrackPackage = MediaInfoHolder.currentTrack.value?.packageName
                            if (packageName == currentTrackPackage || isPotentiallyNewPrimary(controller)) {
                                handleMediaUpdate(controller, controller.metadata, state)
                            }
                        } else if (MediaInfoHolder.currentTrack.value?.packageName == packageName) {
                            handleMediaUpdate(controller, controller.metadata, state)
                        }
                    }
                }
                try {
                    controller.registerCallback(callback)
                    activeControllers[packageName] = controller
                    controllerCallbacks[packageName] = callback
                    //Log.d(TAG, "Registered callback for $packageName. Initial state: ${controller.playbackState?.state}, Title: ${controller.metadata?.description?.title}")
                    if (isControllerRelevant(controller)) {
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING || primaryController == null) {
                            handleMediaUpdate(controller, controller.metadata, controller.playbackState)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering callback for $packageName", e)
                }
            }
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                primaryController = controller
            } else if (primaryController == null && controller.metadata != null && isControllerRelevant(controller)) {
                primaryController = controller
            }
        }

        if (primaryController != null) {
            handleMediaUpdate(primaryController!!, primaryController!!.metadata, primaryController!!.playbackState)
        } else if (currentActiveControllers?.isEmpty() == true || activeControllers.isEmpty()) {
            if (MediaInfoHolder.currentTrack.value != null) {
                MediaInfoHolder.clearMediaInfo()
                Log.d(TAG, "No active controllers, clearing media info from holder.")
            }
        } else {
            val currentTrackInfo = MediaInfoHolder.currentTrack.value
            if (currentTrackInfo != null) {
                val stillActiveControllerForCurrentTrack = activeControllers[currentTrackInfo.packageName]
                if (stillActiveControllerForCurrentTrack == null || !isControllerRelevant(stillActiveControllerForCurrentTrack)) {
                    val firstRelevantFallback = currentActiveControllers?.firstOrNull { isControllerRelevant(it) }
                    if (firstRelevantFallback != null) {
                        handleMediaUpdate(firstRelevantFallback, firstRelevantFallback.metadata, firstRelevantFallback.playbackState)
                    } else {
                        MediaInfoHolder.clearMediaInfo()
                        //Log.d(TAG, "Current track's controller no longer relevant, no fallback, clearing UI.")
                    }
                }
            }
        }
    }

    private fun isPotentiallyNewPrimary(controller: MediaController): Boolean {
        if (MediaInfoHolder.currentTrack.value == null && isControllerRelevant(controller)) return true
        val currentController = MediaInfoHolder.currentTrack.value?.packageName?.let { activeControllers[it] }
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
            currentController?.playbackState?.state != PlaybackState.STATE_PLAYING) {
            return true
        }
        return false
    }


    private fun isControllerRelevant(controller: MediaController): Boolean {
        val state = controller.playbackState?.state
        val hasMetadata = controller.metadata?.description?.title != null ||
                controller.metadata?.description?.description != null ||
                controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null

        return state == PlaybackState.STATE_PLAYING ||
                (hasMetadata && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR)
    }


    private fun handleMediaUpdate(controller: MediaController, metadata: MediaMetadata?, playbackState: PlaybackState?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val albumArtBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val isPlayingState = playbackState?.state == PlaybackState.STATE_PLAYING
        val packageName = controller.packageName

        //Log.i(TAG, "Handling update for $packageName: Title='$title', Artist='$artist', IsPlaying=$isPlayingState, HasArt=${albumArtBitmap != null}, HasToken=${controller.sessionToken != null}")

        if (isControllerRelevant(controller) || MediaInfoHolder.currentTrack.value?.packageName == packageName) {
            if (title != null || artist != null || albumArtBitmap != null) {
                val trackInfo = TrackInfo(title, artist, album, albumArtBitmap, packageName, controller.sessionToken)
                MediaInfoHolder.updateTrack(trackInfo)
                MediaInfoHolder.updatePlaybackState(isPlayingState)
            } else if (MediaInfoHolder.currentTrack.value?.packageName == packageName) {
                if (!isPlayingState) {
                    //Log.d(TAG, "Metadata for $packageName (current track) is null, playback state: $isPlayingState. Clearing track.")
                    MediaInfoHolder.updateTrack(null)
                    MediaInfoHolder.updatePlaybackState(false)
                } else {
                    MediaInfoHolder.updatePlaybackState(true)
                    val existingTrack = MediaInfoHolder.currentTrack.value
                    if (existingTrack != null && existingTrack.packageName == packageName) {
                        MediaInfoHolder.updateTrack(
                            TrackInfo(null, null, null, null, packageName, controller.sessionToken)
                        )
                    }
                }
            }
        } else if (activeControllers.isEmpty() && MediaInfoHolder.currentTrack.value != null) {
            MediaInfoHolder.clearMediaInfo()
            //Log.d(TAG,"No active controllers (from handleMediaUpdate fallback), clearing media info from holder.")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Log.v(TAG, "Notification posted: ${sbn.packageName}, ID: ${sbn.id}, Tag: ${sbn.tag}")

        // Speziell für Google Maps Navigations-Benachrichtigungen
        if (sbn.packageName == GOOGLE_MAPS_PACKAGE_NAME) {
            handleGoogleMapsNotification(sbn)
        }
        // Hier könnte man auch sbn?.notification?.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) prüfen
        // und ggf. updateActiveMediaSessions() auslösen, falls sich die MediaSession geändert hat,
        // ohne dass der addOnActiveSessionsChangedListener getriggert wurde. Ist aber meist redundant.
    }

    private fun handleGoogleMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        Log.i(NAV_TAG, "--- Google Maps Notification Update ---")
        Log.i(NAV_TAG, "Notification ID: ${sbn.id}, Tag: ${sbn.tag}")

        // Standardfelder auslesen
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() // Kann CharSequence sein
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT) // Manchmal verwendet
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT) // Für gruppierte Notifications

        if (title != null) Log.d(NAV_TAG, "Title: $title")
        if (text != null) Log.d(NAV_TAG, "Text: $text")
        if (bigText != null) Log.d(NAV_TAG, "BigText: $bigText")
        if (subText != null) Log.d(NAV_TAG, "SubText: $subText")
        if (infoText != null) Log.d(NAV_TAG, "InfoText: $infoText")
        if (summaryText != null) Log.d(NAV_TAG, "SummaryText: $summaryText")

        // Manchmal sind Navigationsanweisungen in "title" oder "text" oder "bigText".
        // ETA und Distanz sind oft in "text" oder "subText".

        // Fortschrittsanzeige (z.B. für verbleibende Strecke/Zeit in manchen Navis)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1)
        val progressIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        if (progressMax > 0) {
            Log.d(NAV_TAG, "Progress: $progress / $progressMax (Indeterminate: $progressIndeterminate)")
        }

        // Actions (Buttons in der Benachrichtigung)
        notification.actions?.forEachIndexed { index, action ->
            Log.d(NAV_TAG, "Action $index: ${action.title}")
        }

        // Für sehr experimentierfreudige: Alle Extras loggen, um zu sehen, was Maps so mitschickt.
        // Vorsicht: Kann sehr verbose sein und sensible Daten enthalten.

        Log.d(NAV_TAG, "All Extras:")
        for (key in extras.keySet()) {
            val value = extras.get(key)
            Log.d(NAV_TAG, "  $key: $value (${value?.javaClass?.name})")
        }


        // Versuch, spezifische Felder zu finden, die Google Maps verwenden *könnte*.
        // Diese Schlüssel sind NICHT dokumentiert und können sich jederzeit ändern!
        // Beispiele, die in der Vergangenheit mal gesehen wurden (ohne Gewähr):
        // "android.textLines" (ein CharSequence Array)
        // "android.template" (z.B. "android.app.Notification$BigTextStyle")

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            Log.d(NAV_TAG, "TextLines:")
            textLines.forEachIndexed { i, line -> Log.d(NAV_TAG, "  Line $i: $line") }
        }

        val nextInstruction = title // "0 m"
        val currentRoad = text    // "Oberbettringer Str. Richtung Galgenschlößle"
        val etaDetails = subText  // "21 min · 25 km · Ankunft ca. 02:05"

        if (nextInstruction != null || currentRoad != null || etaDetails != null) {
            val navInfo = NavigationInfo(
                nextInstruction = nextInstruction.toString(),
                currentRoad = currentRoad.toString(),
                etaDetails = etaDetails.toString(),
                isActive = true
            )
            NavigationInfoHolder.updateNavigationInfo(navInfo)
        } else {
            // Wenn keine relevanten Infos da sind, aber die Notification noch da ist,
            // könnte man isActive = true setzen, aber ohne Details.
            // Oder, wenn es eine "leere" Nav-Notification ist, isActive = false.
            // Fürs Erste: Nur updaten, wenn Infos da sind.
        }

        // Wenn du tiefer in RemoteViews einsteigen wolltest (SEHR FRAGIL):
        // Man müsste die contentView, bigContentView oder headsUpContentView nehmen,
        // dann per Reflection oder aufwändiges Parsen der RemoteViews-Actions
        // versuchen, an die IDs der TextViews zu kommen und deren Text auszulesen.
        // Das ist extrem unzuverlässig und wird hier nicht implementiert.
        // Example of what NOT to do for production:
        // val remoteViews: RemoteViews? = notification.bigContentView ?: notification.contentView
        // if (remoteViews != null) {
        //     Log.d(NAV_TAG, "RemoteViews package: ${remoteViews.packageName}, layoutId: ${remoteViews.layoutId}")
        //     // Further parsing would be needed here
        // }

        Log.i(NAV_TAG, "--- End Google Maps Notification ---")
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Log.v(TAG, "Notification removed: ${sbn?.packageName}")
        if (sbn?.packageName == GOOGLE_MAPS_PACKAGE_NAME) {
            Log.i(NAV_TAG, "Google Maps notification removed. ID: ${sbn.id}, Tag: ${sbn.tag}")
            // Hier könntest du signalisieren, dass die Navigation beendet wurde,
            // falls die Entfernung der Benachrichtigung das zuverlässig anzeigt.
        }
    }


    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Listener disconnected")
        activeControllers.forEach { (_, controller) ->
            controllerCallbacks[controller.packageName]?.let { controller.unregisterCallback(it) }
        }
        activeControllers.clear()
        controllerCallbacks.clear()
        MediaInfoHolder.clearMediaInfo()
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing session listener", e)
        }
    }
}