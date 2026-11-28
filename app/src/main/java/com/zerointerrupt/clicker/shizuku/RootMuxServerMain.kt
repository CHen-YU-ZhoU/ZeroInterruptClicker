package com.zerointerrupt.clicker.shizuku

import android.content.AttributionSource
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Entry point executed inside a UID 0 (root) `app_process` daemon.
 *
 * Instantiates [TouchMuxUserService] (identical AIDL & native engine to Shizuku mode),
 * hands its live Binder back to the app via [RootBinderProvider.call], links to the app's
 * death sentinel so hardware EVIOCGRAB is always released if the app exits, and loops forever.
 */
@Keep
object RootMuxServerMain {
    private const val TAG = "RootMuxServerMain"

    @JvmStatic
    @Keep
    fun main(args: Array<String>) {
        exemptHiddenApis()

        if (args.size < 3) {
            Log.e(TAG, "Expected 3 args: <packageName> <authority> <sessionToken>")
            exitProcess(2)
        }

        val authority = args[1].trim()
        val sessionToken = args[2].trim()
        val targetUserId = args.getOrNull(3)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (authority.isEmpty() || sessionToken.isEmpty()) {
            Log.e(TAG, "Empty authority or sessionToken")
            exitProcess(2)
        }

        try {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prepareMainLooper notice: ${t.message}")
        }

        val service = TouchMuxUserService()
        val extras = Bundle().apply {
            putBinder(RootBinderProvider.EXTRA_SERVICE_BINDER, service)
        }

        var reply: Bundle? = null
        for (attempt in 1..4) {
            reply = deliverBinderToApp(authority, sessionToken, extras, targetUserId)
            if (reply != null) break
            try {
                Thread.sleep(150L * attempt)
            } catch (_: InterruptedException) {
                break
            }
        }

        val clientToken = reply?.getBinder(RootBinderProvider.EXTRA_CLIENT_TOKEN)
        if (clientToken == null || !clientToken.pingBinder()) {
            Log.e(TAG, "Handshake failed: no valid client death token received; exiting.")
            try {
                service.destroy()
            } catch (_: Throwable) {
                exitProcess(1)
            }
            return
        }

        try {
            clientToken.linkToDeath({
                Log.i(TAG, "Client process died; releasing hardware engine and exiting.")
                try {
                    service.destroy()
                } catch (_: Throwable) {
                    exitProcess(0)
                }
            }, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to linkToDeath on client token: ${t.message}")
            try {
                service.destroy()
            } catch (_: Throwable) {
                exitProcess(1)
            }
            return
        }

        Log.i(TAG, "Root TouchMux daemon active (uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()})")
        try {
            Looper.loop()
        } catch (t: Throwable) {
            Log.e(TAG, "Looper exited unexpectedly: ${t.message}")
            try {
                service.destroy()
            } catch (_: Throwable) {
                exitProcess(1)
            }
        }
    }

    private fun deliverBinderToApp(
        authority: String,
        sessionToken: String,
        extras: Bundle,
        targetUserId: Int
    ): Bundle? {
        // Path A: For primary user 0, let framework ContentResolver pick exact IContentProvider.call signature
        if (targetUserId == 0) {
            try {
                val res = deliverViaSystemContext(authority, sessionToken, extras)
                if (res != null) return res
            } catch (t: Throwable) {
                Log.w(TAG, "SystemContext handshake fallback: ${t.message}")
            }
        }

        // Path B: Direct IActivityManager -> IContentProvider reflection fallback across API 26..35 & multi-user
        return try {
            deliverViaActivityManager(authority, sessionToken, extras, targetUserId)
        } catch (t: Throwable) {
            Log.e(TAG, "IActivityManager handshake failed: ${t.message}", t)
            null
        }
    }

    private fun deliverViaSystemContext(
        authority: String,
        sessionToken: String,
        extras: Bundle
    ): Bundle? {
        val atClass = Class.forName("android.app.ActivityThread")
        val currentAt = try {
            atClass.getDeclaredMethod("currentActivityThread").invoke(null)
        } catch (_: Throwable) {
            null
        } ?: atClass.getDeclaredMethod("systemMain").invoke(null)

        val sysCtx = atClass.getDeclaredMethod("getSystemContext").invoke(currentAt) as Context
        val uri = Uri.parse("content://$authority")
        return sysCtx.contentResolver.call(
            uri,
            RootBinderProvider.METHOD_REGISTER_ROOT_SERVICE,
            sessionToken,
            extras
        )
    }

    private fun deliverViaActivityManager(
        authority: String,
        sessionToken: String,
        extras: Bundle,
        targetUserId: Int
    ): Bundle? {
        val am = obtainActivityManager() ?: return null
        val token = Binder()
        val holder = acquireExternalProviderHolder(am, authority, targetUserId, token) ?: return null
        try {
            val providerField = holder.javaClass.getField("provider")
            val provider = providerField.get(holder) ?: return null
            return invokeIContentProviderCall(
                provider = provider,
                authority = authority,
                method = RootBinderProvider.METHOD_REGISTER_ROOT_SERVICE,
                arg = sessionToken,
                extras = extras
            )
        } finally {
            releaseExternalProvider(am, authority, token)
        }
    }

    private fun obtainActivityManager(): Any? {
        return try {
            val amClass = Class.forName("android.app.ActivityManager")
            amClass.getDeclaredMethod("getService").invoke(null)
        } catch (_: Throwable) {
            val amnClass = Class.forName("android.app.ActivityManagerNative")
            amnClass.getDeclaredMethod("getDefault").invoke(null)
        }
    }

    private fun acquireExternalProviderHolder(
        am: Any,
        authority: String,
        userId: Int,
        token: IBinder
    ): Any? {
        val methods = am.javaClass.methods.filter { it.name == "getContentProviderExternal" }
        // API 29+: getContentProviderExternal(String name, int userId, IBinder token, String tag)
        methods.firstOrNull { it.parameterTypes.size == 4 }?.let { m ->
            return m.invoke(am, authority, userId, token, TAG)
        }
        // API 26-28: getContentProviderExternal(String name, int userId, IBinder token)
        methods.firstOrNull { it.parameterTypes.size == 3 }?.let { m ->
            return m.invoke(am, authority, userId, token)
        }
        return null
    }

    private fun releaseExternalProvider(am: Any, authority: String, token: IBinder) {
        try {
            val m = am.javaClass.methods.firstOrNull {
                it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2
            }
            m?.invoke(am, authority, token)
        } catch (_: Throwable) {
        }
    }

    private fun invokeIContentProviderCall(
        provider: Any,
        authority: String,
        method: String,
        arg: String,
        extras: Bundle
    ): Bundle? {
        val callMethods = provider.javaClass.methods.filter { it.name == "call" }

        // Priority 1 (API 31+): call(AttributionSource, String authority, String method, String arg, Bundle extras)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callMethods.firstOrNull { m ->
                val types = m.parameterTypes
                types.size == 5 &&
                    types[0].name == "android.content.AttributionSource" &&
                    types[4] == Bundle::class.java
            }?.let { m ->
                val attr = AttributionSource.Builder(0)
                    .setPackageName("android")
                    .build()
                return m.invoke(provider, attr, authority, method, arg, extras) as? Bundle
            }
        }

        // Priority 2 (API 30): call(String callingPkg, String attributionTag, String authority, String method, String arg, Bundle extras)
        callMethods.firstOrNull { m ->
            val types = m.parameterTypes
            types.size == 6 &&
                types[0] == String::class.java &&
                types[5] == Bundle::class.java
        }?.let { m ->
            return m.invoke(provider, "android", null, authority, method, arg, extras) as? Bundle
        }

        // Priority 3 (API 29): call(String callingPkg, String authority, String method, String arg, Bundle extras)
        callMethods.firstOrNull { m ->
            val types = m.parameterTypes
            types.size == 5 &&
                types[0] == String::class.java &&
                types[4] == Bundle::class.java
        }?.let { m ->
            return m.invoke(provider, "android", authority, method, arg, extras) as? Bundle
        }

        // Priority 4 (API 26-28): call(String callingPkg, String method, String arg, Bundle extras)
        callMethods.firstOrNull { m ->
            val types = m.parameterTypes
            types.size == 4 &&
                types[0] == String::class.java &&
                types[3] == Bundle::class.java
        }?.let { m ->
            return m.invoke(provider, "android", method, arg, extras) as? Bundle
        }

        return null
    }

    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            setExemptions.invoke(runtime, arrayOf("L"))
        } catch (t: Throwable) {
            Log.w(TAG, "Hidden API exemption notice: ${t.message}")
        }
    }
}
