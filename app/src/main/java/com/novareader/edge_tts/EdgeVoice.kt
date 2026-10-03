package com.novareader.edge_tts

data class EdgeVoice(val name: String, val locale: String, val gender: String, val friendlyName: String) {
    val language: String get() = locale.substringBefore('-')
}
