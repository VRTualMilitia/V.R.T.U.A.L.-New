package com.vrtualmilitia.vrtual

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import rikka.shizuku.Shizuku

object ShellResetManager {
    const val PREFS = "vrtual_settings"
    const val KEY_MODE = "shell_reset_mode"
    const val MODE_OFF = 0
    const val MODE_MANUAL = 1
    const val MODE_AUTO = 2
    private val targets = listOf("com.oculus.vrshell", "com.oculus.shellenv", "com.oculus.systemux")

    fun saveMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
    }

    fun mode(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_OFF)

    fun shizukuReady(): Boolean = try { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    fun requestShizukuPermission(requestCode: Int) {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(requestCode)
    }

    /** Clears one Horizon shell package at a time. Clearing shell data may reset launcher state and require reconfiguration. */
    fun reset(context: Context, allowSettingsFallback: Boolean = true, callback: (String) -> Unit) {
        if (!shizukuReady()) {
            if (allowSettingsFallback) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.oculus.vrshell"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent); callback("Shizuku unavailable: opened Horizon Shell app settings. Clear storage manually if intended.") }
                catch (_: Exception) { callback("Shizuku is unavailable and app settings could not be opened.") }
            } else callback("Shizuku is not running or permission is not granted.")
            return
        }
        Thread {
            var output = "No Horizon shell package could be cleared."
            for (pkg in targets) {
                try {
                    val process = Shizuku.newProcess(arrayOf("sh", "-c", "pm clear $pkg"), null, null)
                    val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
                    val code = process.waitFor()
                    if (code == 0 && result.contains("Success", true)) { output = "Cleared $pkg: $result"; break }
                    output = "$pkg: ${result.ifBlank { error.ifBlank { "exit $code" } }}"
                } catch (e: Exception) { output = "Could not clear $pkg: ${e.message}" }
            }
            android.os.Handler(context.mainLooper).post { callback(output) }
        }.start()
    }
}
