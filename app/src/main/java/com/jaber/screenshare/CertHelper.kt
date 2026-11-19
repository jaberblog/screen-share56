package com.jaber.screenshare

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * مرورگر فقط در بستر امن (HTTPS) اجازهٔ استفاده از WebCodecs را می‌دهد؛
 * پس یک گواهی خودامضا روی خود گوشی ساخته و ذخیره می‌شود (فقط بار اول).
 */
object CertHelper {
    private const val FILE = "tls.p12"
    private const val ALIAS = "screenshare"
    private val PASS = "screenshare".toCharArray()

    fun serverSocketFactory(ctx: Context): SSLServerSocketFactory {
        val ks = loadOrCreate(ctx)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS)
        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, null, null)
        return sc.serverSocketFactory
    }

    private fun loadOrCreate(ctx: Context): KeyStore {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(f).use { ks.load(it, PASS) }
                if (ks.containsAlias(ALIAS)) return ks
            } catch (_: Exception) {
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val name = X500Name("CN=ScreenShare")
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - day),
            Date(now + 3650L * day),
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, PASS, arrayOf<Certificate>(cert))
        try {
            FileOutputStream(f).use { ks.store(it, PASS) }
        } catch (_: Exception) {
        }
        return ks
    }
}
