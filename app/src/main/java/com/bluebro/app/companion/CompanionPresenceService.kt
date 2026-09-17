package com.bluebro.app.companion

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log
import com.bluebro.app.shizuku.AuracastBroadcastController

/**
 * Bound by the system whenever the associated companion device enters or
 * leaves BLE range. That companion device needs no app, no pairing setup
 * beyond the one-time association, and no configuration of its own — this
 * service and [AuracastBroadcastController] do all the work on this device.
 * Toggles the Auracast broadcast through the Shizuku-privileged
 * [AuracastBroadcastController].
 */
class CompanionPresenceService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val watchedAssociationId = CompanionDeviceStore.getAssociationId(this)
        if (watchedAssociationId == CompanionDeviceStore.NO_ASSOCIATION ||
            event.associationId != watchedAssociationId
        ) {
            return
        }

        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> {
                Log.d(TAG, "Companion device appeared (association #${event.associationId})")
                AuracastBroadcastController.startBroadcast(applicationContext)
            }

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> {
                Log.d(TAG, "Companion device disappeared (association #${event.associationId})")
                AuracastBroadcastController.stopBroadcast(applicationContext)
            }

            else -> Log.d(
                TAG,
                "Unhandled presence event type ${event.event} for association #${event.associationId}",
            )
        }
    }

    companion object {
        private const val TAG = "CompanionPresence"
    }
}
