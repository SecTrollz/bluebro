package com.bluebro.app.shizuku

import android.util.Log

/**
 * Runs in a separate process spawned by Shizuku with the shell/root
 * privileges the user granted it — unlike a plain [Runtime.exec] call from
 * the app's own process, this one actually has permission to reach the
 * `bluetooth_le_audio` system service.
 *
 * The exact `service call` argument encoding below was reverse-engineered
 * from `IBluetoothLeAudio`'s AIDL transaction ordering; it is unverified
 * against real hardware and may need adjusting per OS build.
 */
class AuracastBroadcastService : IAuracastBroadcastService.Stub() {

    override fun startBroadcast() {
        runServiceCall(START_BROADCAST_ARGS)
    }

    override fun stopBroadcast() {
        runServiceCall(STOP_BROADCAST_ARGS)
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun runServiceCall(args: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "service call bluetooth_le_audio $args"))
            val exitCode = process.waitFor()
            Log.d(TAG, "service call bluetooth_le_audio $args -> exit $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "service call bluetooth_le_audio $args failed", e)
        }
    }

    companion object {
        private const val TAG = "AuracastBroadcastSvc"
        private const val START_BROADCAST_ARGS = "1 s16 'com.android.systemui' i32 0"
        private const val STOP_BROADCAST_ARGS = "2 s16 'com.android.systemui'"
    }
}
