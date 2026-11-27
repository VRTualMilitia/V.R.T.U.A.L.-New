
package com.vrtualmilitia.vrtual

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import rikka.shizuku.Shizuku

object ShellResetManager {

    const val PREFS = "vrtual_settings"
    const val KEY_MODE = "shell_reset_mode"

    const val MODE_OFF = 0
    const val MODE_MANUAL = 1
    const val MODE_AUTO = 2

    private val targets = listOf(
        "com.oculus.vrshell",
        "com.oculus.shellenv",
        "com.oculus.systemux"
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, mode)
            .apply()
    }

    fun mode(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, MODE_OFF)
    }

    fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Throwable) {
            // Shizuku is unavailable.
        }
    }

    /**
     * Executes a shell command through Shizuku's process API.
     * Uses reflection because newProcess is private in the
     * Shizuku 13.1.5 Kotlin API.
     */
    private fun executeShizukuCommand(command: String): Pair<Int, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val output = process.inputStream
                .bufferedReader()
                .use { it.readText() }
                .trim()

            val error = process.errorStream
                .bufferedReader()
                .use { it.readText() }
                .trim()

            val exitCode = process.waitFor()

            process.destroy()

            Pair(
                exitCode,
                output.ifBlank { error }
            )

        } catch (e: Exception) {
            Pair(-1, "Shizuku execution failed: ${e.message}")
        }
    }

    /**
     * Attempts to clear Horizon shell packages one at a time.
     *
     * WARNING:
     * Clearing Horizon system application data can reset launcher
     * settings and other system state. Only run when intended.
     */
    fun reset(
        context: Context,
        allowSettingsFallback: Boolean = true,
        callback: (String) -> Unit
    ) {

        if (!shizukuReady()) {

            if (allowSettingsFallback) {

                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:com.oculus.vrshell")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                try {
                    context.startActivity(intent)

                    callback(
                        "Shizuku unavailable: opened Horizon Shell settings. " +
                        "Clear storage manually if intended."
                    )

                } catch (_: Exception) {

                    callback(
                        "Shizuku is unavailable and app settings could not be opened."
                    )
                }

            } else {

                callback(
                    "Shizuku is not running or permission is not granted."
                )
            }

            return
        }

        Thread {

            var output = "No Horizon shell package could be cleared."

            for (pkg in targets) {

                val (code, result) = executeShizukuCommand(
                    "pm clear $pkg"
                )

                if (code == 0 && result.contains("Success", true)) {

                    output = "Cleared $pkg: $result"
                    break

                } else {

                    output = "$pkg: ${result.ifBlank { "exit $code" }}"
                }
            }

            mainHandler.post {
                callback(output)
            }

        }.start()
    }
}
