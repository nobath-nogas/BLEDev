package com.websarva.wings.android.bledev

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.websarva.wings.android.bledev.ble.BleCentralManager
import com.websarva.wings.android.bledev.ble.BlePeripheralManager
import com.websarva.wings.android.bledev.ui.ChatMessage
import com.websarva.wings.android.bledev.ui.ChatScreen
import com.websarva.wings.android.bledev.ui.theme.BLEDevTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var blePeripheralManager: BlePeripheralManager
    private lateinit var bleCentralManager: BleCentralManager

    // メッセージリストを管理するstateflow
    private val _message = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _message.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing BLE...")
    val statusMessage = _statusMessage.asStateFlow()

    // Bluetooth有効化のコールバック
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            checkBluetoothPermissions()
        } else {
            Toast.makeText(this, "Bluetooth not enabled, exiting.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // パーミッション要求のコールバック
    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val allGranted = perms.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
            setupBleManagers()
        } else {
            Toast.makeText(this, "Bluetooth permissions denied, exiting.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bluetoothアダプターの取得と初期化
        val bluetoothManager: BluetoothManager? = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter ?: run {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            BLEDevTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatScreen(
                        messages = messages.collectAsState().value,
                        statusMessage = statusMessage.collectAsState().value,
                        onSendMessage = { message ->
                            // メッセージ送信ロジック
                            bleCentralManager.sendMessage(message) // 接続している相手にセントラルとして送信
                            // 通知も送信したい場合は、相手がペリフェラルとして通知を受け取れるように
                            blePeripheralManager.notifyMessage(message)

                            addMessage(ChatMessage("Me", message, true)) // 自分のメッセージは右寄せなど表示を変える
                        }
                    )
                }
            }
        }

        // Bluetooth有効化と権限チェック
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            checkBluetoothPermissions()
        }
    }

    private fun checkBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            setupBleManagers()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBleManagers() {
        _statusMessage.value = "BLE Initialized. Scanning and Advertising..."

        blePeripheralManager = BlePeripheralManager(this, bluetoothAdapter)
        bleCentralManager = BleCentralManager(this, bluetoothAdapter)

        // 受信メッセージのコールバック設定
        blePeripheralManager.onMessageReceived = { message, sender ->
            addMessage(ChatMessage(sender, message, false)) // 相手のメッセージ
        }
        bleCentralManager.onMessageReceived = { message, sender ->
            addMessage(ChatMessage(sender, message, false)) // 相手のメッセージ
        }

        // 接続状態のコールバック設定
        bleCentralManager.onConnected = { device ->
            _statusMessage.value = "Connected to ${device.name ?: device.address}"
            addMessage(ChatMessage("System", "Connected to ${device.name ?: device.address}", false))
        }
        bleCentralManager.onDisconnected = { device ->
            _statusMessage.value = "Disconnected from ${device.name ?: device.address}. Rescanning..."
            addMessage(ChatMessage("System", "Disconnected from ${device.name ?: device.address}", false))
        }

        // BLE機能のセットアップを開始
        blePeripheralManager.setupGattServer()
        blePeripheralManager.startAdvertising()
        bleCentralManager.startScanning()
    }

    private fun addMessage(message: ChatMessage) {
        _message.value = _message.value + message
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onDestroy() {
        super.onDestroy()
        blePeripheralManager.stopAdvertising()
        blePeripheralManager.closeGattServer()
        bleCentralManager.stopScanning()
        bleCentralManager.disconnectGatt()
        bleCentralManager.closeGatt()
    }
}

