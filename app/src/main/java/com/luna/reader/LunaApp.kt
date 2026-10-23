package com.luna.reader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class LunaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializa o PDFBox para processamento de PDFs no Android
        try {
            PDFBoxResourceLoader.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
