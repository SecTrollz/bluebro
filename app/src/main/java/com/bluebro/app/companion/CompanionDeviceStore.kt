package com.bluebro.app.companion

import android.content.Context

/**
 * Persists the [android.companion.AssociationInfo] id for "Device A" so the
 * (separate-process) [CompanionPresenceService] can tell its presence events
 * apart from those of any other companion association.
 */
object CompanionDeviceStore {

    const val NO_ASSOCIATION = -1

    private const val PREFS_NAME = "companion_presence"
    private const val KEY_ASSOCIATION_ID = "association_id"

    fun setAssociationId(context: Context, associationId: Int) {
        prefs(context).edit().putInt(KEY_ASSOCIATION_ID, associationId).apply()
    }

    fun getAssociationId(context: Context): Int =
        prefs(context).getInt(KEY_ASSOCIATION_ID, NO_ASSOCIATION)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
