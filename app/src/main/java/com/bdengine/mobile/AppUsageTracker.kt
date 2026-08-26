package com.bdengine.mobile

import android.app.backup.BackupManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings

class AppUsageTracker(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "bdengine_mobile_settings"
        private const val PREF_TOTAL_USAGE_MS = "total_usage_ms_v1"
        private const val PREF_LAST_EXIT_WALL_MS = "last_exit_wall_ms_v1"
        private const val PREF_LAST_EXIT_ELAPSED_MS = "last_exit_elapsed_ms_v1"
        private const val PREF_LAST_EXIT_BOOT_COUNT = "last_exit_boot_count_v1"

        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS
        private const val WEEK_MS = 7L * DAY_MS
        private const val MONTH_31_MS = 31L * DAY_MS

        const val FIRST_OR_RECENT_TEXT = "Это приложение не официально, просто открывает страницу."
        const val ONE_HOUR_TEXT = "Продолжаем работу."
        const val ONE_DAY_TEXT = "Где ты пропадаешь, бегом за работу!!!"
        const val ONE_WEEK_TEXT = "Тебя долго не было..."
        const val ONE_MONTH_TEXT = "Я думал ты меня бросил... Тебя не было слишком долго..."
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var persistedUsageMs = preferences.getLong(PREF_TOTAL_USAGE_MS, 0L).coerceAtLeast(0L)
    private var sessionStartedElapsedMs: Long? = null

    fun loadingTextForEntry(): String {
        val awayMs = timeSinceLastExitMs() ?: return FIRST_OR_RECENT_TEXT

        return when {
            awayMs >= MONTH_31_MS -> ONE_MONTH_TEXT
            awayMs >= WEEK_MS -> ONE_WEEK_TEXT
            awayMs >= DAY_MS -> ONE_DAY_TEXT
            awayMs >= HOUR_MS -> ONE_HOUR_TEXT
            else -> FIRST_OR_RECENT_TEXT
        }
    }

    fun startSession() {
        if (sessionStartedElapsedMs == null) {
            sessionStartedElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun checkpoint() {
        val startedAt = sessionStartedElapsedMs ?: return
        val now = SystemClock.elapsedRealtime()
        val delta = (now - startedAt).coerceAtLeast(0L)

        if (delta > 0L) {
            persistedUsageMs = (persistedUsageMs + delta).coerceAtLeast(0L)
            sessionStartedElapsedMs = now
            preferences.edit().putLong(PREF_TOTAL_USAGE_MS, persistedUsageMs).apply()
            requestBackup()
        }
    }

    fun stopSessionAndRecordExit() {
        checkpoint()
        sessionStartedElapsedMs = null

        preferences.edit()
            .putLong(PREF_LAST_EXIT_WALL_MS, System.currentTimeMillis())
            .putLong(PREF_LAST_EXIT_ELAPSED_MS, SystemClock.elapsedRealtime())
            .putInt(PREF_LAST_EXIT_BOOT_COUNT, currentBootCount())
            .apply()

        requestBackup()
    }

    fun totalUsageMsNow(): Long {
        val startedAt = sessionStartedElapsedMs
        if (startedAt == null) return persistedUsageMs

        val currentDelta = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        return (persistedUsageMs + currentDelta).coerceAtLeast(0L)
    }

    private fun timeSinceLastExitMs(): Long? {
        val lastWall = preferences.getLong(PREF_LAST_EXIT_WALL_MS, -1L)
        if (lastWall < 0L) return null

        val lastElapsed = preferences.getLong(PREF_LAST_EXIT_ELAPSED_MS, -1L)
        val lastBootCount = preferences.getInt(PREF_LAST_EXIT_BOOT_COUNT, -1)
        val bootCount = currentBootCount()
        val nowElapsed = SystemClock.elapsedRealtime()

        // Same boot: use Android's monotonic clock, which is unaffected by changing
        // date/time in system settings.
        if (
            lastElapsed >= 0L &&
            lastBootCount >= 0 &&
            lastBootCount == bootCount &&
            nowElapsed >= lastElapsed
        ) {
            return nowElapsed - lastElapsed
        }

        // Across a reboot elapsedRealtime() is reset, so wall time is the only local
        // fallback. Clamp clock rollback to zero instead of accepting negative time.
        return (System.currentTimeMillis() - lastWall).coerceAtLeast(0L)
    }

    private fun currentBootCount(): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Throwable) {
            -1
        }
    }

    private fun requestBackup() {
        try {
            BackupManager(context).dataChanged()
        } catch (_: Throwable) {
            // Backup transport is optional on Android devices.
        }
    }
}
