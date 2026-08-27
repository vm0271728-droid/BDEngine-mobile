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
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var persistedUsageMs = preferences.getLong(PREF_TOTAL_USAGE_MS, 0L).coerceAtLeast(0L)
    private var sessionStartedElapsedMs: Long? = null

    fun loadingTextForEntry(): String {
        val strings = AppLocale.strings(context)
        val awayMs = timeSinceLastExitMs() ?: return strings.splashRecent

        return when {
            awayMs >= MONTH_31_MS -> strings.splashMonth
            awayMs >= WEEK_MS -> strings.splashWeek
            awayMs >= DAY_MS -> strings.splashDay
            awayMs >= HOUR_MS -> strings.splashHour
            else -> strings.splashRecent
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
