package com.bluebro.app

import android.Manifest
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
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

class MainActivity : AppCompatActivity() {

    private lateinit var companionDeviceManager: CompanionDeviceManager
    private lateinit var statusText: TextView

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAssociation()
            } else {
                statusText.text = getString(R.string.status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        companionDeviceManager =
            getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.associateButton).setOnClickListener {
            requestBluetoothPermissionThenAssociate()
        }

        val existingAssociationId = CompanionDeviceStore.getAssociationId(this)
        if (existingAssociationId != CompanionDeviceStore.NO_ASSOCIATION) {
            statusText.text = getString(R.string.status_already_watching, existingAssociationId)
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
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
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

                    statusText.text = getString(R.string.status_watching, association.displayName)
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(TAG, "Association failed: $error")
                    statusText.text = getString(R.string.status_association_failed, error)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    companion object {
        private const val TAG = "Bluebro"
    }
}
