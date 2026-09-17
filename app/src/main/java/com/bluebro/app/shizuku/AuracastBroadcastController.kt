package com.bluebro.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Binds the Shizuku-privileged [AuracastBroadcastService] on demand and
 * forwards broadcast start/stop calls to it, with zero UI and no user
 * interaction on the receiving device beyond the one-time Shizuku/Bluetooth
 * grants handled in [com.bluebro.app.MainActivity].
 *
 * [CompanionPresenceService] can be cold-started by the system purely from
 * a BLE presence event (e.g. right after a reboot, before the app's own UI
 * has ever run in this process), in which case Shizuku's binder pairing may
 * not have finished yet. Rather than dropping that first event, [dispatch]
 * queues the call behind [Shizuku.addBinderReceivedListenerSticky] so it
 * still runs — silently, in the background — the moment the binder is up.
 */
object AuracastBroadcastController {

    private const val TAG = "AuracastController"

    private var service: IAuracastBroadcastService? = null
    private var pendingAction: ((IAuracastBroadcastService) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IAuracastBroadcastService.Stub.asInterface(binder)
            service = bound
            pendingAction?.let { runAction(bound, it) }
            pendingAction = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun startBroadcast(context: Context) = dispatch(context) { it.startBroadcast() }

    fun stopBroadcast(context: Context) = dispatch(context) { it.stopBroadcast() }

    private fun dispatch(context: Context, action: (IAuracastBroadcastService) -> Unit) {
        if (Shizuku.pingBinder()) {
            dispatchWithBinder(context, action)
            return
        }

        // No dialog, no notification — just wait for the binder in the
        // background and run the call once it's up.
        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                dispatchWithBinder(context, action)
            }
        })
    }

    private fun dispatchWithBinder(context: Context, action: (IAuracastBroadcastService) -> Unit) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku permission not granted; skipping broadcast call")
            return
        }

        val current = service
        if (current != null) {
            runAction(current, action)
            return
        }

        pendingAction = action
        val args = Shizuku.UserServiceArgs(ComponentName(context, AuracastBroadcastService::class.java))
            .daemon(false)
            .processNameSuffix("auracast")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, connection)
    }

    private fun runAction(service: IAuracastBroadcastService, action: (IAuracastBroadcastService) -> Unit) {
        runCatching { action(service) }.onFailure { Log.e(TAG, "Broadcast call failed", it) }
    }
}
