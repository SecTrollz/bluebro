package com.bluebro.app.companion

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log

/**
 * Bound by the system whenever Device A (the associated companion device)
 * enters or leaves BLE range. Wiring the Auracast broadcast toggle through
 * Shizuku is deliberately deferred until presence events are confirmed to
 * fire reliably on-device.
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
                Log.d(TAG, "Device A appeared (association #${event.associationId})")
                // TODO: startBroadcastViaShizuku() once presence is verified.
            }

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> {
                Log.d(TAG, "Device A disappeared (association #${event.associationId})")
                // TODO: stopBroadcastViaShizuku() once presence is verified.
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
