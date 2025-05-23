package com.turksat46.carlydashboard.other

import android.graphics.Bitmap
import android.media.session.MediaSession
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

// Singleton oder ViewModel, um Daten an die UI zu übergeben
object MediaInfoHolder {
    private val _currentTrack = MutableLiveData<TrackInfo?>()
    val currentTrack: LiveData<TrackInfo?> = _currentTrack

    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentSourceApp = MutableLiveData<String?>() // Optional: Name der Quell-App
    val currentSourceApp: LiveData<String?> = _currentSourceApp


    fun updateTrack(trackInfo: TrackInfo?) {
        _currentTrack.postValue(trackInfo)
        if (trackInfo != null) {
            _currentSourceApp.postValue(getAppNameFromPackage(trackInfo.packageName))
        } else {
            _currentSourceApp.postValue(null)
        }
    }

    fun updatePlaybackState(playing: Boolean) {
        _isPlaying.postValue(playing)
    }

    fun clearMediaInfo() {
        _currentTrack.postValue(null)
        _isPlaying.postValue(false)
        _currentSourceApp.postValue(null)
    }

    // Hilfsfunktion, um einen lesbaren App-Namen zu bekommen (optional)
    private fun getAppNameFromPackage(packageName: String): String {
        return when (packageName) {
            "com.spotify.music" -> "Spotify"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "com.amazon.mp3" -> "Amazon Music"
            // Füge hier weitere bekannte Player hinzu
            else -> packageName.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } // Fallback
        }
    }
}

data class TrackInfo(
    val title: String?,
    val artist: String?,
    val album: String?, // Kann nützlich sein, auch wenn nicht direkt angezeigt
    val albumArt: Bitmap?,
    val packageName: String, // Um die Quelle zu identifizieren
    val sessionToken: MediaSession.Token? = null // Added session token

)