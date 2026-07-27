package org.crosspoint.companion.ble

import java.util.UUID

object BleProtocol {
    const val VERSION = 6
    val SERVICE: UUID = UUID.fromString("7b1f0001-6f70-4f69-8f31-63726f737370")
    val CONTROL: UUID = UUID.fromString("7b1f0002-6f70-4f69-8f31-63726f737370")
    val EVENT: UUID = UUID.fromString("7b1f0003-6f70-4f69-8f31-63726f737370")
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
