package com.turksat46.carlydashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

class ProcessDetectorData {
    private val boundingBoxesState = mutableStateOf<List<BoundingBox>>(emptyList())
    private val imageVectorsState = mutableStateOf<List<ImageVector>>(emptyList())

    fun getBoundingBoxes(): List<BoundingBox> {
        return boundingBoxesState.value
    }

    fun getImageVectors(): List<ImageVector> {
        return imageVectorsState.value
    }

    fun setBoundingBoxes(boundingBoxes: List<BoundingBox>) {
        boundingBoxesState.value = boundingBoxes

    }

    companion object { // Make it static-like for easy access
        fun getDrawableResourceIdForClass(classId: Int): Int {
            return when (classId) {
                0 -> R.drawable.speed_20
                1 -> R.drawable.speed_30
                2 -> R.drawable.speed_50
                3 -> R.drawable.speed_60
                4 -> R.drawable.speed_70
                5 -> R.drawable.speed_80
                6 -> R.drawable.restriction_ends_80
                7 -> R.drawable.speed_100
                8 -> R.drawable.speed_120
                9 -> R.drawable.no_overtaking
                10 -> R.drawable.no_overtaking_trucks
                11 -> R.drawable.priority_at_next_intersection
                12 -> R.drawable.priority_road
                13 -> R.drawable.give_way
                14 -> R.drawable.stop
                15 -> R.drawable.no_traffic_both_ways
                16 -> R.drawable.no_trucks
                17 -> R.drawable.no_entry
                18 -> R.drawable.danger
                19 -> R.drawable.bend_left
                20 -> R.drawable.bend_right
                21 -> R.drawable.bend
                22 -> R.drawable.uneven_road
                23 -> R.drawable.slippery_road
                24 -> R.drawable.road_narrows
                25 -> R.drawable.construction
                26 -> R.drawable.traffic_signals
                27 -> R.drawable.pedestrian_crossing
                28 -> R.drawable.school_crossing
                29 -> R.drawable.bicycle_crossing
                30 -> R.drawable.snow
                31 -> R.drawable.animal
                32 -> R.drawable.restriction_ends
                33 -> R.drawable.go_right
                34 -> R.drawable.go_left
                35 -> R.drawable.go_straight
                36 -> R.drawable.go_right_or_straight
                37 -> R.drawable.go_left_or_straight
                38 -> R.drawable.keep_right
                39 -> R.drawable.keep_left
                40 -> R.drawable.roundabout
                41 -> R.drawable.restriction_ends_overtaking
                42 -> R.drawable.restriction_ends_overtaking_trucks
                else -> R.drawable.baseline_disabled_visible_24 // Default/fallback icon
            }
        }
    }

}