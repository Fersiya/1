package com.lumalink.bridge

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredPc(val name: String, val ip: String)

/** Поиск сервера LumaLink на ПК широковещательным UDP-запросом (порт 8001). */
@SuppressLint("MissingPermission")
object PcDiscovery {
    /** Возвращает найденный ПК или null (не найден / мост уже запущен). */
    suspend fun find(): DiscoveredPc? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1500
            }
            val payload = "LUMALINK_DISCOVER".toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            for (attempt in 0 until 6) {
                if (BridgeService.isRunning.value) return@withContext null
                try {
                    socket.send(DatagramPacket(payload, payload.size, broadcastAddr, 8001))
                    val buf = ByteArray(512)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val senderIp = resp.address?.hostAddress
                    if (senderIp != null) {
                        val json = JSONObject(String(resp.data, 0, resp.length, Charsets.UTF_8))
                        if (json.optString("service") == "lumalink") {
                            return@withContext DiscoveredPc(json.optString("name", "ПК"), senderIp)
                        }
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
