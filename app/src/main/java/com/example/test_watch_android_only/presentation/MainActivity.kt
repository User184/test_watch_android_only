package com.example.test_watch_android_only.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.test_watch_android_only.presentation.theme.Test_watch_android_onlyTheme
import java.util.UUID


class MainActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var gattServer: BluetoothGattServer

    private var connectionStatus by mutableStateOf("Disconnected")
    private var lastReceivedMessage by mutableStateOf("")

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeBluetooth()
            startAdvertising()
            setupGattServer()
        } else {
            connectionStatus = "Permissions denied"
        }
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        setContent {
            WearApp(
                connectionStatus = connectionStatus,
                lastMessage = lastReceivedMessage,
                onSendMessage = {
                    sendMessageToPhone("Привет телефон, это часы!")
                }
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initializeBluetooth()
            startAdvertising()
            setupGattServer()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BLE", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Advertising failed with error: $errorCode")
        }
    }

    private fun setupGattServer() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
            return
        }

        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            Log.d("BLE", "GATT Server opened successfully")

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            Log.d("BLE", "Created service with UUID: $SERVICE_UUID")

            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or // Добавляем поддержку записи без ответа
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            Log.d("BLE", "Created characteristic with UUID: $CHARACTERISTIC_UUID")

            service.addCharacteristic(characteristic)
            gattServer.addService(service)
            Log.d("BLE", "Service added to GATT Server")
        } catch (e: Exception) {
            Log.e("BLE", "Error setting up GATT Server: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d("BLE", "Connection state changed: status=$status, newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d("BLE", "Device connected: ${device.address}")
                connectedDevice = device
                connectionStatus = "Connected to ${device.address}"
            } else {
                Log.d("BLE", "Device disconnected: ${device.address}")
                connectedDevice = null
                connectionStatus = "Disconnected"
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                try {
                    // Декодируем сообщение в UTF-8
                    val message = String(value, Charsets.UTF_8)
                    Log.d("BLE", "Received message: $message")
                    lastReceivedMessage = message

                    if (responseNeeded) {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
                            return
                        }
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        Log.d("BLE", "Response sent to device")
                    }
                } catch (e: Exception) {
                    Log.e("BLE", "Error processing message: ${e.message}")
                    if (responseNeeded) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                }
            }
        }

        // Добавим обработку чтения характеристики
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d("BLE", "Read request received for characteristic: ${characteristic.uuid}")
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
                    return
                }
                // Отправляем последнее сообщение при запросе чтения
                val value = lastReceivedMessage.toByteArray(Charsets.UTF_8)
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                Log.d("BLE", "Read response sent: $lastReceivedMessage")
            }
        }
    }

    private var connectedDevice: BluetoothDevice? = null

    private fun sendMessageToPhone(message: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BLE", "Missing BLUETOOTH_CONNECT permission")
            return
        }

        connectedDevice?.let { device ->
            try {
                val characteristic = BluetoothGattCharacteristic(
                    CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    0
                )
                characteristic.value = message.toByteArray(Charsets.UTF_8)
                gattServer.notifyCharacteristicChanged(device, characteristic, false)
                Log.d("BLE", "Message sent to phone: $message")
            } catch (e: Exception) {
                Log.e("BLE", "Error sending message: ${e.message}")
            }
        } ?: Log.e("BLE", "No connected device")
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun WearApp(connectionStatus: String, lastMessage: String, onSendMessage: () -> Unit) {
    Test_watch_android_onlyTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            val scrollState = rememberScrollState()

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.body1,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (lastMessage.isNotEmpty()) {
                        Text(
                            text = "Received message:",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = lastMessage,
                            style = MaterialTheme.typography.body1,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onSendMessage,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "Отправить привет",
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
