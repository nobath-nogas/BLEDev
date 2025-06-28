package com.websarva.wings.android.bledev.ui

// チャットメッセージのデータクラス
data class ChatMessage(val sender: String, val text: String, val isMe: Boolean)