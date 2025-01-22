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


/**
 * MainActivity - основной класс приложения для умных часов
 * Реализует BLE (Bluetooth Low Energy) функциональность для обмена сообщениями с телефоном
 */
class MainActivity : ComponentActivity() {

    // Константы для BLE
    companion object {
        private const val TAG = "BLE" // Тег для логирования
        private const val MAX_RETRIES = 3 // Максимальное количество попыток отправки


        // UUID для BLE сервиса и характеристики
        private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
    }

    // Bluetooth компоненты
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var gattServer: BluetoothGattServer
    private var connectedDevice: BluetoothDevice? = null

    // Состояние UI
    private var connectionStatus by mutableStateOf("Отключено")
    private var lastReceivedMessage by mutableStateOf("")

    /**
     * Необходимые разрешения в зависимости от версии Android
     */
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

    /**
     * Обработчик запроса разрешений
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeBluetooth()
            startAdvertising()
            setupGattServer()
        } else {
            connectionStatus = "Нет необходимых разрешений"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        setupUI()
    }

    /**
     * Настройка пользовательского интерфейса
     */
    private fun setupUI() {
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

    /**
     * Проверка и запрос необходимых разрешений
     */
    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initializeBluetoothComponents()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Инициализация всех Bluetooth компонентов
     */
    private fun initializeBluetoothComponents() {
        initializeBluetooth()
        startAdvertising()
        setupGattServer()
    }

    /**
     * Инициализация основных Bluetooth объектов
     */
    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
    }

    /**
     * Настройка и запуск BLE рекламы
     */
    private fun startAdvertising() {
        if (!hasBluetoothPermission()) return

        val settings = createAdvertiseSettings()
        val data = createAdvertiseData()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    /**
     * Создание настроек BLE рекламы
     */
    private fun createAdvertiseSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
    }

    /**
     * Создание данных для BLE рекламы
     */
    private fun createAdvertiseData(): AdvertiseData {
        return AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
    }

    /**
     * Callback для отслеживания статуса рекламы
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Реклама BLE успешно запущена")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Ошибка запуска рекламы BLE: $errorCode")
        }
    }

    /**
     * Настройка GATT сервера
     */
    private fun setupGattServer() {
        if (!hasBluetoothPermission()) return

        try {
            createGattServer()
            setupGattService()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка настройки GATT сервера: ${e.message}")
        }
    }

    /**
     * Создание GATT сервера
     */
    private fun createGattServer() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        Log.d(TAG, "GATT сервер успешно открыт")
    }

    /**
     * Настройка GATT сервиса и характеристики
     */
    private fun setupGattService() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = createCharacteristic()

        service.addCharacteristic(characteristic)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        gattServer.addService(service)
        Log.d(TAG, "Сервис добавлен в GATT сервер")
    }

    /**
     * Создание характеристики для GATT сервиса
     */
    private fun createCharacteristic(): BluetoothGattCharacteristic {
        return BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }

    /**
     * Callback для обработки событий GATT сервера
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handleConnectionStateChange(device, status, newState)
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
            handleCharacteristicWrite(device, requestId, characteristic, responseNeeded, value)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicRead(device, requestId, offset, characteristic)
        }
    }

    /**
     * Обработка изменения состояния подключения
     */
    private fun handleConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Log.d(TAG, "Изменение состояния подключения: status=$status, newState=$newState")
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            connectedDevice = device
            connectionStatus = "Подключено к ${device.address}"
        } else {
            connectedDevice = null
            connectionStatus = "Отключено"
        }
    }

    /**
     * Обработка запроса на запись характеристики
     */
    private fun handleCharacteristicWrite(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        responseNeeded: Boolean,
        value: ByteArray
    ) {
        if (characteristic.uuid != CHARACTERISTIC_UUID) return

        try {
            val message = String(value, Charsets.UTF_8)
            Log.d(TAG, "Получено сообщение: $message")
            lastReceivedMessage = message

            if (responseNeeded && hasBluetoothPermission()) {
                val response =
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, message.toByteArray(Charsets.UTF_8))
                if (!response) {
                    Log.e(TAG, "Ошибка отправки ответа")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки сообщения: ${e.message}")
            if (responseNeeded) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    /**
     * Обработка запроса на чтение характеристики
     */
    private fun handleCharacteristicRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (characteristic.uuid != CHARACTERISTIC_UUID || !hasBluetoothPermission()) return

        val value = lastReceivedMessage.toByteArray(Charsets.UTF_8)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        Log.d(TAG, "Отправлен ответ на чтение: $lastReceivedMessage")
    }

    /**
     * Отправка сообщения на телефон
     */
    private fun sendMessageToPhone(message: String) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Отсутствует разрешение BLUETOOTH_CONNECT")
            return
        }

        var retryCount = 0
        var success = false

        while (!success && retryCount < MAX_RETRIES) {
            try {
                connectedDevice?.let { device ->
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(TAG, "Отсутствует разрешение BLUETOOTH_CONNECT")
                        return
                    }

                    val service = gattServer.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                    characteristic?.let {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ActivityCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    // Преобразуем сообщение в байты
                                    val messageBytes = message.toByteArray(Charsets.UTF_8)
                                    // Устанавливаем значение характеристики
                                    characteristic.value = messageBytes
                                    // Отправляем уведомление
                                    success = gattServer.notifyCharacteristicChanged(device, characteristic, false)
                                    if (success) {
                                        Log.d(TAG, "Сообщение отправлено на телефон (новый API): $message")
                                    } else {
                                        Log.e(TAG, "Не удалось отправить сообщение")
                                    }
                                } else {
                                    throw SecurityException("Отсутствует разрешение BLUETOOTH_CONNECT")
                                }
                            } else {
                                // Для более старых версий Android
                                if (ActivityCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    @Suppress("DEPRECATION")
                                    characteristic.value = message.toByteArray(Charsets.UTF_8)
                                    @Suppress("DEPRECATION")
                                    success = gattServer.notifyCharacteristicChanged(device, characteristic, false)
                                    if (success) {
                                        Log.d(TAG, "Сообщение отправлено на телефон (старый API): $message")
                                    } else {
                                        Log.e(TAG, "Не удалось отправить сообщение")
                                    }
                                } else {
                                    throw SecurityException("Отсутствует разрешение BLUETOOTH_CONNECT")
                                }
                            }
                        } catch (securityException: SecurityException) {
                            Log.e(TAG, "Ошибка прав доступа: ${securityException.message}")
                            return
                        }
                    } ?: throw Exception("Характеристика не найдена")
                } ?: throw Exception("Нет подключенного устройства")
            } catch (e: Exception) {
                when (e) {
                    is SecurityException -> {
                        Log.e(TAG, "Ошибка прав доступа: ${e.message}")
                        return
                    }
                    else -> {
                        retryCount++
                        Log.e(TAG, "Попытка $retryCount: Ошибка отправки сообщения: ${e.message}")
                        if (retryCount < MAX_RETRIES) {
                            Thread.sleep(1000) // Пауза перед следующей попыткой
                        }
                    }
                }
            }
        }

        if (!success) {
            Log.e(TAG, "Не удалось отправить сообщение после $MAX_RETRIES попыток")
        }
    }



    /**
     * Проверка наличия разрешения Bluetooth
     * @return true если разрешение есть, false если нет
     */


    private fun hasBluetoothPermission(): Boolean {
        return run {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

/**
 * Компонент пользовательского интерфейса для умных часов
 */

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun WearApp(
    connectionStatus: String,
    lastMessage: String,
    onSendMessage: () -> Unit
) {
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
                    // Статус подключения
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.body1,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Отображение последнего полученного сообщения
                    if (lastMessage.isNotEmpty()) {
                        Text(
                            text = "Полученное сообщение:",
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

                    // Кнопка отправки сообщения
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
}}