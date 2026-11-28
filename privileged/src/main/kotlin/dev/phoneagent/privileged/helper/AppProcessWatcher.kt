/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/HelperProcess.kt, `watchUid`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: own class, added getUidImportance, failures are reported
 * to the caller instead of being swallowed.
 */
package dev.phoneagent.privileged.helper

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Watches the app's uid through `ActivityManager.addOnUidImportanceListener` (needs
 * PACKAGE_USAGE_STATS, which shell holds). [onAppStarted] runs on [handler] whenever a process of
 * [appUid] appears or changes importance; the caller ignores the calls it does not need.
 */
internal class AppProcessWatcher(
    private val context: Context,
    private val handler: Handler,
    private val appUid: Int,
    private val onAppStarted: () -> Unit,
) {
    private var manager: ActivityManager? = null

    /** Registers the listener. Returns false, after logging why, when the framework refuses it. */
    fun start(): Boolean {
        val am =
            try {
                register()
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "cannot watch uid $appUid; the helper will not notice the app restarting", e)
                null
            }
        manager = am
        return am != null
    }

    /** Whether a process of [appUid] exists now. False when it cannot tell (logged). */
    @SuppressLint("DiscouragedPrivateApi")
    fun isRunning(): Boolean {
        val am = manager ?: return false
        val importance =
            try {
                ActivityManager::class.java
                    .getMethod(
                        "getUidImportance",
                        Int::class.javaPrimitiveType,
                    ).invoke(am, appUid) as Int
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "getUidImportance($appUid) failed", (e as? InvocationTargetException)?.targetException ?: e)
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
            }
        return importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
    }

    /** A manager on the shell context (registration checks the caller's package), with the listener added. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun register(): ActivityManager {
        val am =
            ActivityManager::class.java
                .getDeclaredConstructor(Context::class.java, Handler::class.java)
                .apply { isAccessible = true }
                .newInstance(context, handler)
        val listenerType = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
        ActivityManager::class.java
            .getMethod("addOnUidImportanceListener", listenerType, Int::class.javaPrimitiveType)
            .invoke(am, listener(listenerType), ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        return am
    }

    private fun listener(type: Class<*>): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "onUidImportance" -> {
                    val uid = args[0] as Int
                    val importance = args[1] as Int
                    if (uid == appUid && importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
                        handler.post(onAppStarted)
                    }
                    null
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "equals" -> {
                    proxy === args?.getOrNull(0)
                }

                "toString" -> {
                    "PhoneAgentHelperUidListener"
                }

                else -> {
                    null
                }
            }
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
