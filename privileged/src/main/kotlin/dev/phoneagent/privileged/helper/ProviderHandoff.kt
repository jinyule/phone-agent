/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/HelperProcess.kt, `deliver`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: extracted the provider call into its own class,
 * added the API 30 `IContentProvider.call` signature, specific reflection errors with causes.
 */
package dev.phoneagent.privileged.helper

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Calls the app's handoff provider the way `content call` does: `IActivityManager.getContentProviderExternal`
 * (allowed for shell), then `IContentProvider.call`. Getting the provider starts the app process if
 * it is not running. The provider must belong to [packageName] / [appUid]; the helper never hands
 * its Binder to anyone else.
 */
internal class ProviderHandoff(
    private val authority: String,
    private val packageName: String,
    private val appUid: Int,
) {
    sealed interface Result {
        /** The provider answered (possibly null). */
        data class Reply(
            val bundle: Bundle?,
        ) : Result

        /** The authority belongs to another package or uid. */
        data class WrongOwner(
            val description: String,
        ) : Result

        /** The provider could not be reached; [cause] says why. */
        data class Unreachable(
            val cause: String,
        ) : Result
    }

    /** Sends `call(method, null, extras)` to the provider. Blocks; call off the Binder threads. */
    fun call(
        method: String,
        extras: Bundle,
    ): Result =
        try {
            acquireAndCall(method, extras)
        } catch (e: Unreachable) {
            Result.Unreachable(e.message.orEmpty())
        }

    /** Thrown inside this class when a step fails; [call] turns it into [Result.Unreachable]. */
    private class Unreachable(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun acquireAndCall(
        method: String,
        extras: Bundle,
    ): Result {
        val activityManager =
            reflect("IActivityManager") {
                Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            }
                ?: throw Unreachable("IActivityManager is null")
        val token = Binder()
        val holder =
            reflect("getContentProviderExternal($authority)") {
                activityManager.javaClass
                    .getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        IBinder::class.java,
                        String::class.java,
                    ).invoke(activityManager, authority, USER_PRIMARY, token, TAG)
            } ?: throw Unreachable("no provider for $authority")
        try {
            return callHolder(holder, method, extras)
        } finally {
            release(activityManager, token)
        }
    }

    private fun callHolder(
        holder: Any,
        method: String,
        extras: Bundle,
    ): Result {
        val info =
            reflect("ContentProviderHolder.info") { holder.javaClass.getField("info").get(holder) as? ProviderInfo }
        if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
            return Result.WrongOwner(
                "$authority belongs to ${info?.packageName}/${info?.applicationInfo?.uid}, not $packageName/$appUid",
            )
        }
        val provider =
            reflect("ContentProviderHolder.provider") { holder.javaClass.getField("provider").get(holder) }
                ?: throw Unreachable("$authority has no provider object")
        return Result.Reply(reflect("IContentProvider.call") { invokeCall(provider, method, extras) })
    }

    /** `IContentProvider.call`: API 31+ takes an AttributionSource, API 30 a package and an attribution tag. */
    private fun invokeCall(
        provider: Any,
        method: String,
        extras: Bundle,
    ): Bundle? {
        val call = findCall(provider.javaClass)
        val reply =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val caller =
                    AttributionSource
                        .Builder(
                            android.os.Process.myUid(),
                        ).setPackageName(ShellContext.SHELL_PACKAGE)
                        .build()
                call.invoke(provider, caller, authority, method, null, extras)
            } else {
                call.invoke(provider, ShellContext.SHELL_PACKAGE, null, authority, method, null, extras)
            }
        return reply as Bundle?
    }

    /** Runs one reflective step; a [ReflectiveOperationException] becomes [Unreachable] naming [what] and the cause. */
    private inline fun <T> reflect(
        what: String,
        step: () -> T,
    ): T =
        try {
            step()
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "$what failed", e)
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            throw Unreachable("$what: ${cause.javaClass.simpleName}: ${cause.message}", e)
        }

    private fun findCall(type: Class<*>): Method {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) CALL_ARGS_31 else CALL_ARGS_30
        return type.methods.firstOrNull { method ->
            method.name == "call" &&
                method.parameterTypes.size == wanted &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        method.parameterTypes[0] == AttributionSource::class.java
                )
        } ?: throw NoSuchMethodException("IContentProvider.call with $wanted parameters")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun release(
        activityManager: Any,
        token: IBinder,
    ) {
        try {
            activityManager.javaClass
                .getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(activityManager, authority, token, USER_PRIMARY)
        } catch (e: ReflectiveOperationException) {
            // The reference dies with the token anyway; only log it.
            Log.w(TAG, "removeContentProviderExternalAsUser($authority) failed", e)
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val USER_PRIMARY = 0
        const val CALL_ARGS_31 = 5
        const val CALL_ARGS_30 = 6
    }
}
