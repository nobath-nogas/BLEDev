package com.websarva.wings.android.bledev.ble

import java.util.UUID

object ChatGattProfile {
    // 実際のUUIDは衝突しないように自分で生成してください
    val CHAT_SERVICE_UUID: UUID = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB")
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB")
}