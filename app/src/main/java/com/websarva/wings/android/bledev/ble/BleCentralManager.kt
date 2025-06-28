package com.websarva.wings.android.bledev.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.nio.charset.StandardCharsets
import java.util.UUID

// MainActivityの一部、またはBLEを管理する独立したクラス
class BleCentralManager(private val context: Context, private val bluetoothAdapter: BluetoothAdapter) {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var chatCharacteristic: BluetoothGattCharacteristic? = null

    // 発見されたデバイスや受信メッセージをActivityに渡すためのコールバックインターフェース
    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null
    var onMessageReceived: ((String, String) -> Unit)? = null
    var onConnected: ((BluetoothDevice) -> Unit)? = null
    var onDisconnected: ((BluetoothDevice) -> Unit)? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScanning() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.w("BleCentralManager", "BluetoothLEScanner not available")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(ChatGattProfile.CHAT_SERVICE_UUID))
                .build()
        )

        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        Log.d("BleCentralManager", "Scanning started...")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d("BleCentralManager", "Found device: ${result.device.name ?: "Unknown"} (${result.device.address})")
            onDeviceFound?.invoke(result.device)
            // 見つかったデバイスに自動接続する場合（チャットアプリでは自動接続が便利）
            connectToDevice(result.device)
            bluetoothLeScanner?.stopScan(this) // 接続後はスキャンを停止
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            // 複数のスキャン結果がバッチで返された場合に処理
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BleCentralManager", "Scan failed: $errorCode")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() {
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothGatt == null) { // 既に接続されていない場合
            bluetoothGatt = device.connectGatt(context, false, gattClientCallback)
            Log.d("BleCentralManager", "Connecting to device: ${device.address}")
        } else {
            Log.d("BleCentralManager", "Already connected or trying to connect.")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d("BleCentralManager", "Client ConnectionStateChange: ${gatt.device.address}, status: $status, newState: $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleCentralManager", "Connected to GATT server, discovering services...")
                gatt.discoverServices() // サービスを探索
                onConnected?.invoke(gatt.device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleCentralManager", "Disconnected from GATT server.")
                gatt.close()
                bluetoothGatt = null
                chatCharacteristic = null
                onDisconnected?.invoke(gatt.device)
                // 必要であれば再スキャンを開始
                startScanning()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ChatGattProfile.CHAT_SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(ChatGattProfile.MESSAGE_CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        Log.d("BleCentralManager", "Found chat characteristic. Ready to send/receive messages.")
                        chatCharacteristic = characteristic
                        gatt.setCharacteristicNotification(characteristic, true)

                        // Descriptorを有効にして通知を受け取るように設定
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) // Client Characteristic Configuration Descriptor UUID
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }
                }
            } else {
                Log.w("BleCentralManager", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleCentralManager", "Message sent successfully.")
            } else {
                Log.e("BleCentralManager", "Failed to send message: $status")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            // ペリフェラルからの通知を受信した場合
            if (characteristic.uuid == ChatGattProfile.MESSAGE_CHARACTERISTIC_UUID) {
                val receivedMessage = String(characteristic.value, StandardCharsets.UTF_8)
                Log.d("BleCentralManager", "Received message via notification: $receivedMessage")
                onMessageReceived?.invoke(receivedMessage, gatt.device.name ?: gatt.device.address)
            }
        }
    }

    // メッセージ送信関数 (セントラルとして)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(message: String) {
        val gatt = bluetoothGatt ?: run {
            Log.e("BleCentralManager", "GATT client not connected.")
            return
        }
        val characteristic = chatCharacteristic ?: run {
            Log.e("BleCentralManager", "Chat characteristic not found.")
            return
        }
        characteristic.value = message.toByteArray(StandardCharsets.UTF_8)
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            Log.e("BleCentralManager", "Failed to initiate write characteristic.")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectGatt() {
        bluetoothGatt?.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}