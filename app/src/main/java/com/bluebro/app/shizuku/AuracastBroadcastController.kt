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
 * forwards broadcast start/stop calls to it. No-ops (with a log warning)
 * if Shizuku isn't running or the user hasn't granted this app permission
 * yet — see [com.bluebro.app.MainActivity] for the permission request.
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
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku not available or permission not granted; skipping broadcast call")
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
