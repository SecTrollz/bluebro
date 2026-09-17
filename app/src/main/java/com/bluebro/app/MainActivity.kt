package com.bluebro.app

import android.Manifest
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bluebro.app.companion.CompanionDeviceStore
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var companionDeviceManager: CompanionDeviceManager
    private lateinit var setupStatusText: TextView
    private lateinit var liveStatusText: TextView

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAssociation()
            } else {
                setupStatusText.text = getString(R.string.status_permission_denied)
            }
        }

    /** Reflects presence updates from [com.bluebro.app.companion.CompanionPresenceService]
     * while this screen happens to be open — it plays no part in making them happen. */
    private val presenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshLiveStatus() }

    /**
     * One-time setup only: after this completes, presence detection and
     * the Auracast toggle run entirely in the background via
     * [com.bluebro.app.companion.CompanionPresenceService] — no further
     * taps, screens, or notifications on this device.
     */
    private fun setUpAutomaticAuracast() {
        requestBluetoothPermissionThenAssociate()
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Shizuku permission ${if (granted) "granted" else "denied"}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        companionDeviceManager =
            getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        setupStatusText = findViewById(R.id.setupStatusText)
        liveStatusText = findViewById(R.id.liveStatusText)
        findViewById<Button>(R.id.associateButton).setOnClickListener {
            setUpAutomaticAuracast()
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        requestShizukuPermissionIfNeeded()

        val existingAssociationId = CompanionDeviceStore.getAssociationId(this)
        if (existingAssociationId != CompanionDeviceStore.NO_ASSOCIATION) {
            setupStatusText.text = getString(R.string.status_already_watching, existingAssociationId)
        }
    }

    override fun onResume() {
        super.onResume()
        CompanionDeviceStore.addChangeListener(this, presenceListener)
        refreshLiveStatus()
    }

    override fun onPause() {
        CompanionDeviceStore.removeChangeListener(this, presenceListener)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun refreshLiveStatus() {
        liveStatusText.text = when (CompanionDeviceStore.isCompanionNearby(this)) {
            true -> getString(R.string.live_status_nearby)
            false -> getString(R.string.live_status_away)
            null -> getString(R.string.live_status_unknown)
        }
    }

    private fun requestShizukuPermissionIfNeeded() {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku is not running; the Auracast broadcast toggle will be skipped")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestBluetoothPermissionThenAssociate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            startAssociation()
        }
    }

    private fun startAssociation() {
        // BLE-only filter, deliberately: CompanionDeviceManager.associate()
        // never bonds a device on its own -- that only happens if the app
        // explicitly calls BluetoothDevice#createBond(), which this code
        // never does -- but the classic BluetoothDeviceFilter can fold a
        // system "pair" confirmation into the same picker gesture for some
        // device categories. An empty ScanFilter matches any BLE-advertising
        // device with no name/address/service constraints, so the companion
        // device just needs Bluetooth on and to be in range: no pairing, no
        // PIN, nothing pre-paired.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().build()).build())
            .setSingleDevice(false)
            .build()

        companionDeviceManager.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationCreated(association: AssociationInfo) {
                    Log.d(TAG, "Device associated: ${association.displayName} (id=${association.id})")
                    CompanionDeviceStore.setAssociationId(this@MainActivity, association.id)

                    val deviceAddress = association.deviceMacAddress?.toString()
                    if (deviceAddress != null) {
                        companionDeviceManager.startObservingDevicePresence(deviceAddress)
                    } else {
                        Log.w(TAG, "Association ${association.id} has no MAC address to observe")
                    }

                    // Last step of setup: make sure Shizuku is granted too, so
                    // the broadcast toggle never needs a screen on this device again.
                    requestShizukuPermissionIfNeeded()

                    setupStatusText.text = getString(R.string.status_watching, association.displayName)
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(TAG, "Association failed: $error")
                    setupStatusText.text = getString(R.string.status_association_failed, error)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    companion object {
        private const val TAG = "Bluebro"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
