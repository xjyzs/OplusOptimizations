package com.xjyzs.oplusoptimizations

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.view.InputDevice
import android.view.KeyEvent
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
    companion object {
        private var mWinPressed = false
        private var mShortcutTriggered = false
    }

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

    private fun injectCustomKey(
        keyCode: Int, action: Int, scanCode: Int, classLoader: ClassLoader?
    ) {
        try {
            val inputManagerClass =
                XposedHelpers.findClass("android.hardware.input.InputManager", classLoader)
            val inputManager = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance")

            val now = android.os.SystemClock.uptimeMillis()
            // 构造新的虚拟按键：
            // 参数依次为: downTime, eventTime, action, code, repeat, metaState, deviceId, scanCode, flags, source
            val event = KeyEvent(
                now, now, action, keyCode, 0, 0, -1, scanCode, 0, InputDevice.SOURCE_KEYBOARD
            )

            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            XposedHelpers.callMethod(inputManager, "injectInputEvent", event, 0)
        } catch (e: Throwable) {
            XposedBridge.log("按键注入失败: ${e.message}")
        }
    }

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
                    targetClass, "getTaskRealSize", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 只有目标应用未指定方向才进行干预
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
                    "com.android.server.wm.FlexibleWindowManagerService",
                    lpparam.classLoader,
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

                            // 注入系统精确的长宽比给渲染层，强制允许缩放
                            val exactRatio =
                                Math.max(sw, sh).toFloat() / Math.min(sw, sh) + 0.03.toFloat()
                            bundle.putFloat("androidx.flexible.CompatRatio", exactRatio)
                            bundle.putInt("androidx.flexible.ResizeMode", 1)
                        }
                    })
            } catch (e: Throwable) {
                XposedBridge.log("Hook WMS Error: $e")
            }
            try {
                val policyClass =
                    "com.android.server.oplus.input.exinputservice.globalgesture.OplusGestureAnimationManagerPolicy"

                // 强制手势动画方向与物理设备方向一致
                val syncOrientationHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 只有应用是允许自由旋转时，才修改 isHorizontalApp
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
                    policyClass,
                    lpparam.classLoader,
                    "updateFourDragAnimSurface",
                    android.view.SurfaceControl.Transaction::class.java,
                    Float::class.javaPrimitiveType,
                    String::class.java,
                    syncOrientationHook
                )

                XposedHelpers.findAndHookMethod(
                    policyClass, lpparam.classLoader, "getZoomThresholdScale", syncOrientationHook
                )
                XposedHelpers.findAndHookMethod(
                    policyClass,
                    lpparam.classLoader,
                    "updateRealCropRect",
                    Rect::class.java,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            return null
                        }
                    })

            } catch (e: Throwable) {
                XposedBridge.log("Hook Gesture Animation Error: $e")
            }

            // 防止 Super 键呼出小布助手
            val hookedClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager", lpparam.classLoader
            )

            var assistHandlerHooked = false
            XposedHelpers.findAndHookMethod(
                hookedClass, "interceptKeyBeforeQueueing",
                KeyEvent::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        var phoneWindowManagerHandler: Handler? = null
                        var msgLaunchAssist = -1
                        if (!assistHandlerHooked) {
                            try {
                                phoneWindowManagerHandler = XposedHelpers.getObjectField(
                                    param.thisObject,
                                    "mHandler"
                                ) as Handler
                                msgLaunchAssist = XposedHelpers.getStaticIntField(
                                    hookedClass,
                                    "MSG_LAUNCH_ASSIST"
                                )
                                XposedHelpers.findAndHookMethod(
                                    Message::class.java, "sendToTarget",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val msg = param.thisObject as Message
                                            if (msg.what != msgLaunchAssist) return
                                            if (msg.target !== phoneWindowManagerHandler) return
                                            param.result = null
                                        }
                                    }
                                )
                                assistHandlerHooked = true
                            } catch (t: Throwable) {
                                XposedBridge.log("采集 mHandler/MSG_LAUNCH_ASSIST 失败: $t")
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val keyEvent = param.args[0] as KeyEvent
                        if (keyEvent.keyCode != 219) return
                        param.result = (param.result as Int) or 1  // 强制保留 FLAG_PASS_TO_USER
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
                    File::class.java, "delete", object : XC_MethodHook() {
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
                    })
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
                    })
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        } else if (lpparam.packageName == "com.oplus.screenshot") {
            // 提高长截图滚动长度限制
            try {
                val configClass = XposedHelpers.findClass("t9.b", lpparam.classLoader)
                // 最大可捕捉页数
                XposedBridge.hookAllMethods(configClass, "x", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = 63 //TODO
                    }
                })
                // 最大可捕捉像素
                XposedBridge.hookAllMethods(configClass, "y", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = Integer.MAX_VALUE
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("Failed to hook config limits: " + t.message)
            }

            // 破解长截图拼接最后一步的Fallback限制
            try {
                val stitchLimitUtils = XposedHelpers.findClass("eb.h", lpparam.classLoader)

                XposedBridge.hookAllMethods(stitchLimitUtils, "f", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
                XposedBridge.hookAllMethods(stitchLimitUtils, "g", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
                XposedBridge.hookAllMethods(stitchLimitUtils, "h", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = -1
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("Failed to hook fallback stitch limits: " + t.message)
            }

            // 自由裁剪模式的最小面积限制
            try {
                val lassoPathParser = XposedHelpers.findClass("d7.b", lpparam.classLoader)
                // 方法 C 负责校验套索面积，返回 true 则允许通过
                XposedBridge.hookAllMethods(lassoPathParser, "C", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("Failed to hook Lasso limit: " + t.message)
            }

            // 破解常规矩形裁剪模式的最小面积限制
            try {
                val abstractEditorInfo = XposedHelpers.findClass(
                    "f7.c", lpparam.classLoader
                )
                // 方法 J 设置最小矩形限制大小
                XposedBridge.hookAllMethods(abstractEditorInfo, "J", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("Bypassed Rect crop minimum area!")
                        param.args[0] = 42.0f
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("Failed to hook Rect limit: " + t.message)
            }
        }
    }
}
