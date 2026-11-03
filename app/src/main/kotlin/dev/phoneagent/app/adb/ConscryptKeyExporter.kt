package dev.phoneagent.app.adb

import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.RequiresApi
import dev.phoneagent.core.adb.TlsKeyExporter
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * The TLS 1.3 keying material exporter of the platform TLS stack (Conscrypt), through
 * `SSLSockets.exportKeyingMaterial`. That API arrived in Android 12 (API 31) and Android 11 has no
 * public equivalent, so the built-in adb client needs Android 12 (BuiltinAdb requires API 31).
 * Exports without a context, as adbd does; in TLS 1.3 that equals an empty context (RFC 8446 §7.5).
 */
@RequiresApi(Build.VERSION_CODES.S)
internal object ConscryptKeyExporter : TlsKeyExporter {
    override fun export(
        socket: SSLSocket,
        label: String,
        length: Int,
    ): ByteArray {
        if (!SSLSockets.isSupportedSocket(
                socket,
            )
        ) {
            throw SSLException("not a platform TLS socket: ${socket.javaClass.name}")
        }
        return SSLSockets.exportKeyingMaterial(socket, label, null, length)
            ?: throw SSLException("the platform TLS stack exported no keying material")
    }
}
