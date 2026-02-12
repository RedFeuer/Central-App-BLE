package com.example.shared

import java.util.UUID

object BleUuids {
    val SERVICE: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")

    val CMD_RX: UUID = UUID.fromString("0000feed-0001-1000-8000-00805f9b34fb")
    val CMD_TX: UUID = UUID.fromString("0000feed-0002-1000-8000-00805f9b34fb")

    val DATA_RX: UUID = UUID.fromString("0000feed-0003-1000-8000-00805f9b34fb")
    val DATA_TX: UUID = UUID.fromString("0000feed-0004-1000-8000-00805f9b34fb")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
