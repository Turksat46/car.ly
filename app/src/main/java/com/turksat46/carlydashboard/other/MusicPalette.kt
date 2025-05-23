package com.turksat46.carlydashboard.other

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

class MusicPalette(){
    private lateinit var palette: Palette
    private lateinit var bitmap: Bitmap

    public fun generatePalette(bitmap: Bitmap){
        this.bitmap = bitmap
        palette = Palette.from(bitmap).generate()
    }

    public fun setPalette(palette: Palette){
        this.palette = palette
    }

    public fun getPalette(): Palette{
        return palette
    }

    public fun getDominantColor(): Int{
        return palette.getDominantColor(0)
    }

}