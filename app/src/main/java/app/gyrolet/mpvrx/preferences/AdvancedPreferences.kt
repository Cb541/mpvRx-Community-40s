/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.NotificationStyle

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  companion object {
    const val AUTO_BACKUP_ENABLED_KEY = "auto_backup_enabled"
    // Kept only to ignore backups created by the short-lived separate-folder implementation.
    const val AUTO_BACKUP_FOLDER_URI_KEY = "auto_backup_folder_uri"
  }

  val mpvConfStorageUri = preferenceStore.getString("mpv_conf_storage_location_uri")
  val mpvConf = preferenceStore.getString("mpv.conf", CommunityPlaybackCacheDefaults.mpvConf)
  val inputConf = preferenceStore.getString("input.conf")
  val mpvConfOverrides = preferenceStore.getStringSet("mpv_conf_overrides", emptySet())

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")
  val debugLogSizeMb = preferenceStore.getInt("debug_log_size_mb", 3)

  val autoBackupEnabled = preferenceStore.getBoolean(AUTO_BACKUP_ENABLED_KEY, false)

  val enabledStatisticsPage = preferenceStore.getInt("enabled_stats_page", 0)

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

  val enableLuaScripts = preferenceStore.getBoolean("enable_lua_scripts", false)
  val selectedLuaScripts = preferenceStore.getStringSet("selected_lua_scripts", emptySet())

  fun userScriptsConfigurationKey(): String {
    if (!enableLuaScripts.get()) return "disabled"
    return buildString {
      val location = mpvConfStorageUri.get()
      append(location.length).append(':').append(location)
      selectedLuaScripts.get().sorted().forEach { script ->
        append('|').append(script.length).append(':').append(script)
      }
    }
  }

  val enableP2pStreaming = preferenceStore.getBoolean("enable_p2p_streaming", true)

  val enableHlsProxy = preferenceStore.getBoolean("enable_hls_proxy", true)

  /** Notification style for the playback service (Media vs Progress-centric on Android 16+). */
  val notificationStyle = preferenceStore.getEnum("notification_style", NotificationStyle.Media)
}
