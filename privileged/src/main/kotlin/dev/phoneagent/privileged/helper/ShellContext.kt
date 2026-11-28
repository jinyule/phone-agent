/*
 * Portions adapted from scrcpy (https://github.com/Genymobile/scrcpy):
 *   Copyright (C) 2018 Genymobile
 *   Copyright (C) 2018-2024 Romain Vimont
 *
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellContext.kt):
 *   Copyright 2026 wangzan101
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by PhoneAgent contributors: optional steps catch and log specific reflection errors
 * instead of swallowing everything; no getAttributionSource override (unused so far).
 */
package dev.phoneagent.privileged.helper

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log

/**
 * System context of the `app_process` helper. System services check that the calling package
 * belongs to the calling uid, so it reports `com.android.shell` (uid 2000), not the app's package.
 */
internal class ShellContext private constructor(
    base: Context,
) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE: String = "com.android.shell"
        private const val TAG = "PhoneAgentHelper"

        /**
         * Builds the context, creating an `ActivityThread` the way `app_process` lacks one.
         * Throws [ReflectiveOperationException] when the framework does not have the expected
         * internals; the helper cannot run without them.
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun create(): Context {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread =
                activityThread.getMethod("currentActivityThread").invoke(null) ?: newActivityThread(activityThread)
            val system = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val context = ShellContext(system)
            val initialApplication =
                activityThread
                    .getDeclaredField(
                        "mInitialApplication",
                    ).apply { isAccessible = true }
            if (initialApplication.get(thread) == null) {
                try {
                    initialApplication.set(thread, Instrumentation.newApplication(Application::class.java, context))
                } catch (e: ReflectiveOperationException) {
                    Log.w(TAG, "no initial Application; services needing one may fail", e)
                }
            }
            return context
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
        private fun newActivityThread(activityThread: Class<*>): Any {
            val thread = activityThread.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            activityThread.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            activityThread.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val controllerClass = Class.forName("android.app.ConfigurationController")
                    val internal = Class.forName("android.app.ActivityThreadInternal")
                    val controller =
                        controllerClass
                            .getDeclaredConstructor(
                                internal,
                            ).apply { isAccessible = true }
                            .newInstance(thread)
                    activityThread
                        .getDeclaredField(
                            "mConfigurationController",
                        ).apply { isAccessible = true }
                        .set(thread, controller)
                } catch (e: ReflectiveOperationException) {
                    Log.w(TAG, "no ConfigurationController; configuration callbacks may fail", e)
                }
            }
            try {
                val bindData = Class.forName("android.app.ActivityThread\$AppBindData")
                val data = bindData.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                bindData
                    .getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                    .set(data, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                activityThread.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "no bound application info; package-name checks may fail", e)
            }
            return thread
        }
    }
}
