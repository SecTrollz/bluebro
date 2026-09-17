package com.bluebro.app.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the [android.companion.AssociationInfo] id for the companion
 * device so [CompanionPresenceService] can tell its presence events apart
 * from those of any other companion association, plus the last known
 * presence state so the UI can show what's happening right now even
 * though it played no part in making it happen.
 */
object CompanionDeviceStore {

    const val NO_ASSOCIATION = -1

    private const val PREFS_NAME = "companion_presence"
    private const val KEY_ASSOCIATION_ID = "association_id"
    private const val KEY_COMPANION_NEARBY = "companion_nearby"

    fun setAssociationId(context: Context, associationId: Int) {
        prefs(context).edit().putInt(KEY_ASSOCIATION_ID, associationId).apply()
    }

    fun getAssociationId(context: Context): Int =
        prefs(context).getInt(KEY_ASSOCIATION_ID, NO_ASSOCIATION)

    fun setCompanionNearby(context: Context, nearby: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPANION_NEARBY, nearby).apply()
    }

    /** Null until the first presence event has been observed. */
    fun isCompanionNearby(context: Context): Boolean? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_COMPANION_NEARBY)) {
            prefs.getBoolean(KEY_COMPANION_NEARBY, false)
        } else {
            null
        }
    }

    fun addChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
