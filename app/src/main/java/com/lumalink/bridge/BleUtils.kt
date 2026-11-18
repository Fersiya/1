package com.lumalink.bridge

import android.bluetooth.BluetoothGatt

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Сбрасывает кэш GATT-служб Android (скрытый метод refresh), чтобы лампа переоткрылась «с нуля». */
internal fun refreshGattCache(gatt: BluetoothGatt) {
    try {
        val refreshMethod = gatt.javaClass.getMethod("refresh")
        refreshMethod.invoke(gatt)
    } catch (_: Exception) {
    }
}
