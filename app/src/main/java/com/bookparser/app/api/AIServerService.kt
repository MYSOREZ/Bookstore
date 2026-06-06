package com.bookparser.app.api

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bookparser.app.R

/**
 * Foreground Service that keeps the AI Screen Observation API alive even when
 * the app is in the background or the screen is off. Shows a persistent
 * notification with the IP:port so the user always knows where to connect.
 */
class AIServerService : Service() {

    companion object {
        const val CHANNEL_ID = "ai_server_channel"
        const val NOTIF_ID   = 9001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip   = getLocalIp()
        val port = intent?.getIntExtra("port", 8765) ?: 8765
        startForeground(NOTIF_ID, buildNotification(ip, port))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(ip: String, port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(ip, port))
    }

    private fun getLocalIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo.ipAddress
            "%d.%d.%d.%d".format(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff)
        } catch (e: Exception) {
            // Fallback: scan network interfaces
            try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (iface in ifaces) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            } catch (_: Exception) {}
            "localhost"
        }
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Screen API активен")
            .setContentText("http://$ip:$port")
            .setSubText("Подключите AI-агента к этому адресу")
            .setSmallIcon(R.drawable.ic_ai_server)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Screen API",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Статус сервера для AI-агента"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
