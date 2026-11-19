package com.jaber.screenshare

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object Util {

    /** اندازهٔ واقعی صفحه (با نوار وضعیت و ناوبری) به پیکسل. */
    @Suppress("DEPRECATION")
    fun realSize(ctx: Context): Point {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        d.getRealSize(p)
        return p
    }

    /** آدرس‌های IPv4 شبکهٔ محلی؛ وای‌فای و هات‌اسپات اول می‌آیند. */
    fun localAddresses(): List<String> {
        val res = ArrayList<Pair<Int, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in Collections.list(ifaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                // رابط‌های VPN/تونل آدرس قابل‌اتصال از کامپیوتر نیستند
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("tap") ||
                    name.startsWith("wg") || name.startsWith("ipsec") || name.startsWith("dummy")
                ) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        val score = if (name.startsWith("wlan") || name.startsWith("ap") ||
                            name.startsWith("swlan") || name.startsWith("eth")
                        ) 0 else 1
                        res.add(score to ip)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return res.sortedBy { it.first }.map { it.second }.distinct()
    }
}
