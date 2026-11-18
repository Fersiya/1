package com.lumalink.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Запускает сервис как foreground с постоянным уведомлением «LumaLink активен». */
internal fun Service.startBridgeForeground() {
    val channelId = "lumalink_bridge_channel"
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(channelId, "LumaLink Мост", NotificationManager.IMPORTANCE_LOW)
    )

    val openAppIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val stopIntent = PendingIntent.getService(
        this, 1, Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle("LumaLink активен")
        .setContentText("Синхронизация света ПК → Bluetooth")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentIntent(openAppIntent)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopIntent)
        .setOngoing(true)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
        startForeground(1, notification)
    }
}

/** Держит CPU активным до 8 часов, пока работает мост. */
internal fun Context.acquireBridgeWakeLock(): PowerManager.WakeLock {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaLink::WakeLock").apply {
        acquire(8 * 60 * 60 * 1000L)
    }
}
