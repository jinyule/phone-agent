package dev.phoneagent.privileged

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * [binder] as another process reaches it. `queryLocalInterface` is hidden, so `asInterface` builds
 * the generated Proxy and every call is a real transact. An exception `Parcel.writeException`
 * cannot carry (it rethrows those) ends the transaction the way the native Binder does:
 * `UNKNOWN_TRANSACTION` with an empty reply, which the Proxy reads as "no exception" and a zero
 * result. Such exceptions are collected in [uncaught]. A device reads zeros past the end of a
 * parcel and Robolectric's refuses to, so the empty reply is filled with the zeros a device reads.
 */
internal class OverTheWire(
    private val binder: IBinder,
) : IBinder by binder {
    val uncaught = mutableListOf<RuntimeException>()

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    @Suppress("TooGenericExceptionCaught") // Anything else Parcel could not write ends here, as in JavaBBinder.
    override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean =
        try {
            binder.transact(code, data, reply, flags)
        } catch (e: RuntimeException) {
            uncaught += e
            reply?.apply {
                setDataSize(0)
                repeat(ZEROS_A_DEVICE_READS) { writeInt(0) }
                setDataPosition(0)
            }
            false
        }

    companion object {
        /** Enough for the exception header and any result the Proxy reads. */
        private const val ZEROS_A_DEVICE_READS = 4
    }
}
