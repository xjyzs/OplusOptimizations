package com.xjyzs.oplusoptimizations

import android.app.ActivityOptions
import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File


class MainHook : IXposedHookLoadPackage {
    // 判断屏幕方向常量是否为"未指定/跟随类"
    private fun isFromUserGesture(): Boolean {
        val stackTrace = Thread.currentThread().stackTrace
        for (element in stackTrace) {
            val name = element.className
            if (name.contains("Gesture") || name.contains("Pointer") || name.contains("Drag")) {
                return true
            }
        }
        return false
    }

    // Android 源码定义的所有“未指定/可跟随系统”的屏幕方向常量
    private fun isUnspecifiedOrientation(orientation: Int): Boolean {
        return orientation in intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, // -1
            ActivityInfo.SCREEN_ORIENTATION_USER,        // 2
            ActivityInfo.SCREEN_ORIENTATION_BEHIND,      // 3
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,      // 4
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, // 10
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER    // 13
        )
    }

    private val isInsideClipboardListener = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean {
            return false
        }
    }

    @Volatile
    var isLastAppOrientationUnspecified: Boolean = true

    @Volatile
    private var sLastOrientation: Int = -1

    @Volatile
    private var sDeviceLandscape: Boolean = false

    @Volatile
    private var sFullScreenRatio: Float = 0.6923077f
    private val MAGIC_OFFSET = 0.015123f
    private val originalRatioLocal = ThreadLocal<Float>()
    // 记录最近一次计算出的设备方向，用于指导后续的小窗数据生成
    private var lastForceLandscape: Boolean = false
    private var lastForcePortrait: Boolean = false

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "android") {
            // 小窗比例
//            try {
//                val targetClass = XposedHelpers.findClass(
//                    "com.android.server.wm.OplusFlexibleTaskLayoutPolicy", lpparam.classLoader
//                )
//
//                XposedBridge.hookAllMethods(
//                    targetClass, "getTaskRealSize",
//                    object : XC_MethodHook() {
//                        override fun beforeHookedMethod(param: MethodHookParam) {
//                            var isLandscapeBase = param.args[0] as Boolean
//                            var incomingRatio = param.args[1] as Float
//
//                            // 1. 判断比例是否为 16:9
//                            var isTargetRatio = (incomingRatio == 1.7777778f)
//
//                            // 不修改横屏应用
//                            var isFromGesture = false
//                            val stackTrace = Thread.currentThread().stackTrace
//                            for (element in stackTrace) {
//                                val className = element.className
//                                // 四指捏合手势
//                                if (className.contains("GestureController") || className.contains("Gesture")) {
//                                    isFromGesture = true
//                                    break
//                                }
//                            }
//                            if (!isFromGesture) {
//                                if (!isLandscapeBase && isTargetRatio) {
//                                    param.args[0] = true
//                                    param.args[1] = 0.6923077f
//                                }
//                            }
//                        }
//                    })
//
//            } catch (e: Throwable) {
//                XposedBridge.log(e)
//            }
            try {
                val targetClass = XposedHelpers.findClass(
                    "com.android.server.wm.OplusFlexibleTaskLayoutPolicy", lpparam.classLoader
                )

                XposedBridge.hookAllMethods(
                    targetClass, "getTaskRealSize",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 只有目标应用（未指定方向/跟随系统）才进行干预
                            if (!isUnspecifiedOrientation(sLastOrientation)) return

                            val policy = param.thisObject
                            // 获取当前屏幕真实的宽高
                            val sw = XposedHelpers.getIntField(policy, "mScreenWidth")
                            val sh = XposedHelpers.getIntField(policy, "mScreenHeight")

                            if (sw > 0 && sh > 0) {
                                val isDeviceLandscape = sw > sh
                                // 计算设备物理比例（长边/短边，系统会自动处理 >1 还是 <1）
                                val deviceRatio =
                                    Math.max(sw, sh).toFloat() / Math.min(sw, sh) + 0.03.toFloat()

                                // 【核心】强制让系统认为该应用的横竖屏状态和比例与设备物理状态完全一致！
                                param.args[0] = isDeviceLandscape
                                param.args[1] = deviceRatio
                            }
                        }
                    })
            } catch (e: Throwable) {
                XposedBridge.log("Hook getTaskRealSize Error: $e")
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.FlexibleWindowManagerService", lpparam.classLoader,
                    "calculateFlexibleWindowBounds",
                    android.content.Intent::class.java,
                    Int::class.java,
                    "com.android.server.wm.DisplayContent",
                    ActivityInfo::class.java,
                    Int::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activityInfo = param.args[3] as? ActivityInfo ?: return
                            sLastOrientation = activityInfo.screenOrientation
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val bundle = param.result as? Bundle ?: return
                            if (!isUnspecifiedOrientation(sLastOrientation)) return

                            val displayContent = param.args[2] ?: return
                            val displayInfo =
                                XposedHelpers.callMethod(displayContent, "getDisplayInfo") ?: return
                            val sw = XposedHelpers.getIntField(displayInfo, "logicalWidth")
                            val sh = XposedHelpers.getIntField(displayInfo, "logicalHeight")

                            // 核心：注入系统精确的长宽比给渲染层，强制允许缩放
                            val exactRatio =
                                Math.max(sw, sh).toFloat() / Math.min(sw, sh) + 0.03.toFloat()
                            bundle.putFloat("androidx.flexible.CompatRatio", exactRatio)
                            bundle.putInt("androidx.flexible.ResizeMode", 1)

                            // 去除掉你之前的 bundle.getParcelable<Rect> 和翻转逻辑！
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("Hook WMS Error: $e")
            }
            try {
                val policyClass =
                    "com.android.server.oplus.input.exinputservice.globalgesture.OplusGestureAnimationManagerPolicy"

                // 强制手势动画方向与物理设备方向一致
                val syncOrientationHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 关键：只有应用是允许自由旋转时，才修改 isHorizontalApp
                        if (isUnspecifiedOrientation(sLastOrientation)) {
                            val policy = param.thisObject
                            val sw = XposedHelpers.getIntField(policy, "mScreenWidth")
                            val sh = XposedHelpers.getIntField(policy, "mScreenHeight")
                            XposedHelpers.setBooleanField(policy, "isHorizontalApp", sw > sh)
                        }
                    }
                }

                // 纠正动画渲染过程与最终缩放判定
                XposedHelpers.findAndHookMethod(
                    policyClass, lpparam.classLoader,
                    "updateFourDragAnimSurface",
                    android.view.SurfaceControl.Transaction::class.java,
                    Float::class.javaPrimitiveType,
                    String::class.java,
                    syncOrientationHook
                )

                XposedHelpers.findAndHookMethod(
                    policyClass, lpparam.classLoader,
                    "getZoomThresholdScale",
                    syncOrientationHook
                )

                // 【最关键的一步】：彻底干掉覆盖尺寸的方法！
                // 拦截 updateRealCropRect，什么都不做，保留动画最后一帧的完美尺寸，无缝移交给 WMS
                XposedHelpers.findAndHookMethod(
                    policyClass, lpparam.classLoader,
                    "updateRealCropRect",
                    Rect::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            // Return null 直接阻断执行，防止它把 432dp 的完美尺寸强行缩小为 360dp
                            return null
                        }
                    })

            } catch (e: Throwable) {
                XposedBridge.log("Hook Gesture Animation Error: $e")
            }
        } else if (lpparam.packageName == "com.android.systemui") {
            // 开启原生剪贴板编辑器
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.clipboardoverlay.ClipboardListener",
                lpparam.classLoader,
                "onPrimaryClipChanged",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        isInsideClipboardListener.set(true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        isInsideClipboardListener.set(false)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass(
                    "com.oplusos.systemui.common.feature.FeatureOption",
                    lpparam.classLoader
                ),
                "isExpRegion",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideClipboardListener.get() == true) {
                            param.result = true
                        }
                    }
                }
            )
        } else if (lpparam.packageName == "com.android.launcher") {
            // 小窗比例
            // TODO:


            // 小窗启动应用
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.taskbar.TaskbarActivityContext",
                    lpparam.classLoader,
                    "startItemInfoActivity",
                    "com.android.launcher3.model.data.ItemInfo",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            try {
                                val context = param.thisObject as Context
                                val itemInfo = param.args[0] ?: return null

                                val originalIntent =
                                    XposedHelpers.callMethod(itemInfo, "getIntent") as? Intent
                                if (originalIntent == null) {
                                    XposedBridge.log("TaskbarHook: Intent is null")
                                    return null
                                }

                                val newIntent = Intent(originalIntent)
                                newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                val options = ActivityOptions.makeBasic()
                                try {
                                    val method = ActivityOptions::class.java.getMethod(
                                        "setLaunchWindowingMode", Int::class.javaPrimitiveType
                                    )
                                    method.invoke(options, 100)
                                } catch (e: Exception) {
                                    XposedBridge.log("TaskbarHook: Failed to set windowing mode: $e")
                                }

                                val bundle = options.toBundle()
                                context.startActivity(newIntent, bundle)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                            return null
                        }
                    })
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        } else if (lpparam.packageName == "com.android.packageinstaller") {
            // 安装完成后不删除 apk
            try {
                XposedHelpers.findAndHookMethod(
                    File::class.java,
                    "delete",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val file = param.thisObject as File
                            val path = file.absolutePath
                            if (path.endsWith(".apk", ignoreCase = true)) {
                                val stackTrace = Exception().stackTrace
                                val isFromInstaller = stackTrace.any {
                                    it.className.contains("InstallAppProgress")
                                }
                                if (isFromInstaller) {
                                    param.result = false
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        } else if (lpparam.packageName == "com.oplus.games") {
            // 屏蔽游戏启动时的弹窗
            try {
                val wmImplClass =
                    XposedHelpers.findClass("android.view.WindowManagerImpl", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    wmImplClass,
                    "addView",
                    View::class.java,
                    ViewGroup.LayoutParams::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val layoutParams = param.args[1] as? WindowManager.LayoutParams
                            val windowTitle = layoutParams?.title?.toString() ?: return
                            if (windowTitle.contains("GameSpaceFloatWindow")) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }
}
