package com.pankaj.mlbbdraft.data

import android.content.Context
import com.pankaj.mlbbdraft.engine.model.PlayerProfile

/**
 * Persists the hero profile.
 *
 * Comfort is the single source of truth and "owned" is derived from it: a hero you
 * rated is a hero you have. One list to maintain instead of two, which matters
 * because nobody will curate 130 heroes twice.
 *
 * Stored as a flat string rather than JSON so it stays trivially inspectable with
 * `adb shell run-as ... cat`, and so the app needs no serialization plugin.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PlayerProfile {
        val comfort = prefs.getString(KEY_COMFORT, "")
            .orEmpty()
            .split(';')
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val id = entry.substringBefore(':')
                val value = entry.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
                if (id.isBlank()) null else id to value.coerceIn(0, 5)
            }
            .toMap()

        return PlayerProfile(
            owned = comfort.filterValues { it > 0 }.keys,
            comfort = comfort,
            restrictToOwned = prefs.getBoolean(KEY_RESTRICT, false),
        )
    }

    /** Whether the top pick is read aloud. Off by default — speech has to be opted into. */
    var speakSuggestions: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SPEAK, value).apply()
        }

    fun save(profile: PlayerProfile) {
        prefs.edit()
            .putString(
                KEY_COMFORT,
                profile.comfort.entries
                    .filter { it.value > 0 }
                    .joinToString(";") { "${it.key}:${it.value}" },
            )
            .putBoolean(KEY_RESTRICT, profile.restrictToOwned)
            .apply()
    }

    private companion object {
        const val PREFS = "draft_profile"
        const val KEY_COMFORT = "comfort"
        const val KEY_RESTRICT = "restrict_to_owned"
        const val KEY_SPEAK = "speak_suggestions"
    }
}
