package com.jaber.screenshare

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocketFactory

/** یک فریم WebSocket (بدون ماسک، سمت سرور) را در یک آرایه می‌سازد تا با یک write ارسال شود. */
fun wsFrame(opcode: Int, vararg parts: ByteArray): ByteArray {
    var len = 0
    for (p in parts) len += p.size
    val hdr = when {
        len < 126 -> 2
        len <= 65535 -> 4
        else -> 10
    }
    val out = ByteArray(hdr + len)
    out[0] = (0x80 or opcode).toByte()
    when (hdr) {
        2 -> out[1] = len.toByte()
        4 -> {
            out[1] = 126
            out[2] = (len shr 8).toByte()
            out[3] = len.toByte()
        }
        else -> {
            out[1] = 127
            for (i in 0 until 8) out[2 + i] = ((len.toLong() shr (56 - 8 * i)) and 0xFF).toByte()
        }
    }
    var pos = hdr
    for (p in parts) {
        System.arraycopy(p, 0, out, pos, p.size)
        pos += p.size
    }
    return out
}

/**
 * سرور کوچک HTTPS + WebSocket.
 * خودمان نوشتیم تا TCP_NODELAY و ارسال یک‌تکه‌ی هر فریم را کنترل کنیم (کمترین تأخیر).
 */
class WebServer(
    private val factory: SSLServerSocketFactory,
    private val page: () -> ByteArray,
    private val listener: Listener
) {
    interface Listener {
        fun onClientOpen(c: Client)
        fun onClientClose(c: Client)
        fun onText(c: Client, text: String)
        fun onNeedKey()
    }

    class Client(val sock: Socket, private val out: OutputStream) {
        val queue = LinkedBlockingQueue<ByteArray>(30)
        private val writeLock = Any()

        @Volatile
        var waitKey = true

        @Volatile
        var open = true

        fun offer(m: ByteArray): Boolean = queue.offer(m)

        fun reset() {
            queue.clear()
            waitKey = true
        }

        fun writeNow(m: ByteArray) {
            synchronized(writeLock) {
                out.write(m)
                out.flush()
            }
        }

        fun close() {
            open = false
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    val clients = CopyOnWriteArrayList<Client>()
    private val lock = Any()

    @Volatile
    private var config: ByteArray? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** سرور را روی اولین پورت آزاد از ۸۴۴۳ به بعد بالا می‌آورد و پورت را برمی‌گرداند. */
    fun start(): Int {
        var last: Exception? = null
        for (port in 8443..8453) {
            try {
                val ss = factory.createServerSocket(port, 50)
                serverSocket = ss
                val t = Thread({ acceptLoop(ss) }, "ws-accept")
                t.isDaemon = true
                t.start()
                return port
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("no free port")
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        for (c in clients) c.close()
        clients.clear()
    }

    /** پیکربندی جدید ویدیو؛ برای همهٔ کلاینت‌ها فرستاده و برای کلاینت‌های بعدی نگه داشته می‌شود. */
    fun setConfig(msg: ByteArray) {
        synchronized(lock) {
            config = msg
            for (c in clients) c.offer(msg)
        }
    }

    /** هنگام تغییر رزولوشن/کیفیت: صف‌ها خالی و منتظر کی‌فریم جدید. */
    fun resetStream() {
        synchronized(lock) {
            config = null
            for (c in clients) c.reset()
        }
    }

    fun broadcastFrame(msg: ByteArray, key: Boolean) {
        var needKey = false
        for (c in clients) {
            if (key) c.waitKey = false
            if (c.waitKey) continue
            if (!c.offer(msg)) {
                // کلاینت کند است: به‌جای انباشتن تأخیر، صف را دور می‌ریزیم و کی‌فریم تازه می‌خواهیم
                c.reset()
                needKey = true
            }
        }
        if (needKey) listener.onNeedKey()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            val t = Thread({ handle(s) }, "ws-conn")
            t.isDaemon = true
            t.start()
        }
    }

    private fun handle(sock: Socket) {
        var isWs = false
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 10000
            try {
                sock.sendBufferSize = 256 * 1024
            } catch (_: Exception) {
            }
            val din = DataInputStream(BufferedInputStream(sock.getInputStream(), 8192))
            val out = sock.getOutputStream()

            val head = readHead(din) ?: return
            val lines = head.split("\r\n")
            val req = lines[0].split(" ")
            if (req.size < 2) return
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val ix = lines[i].indexOf(':')
                if (ix > 0) {
                    headers[lines[i].substring(0, ix).trim().lowercase()] = lines[i].substring(ix + 1).trim()
                }
            }
            val path = req[1].substringBefore('?')

            if (path == "/ws" && headers["upgrade"].equals("websocket", ignoreCase = true)) {
                // فقط صفحه‌ای که خود گوشی داده اجازهٔ اتصال دارد (جلوگیری از سایت‌های دیگر)
                val origin = headers["origin"]
                val host = headers["host"]
                if (origin != null && host != null && origin != "https://$host") {
                    respond(out, "403 Forbidden", "text/plain", "forbidden".toByteArray())
                    return
                }
                val key = headers["sec-websocket-key"] ?: return
                val accept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)),
                    Base64.NO_WRAP
                )
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
                )
                out.flush()
                isWs = true
                serveWs(sock, din, out)
            } else if (path == "/" || path == "/index.html") {
                respond(out, "200 OK", "text/html; charset=utf-8", page())
            } else {
                respond(out, "404 Not Found", "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            if (!isWs) {
                try {
                    sock.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun serveWs(sock: Socket, din: DataInputStream, out: OutputStream) {
        sock.soTimeout = 15000 // کلاینت هر ۳ ثانیه پینگ می‌فرستد
        val c = Client(sock, out)

        val sender = Thread({
            try {
                while (c.open) {
                    val m = c.queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    c.writeNow(m)
                }
            } catch (_: Exception) {
            } finally {
                c.close()
            }
        }, "ws-send")
        sender.isDaemon = true
        sender.start()

        synchronized(lock) {
            config?.let { c.offer(it) }
            clients.add(c)
        }
        listener.onClientOpen(c)

        try {
            while (c.open) {
                val b0 = din.readUnsignedByte()
                val b1 = din.readUnsignedByte()
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong()
                else if (len == 127L) len = din.readLong()
                if (len < 0 || len > 1_000_000) break
                val mask = ByteArray(4)
                if (masked) din.readFully(mask)
                val payload = ByteArray(len.toInt())
                din.readFully(payload)
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                    }
                }
                when (opcode) {
                    1 -> listener.onText(c, String(payload, Charsets.UTF_8))
                    8 -> break
                    9 -> c.writeNow(wsFrame(10, payload))
                }
            }
        } catch (_: Exception) {
        } finally {
            c.close()
            clients.remove(c)
            listener.onClientClose(c)
        }
    }

    private fun readHead(inp: InputStream): String? {
        val sb = ByteArrayOutputStream()
        var state = 0
        while (sb.size() < 16384) {
            val b = inp.read()
            if (b < 0) return null
            sb.write(b)
            state = when {
                b == 13 && (state == 0 || state == 2) -> state + 1
                b == 10 && (state == 1 || state == 3) -> state + 1
                b == 13 -> 1
                else -> 0
            }
            if (state == 4) return String(sb.toByteArray(), Charsets.ISO_8859_1)
        }
        return null
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        val h = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }
}
