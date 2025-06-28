package com.websarva.wings.android.bledev.ble

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
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.nio.charset.StandardCharsets
import java.util.UUID

class BlePeripheralManager(private val context: Context, private val bluetoothAdapter: BluetoothAdapter) {

    private var gattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    // 受信メッセージをActivityに渡すためのコールバックインターフェース
    var onMessageReceived: ((String, String) -> Unit)? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setupGattServer() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(ChatGattProfile.CHAT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        messageCharacteristic = BluetoothGattCharacteristic(
            ChatGattProfile.MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(messageCharacteristic)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object: BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (device != null) {
                Log.d("BlePeripheralManager", "ConnectionStateChange: ${device.address}, status: $status, newState: $newState")
            }
            // 接続状態の変更をハンドリング
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
            if(characteristic.uuid == ChatGattProfile.MESSAGE_CHARACTERISTIC_UUID && value != null) {
                val receivedMessage = String(value, StandardCharsets.UTF_8)
                Log.d("BlePeripheralManager", "Received message: $receivedMessage from ${device?.address}")
                if (device != null) {
                    onMessageReceived?.invoke(receivedMessage, device.name ?: device.address)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun startAdvertising() {
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.w("BlePeripheralManager", "BluetoothLEAdvertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(ChatGattProfile.CHAT_SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d("BlePeripheralManager", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BlePeripheralManager", "Advertising failed: $errorCode")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun closeGattServer() {
        gattServer?.close()
        gattServer = null
    }

    // 接続されているセントラルに対してメッセージを通知 (ペリフェラル側から送信)
    @SuppressLint("MissingPermission")
    fun notifyMessage(message: String) {
        val characteristic = messageCharacteristic ?: return
        characteristic.value = message.toByteArray(StandardCharsets.UTF_8)

        gattServer?.connectedDevices?.forEach { device ->
            // 通知が有効になっているかどうかを確認する必要がある場合もあります
            gattServer?.notifyCharacteristicChanged(device, characteristic, false) // falseはconfirmなし
        }
    }

}