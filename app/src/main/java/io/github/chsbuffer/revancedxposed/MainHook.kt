package io.github.chsbuffer.revancedxposed

import android.app.Application
import app.revanced.extension.shared.Utils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    lateinit var startupParam: StartupParam
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    var targetPackageName: String? = null
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    fun shouldHook(packageName: String): Boolean {
        if (!hooksByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        inContext(lpparam) { app ->
            this.app = app
            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed module does not work with patched app")
                return@inContext
            }

            // Existing Spotify hooks from the module
            hooksByPackage[lpparam.packageName]?.invoke()?.Hook()

            /* 
               PREEMPTIVE STRING HOOK
               Instead of replacing text after it's built, we intercept the string search 
               entirely. This is the closest LSPosed can get to "deleting" the string 
               from classes4.dex without causing background lag.
            */
            XposedHelpers.findAndHookMethod(
                "java.lang.String",
                lpparam.classLoader,
                "contains",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val query = param.args[0]?.toString() ?: return
                        // Catching both "events" and "public/events" under one umbrella
                        if (query.contains("gabo-receiver-service/v3/")) {
                            param.result = false // Tells Spotify "No, this string does not exist"
                        }
                    }
                }
            )

            // Optional: Block the construction of the URL builder specifically
            XposedHelpers.findAndHookMethod(
                "java.lang.StringBuilder",
                lpparam.classLoader,
                "append",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args[0] as? String ?: return
                        if (text.contains("gabo-receiver-service/v3/")) {
                            param.args[0] = "" // Nullifies the append before it happens
                        }
                    }
                }
            )
        }
    }

    private fun isReVancedPatched(lpparam: LoadPackageParam): Boolean {
        return runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.utils.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.utils.Utils")
        }.isSuccess
    }

    override fun initZygote(startupParam: StartupParam) {
        this.startupParam = startupParam
        XposedInit = startupParam
    }
}

fun inContext(lpparam: LoadPackageParam, f: (Application) -> Unit) {
    val appClazz = XposedHelpers.findClass(lpparam.appInfo.className, lpparam.classLoader)
    XposedBridge.hookMethod(appClazz.getMethod("onCreate"), object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val app = param.thisObject as Application
            Utils.setContext(app)
            f(app)
        }
    })
}
