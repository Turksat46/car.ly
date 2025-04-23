package com.turksat46.carlydashboard

import androidx.compose.runtime.mutableStateOf

class ProcessDetectorData {
    private val boundingBoxesState = mutableStateOf<List<BoundingBox>>(emptyList())

    fun getBoundingBoxes(): List<BoundingBox> {
        return boundingBoxesState.value
    }

    fun setBoundingBoxes(boundingBoxes: List<BoundingBox>) {
        boundingBoxesState.value = boundingBoxes
    }

    fun getClassNumbers(): List<Int> {
        return boundingBoxesState.value.map { it.cls }
    }

}