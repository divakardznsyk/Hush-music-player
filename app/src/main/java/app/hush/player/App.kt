package app.hush.player

import android.app.Application
import java.io.File

/** Saves the last crash so the app can show it on next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
