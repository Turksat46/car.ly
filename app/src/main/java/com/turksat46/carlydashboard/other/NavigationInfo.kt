package com.turksat46.carlydashboard.other

data class NavigationInfo(
    val nextInstruction: String? = null, // z.B. "In 500 m links abbiegen auf Hauptstraße" oder "0 m"
    val currentRoad: String? = null,     // z.B. "Oberbettringer Str. Richtung Galgenschlößle"
    val etaDetails: String? = null,      // z.B. "21 min · 25 km · Ankunft ca. 02:05"
    val isActive: Boolean = false
)