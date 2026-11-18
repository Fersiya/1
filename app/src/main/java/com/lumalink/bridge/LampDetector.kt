package com.lumalink.bridge

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid

/** Похоже ли найденное BLE-устройство на умную лампу (Tuya и совместимые). */
internal fun detectSmartLamp(result: ScanResult): Boolean {
    val record = result.scanRecord ?: return false
    val uuids = record.serviceUuids ?: emptyList()
    if (uuids.contains(ParcelUuid(BridgeService.LIGHT_SERVICE_PRIMARY)) ||
        uuids.contains(ParcelUuid(BridgeService.LIGHT_SERVICE_ALT)) ||
        uuids.contains(ParcelUuid(BridgeService.LIGHT_SERVICE_TUYA_FD50))
    ) {
        return true
    }
    val sData = record.serviceData
    if (sData != null) {
        for (key in sData.keys) {
            val u = key.uuid.toString().lowercase()
            if (u.startsWith("00001910") || u.startsWith("0000a201") || u.startsWith("0000fd50")) return true
        }
    }
    if (record.getManufacturerSpecificData(0x07D0) != null) {
        return true
    }
    return false
}
