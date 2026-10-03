package com.novareader.edge_tts

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val TAG = "NovaTTS"
    private var logFile: File? = null
    
    fun init(context: Context) {
        logFile = File(context.filesDir, "nova_tts_log.txt")
        // Очищаем старый лог при инициализации
        logFile?.writeText("")
        log("=== Логирование запущено ===")
        log("Время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        log("Путь к файлу: ${logFile?.absolutePath}")
    }
    
    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        Log.d(TAG, message)
        try {
            logFile?.appendText(logEntry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
    
    fun error(message: String, throwable: Throwable? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] ERROR: $message\n"
        Log.e(TAG, message, throwable)
        try {
            logFile?.appendText(logEntry)
            if (throwable != null) {
                logFile?.appendText("${Log.getStackTraceString(throwable)}\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write error to log file", e)
        }
    }
    
    fun getLogContent(): String {
        return logFile?.readText() ?: "Лог файл не найден"
    }
    
    fun getLogFilePath(): String {
        return logFile?.absolutePath ?: "Неизвестно"
    }
    
    fun clear() {
        logFile?.writeText("")
        log("Лог очищен")
    }
}