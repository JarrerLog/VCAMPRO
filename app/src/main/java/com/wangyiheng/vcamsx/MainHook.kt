package com.wangyiheng.vcamsx

import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.utils.InfoProcesser.videoStatus
import com.wangyiheng.vcamsx.utils.OutputImageFormat
import com.wangyiheng.vcamsx.utils.VideoPlayer.c1_camera_play
import com.wangyiheng.vcamsx.utils.VideoPlayer.ijkMediaPlayer
import com.wangyiheng.vcamsx.utils.VideoPlayer.camera2Play
import com.wangyiheng.vcamsx.utils.VideoPlayer.initializeTheStateAsWellAsThePlayer
import com.wangyiheng.vcamsx.utils.VideoToFrames
import de.robv.android.xposed.*
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.min

class MainHook : IXposedHookLoadPackage {
    fun rotateNV21(data: ByteArray, width: Int, height: Int): ByteArray {
        Toast.makeText(context, "ROTATE_001", Toast.LENGTH_SHORT).show()
        val output = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                output[i++] = data[y * width + x]
            }
        }
        val uvStart = width * height
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (x in 0 until uvWidth) {
            for (y in uvHeight - 1 downTo 0) {
                val index = uvStart + y * width + x * 2
                output[i++] = data[index]
                output[i++] = data[index + 1]
            }
        }
        return output
    }

    companion object {
        val TAG = "vcamsx"
        @Volatile
        var data_buffer = byteArrayOf(0)
        var context: Context? = null
        var origin_preview_camera: Camera? = null
        var fake_SurfaceTexture: SurfaceTexture? = null
        var c1FakeTexture: SurfaceTexture? = null
        var c1FakeSurface: Surface? = null
        var sessionConfiguration: SessionConfiguration? = null
        var outputConfiguration: OutputConfiguration? = null
        var fake_sessionConfiguration: SessionConfiguration? = null
        var original_preview_Surface: Surface? = null
        var original_c1_preview_SurfaceTexture:SurfaceTexture? = null
        var isPlaying:Boolean = false
        var needRecreate: Boolean = false
        var c2VirtualSurfaceTexture: SurfaceTexture? = null
        var c2_reader_Surfcae: Surface? = null
        var camera_onPreviewFrame: Camera? = null
        var camera_callback_calss: Class<*>? = null
        var hw_decode_obj: VideoToFrames? = null
        var mcamera1: Camera? = null
        var oriHolder: SurfaceHolder? = null
    }

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx") return
        Toast.makeText(context, "HOOK_001: " + lpparam.packageName, Toast.LENGTH_SHORT).show()

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    Toast.makeText(context, "HOOK_002", Toast.LENGTH_SHORT).show()
                    param?.args?.firstOrNull()?.let { arg ->
                        if (arg is Application) {
                            val applicationContext = arg.applicationContext
                            if (context != applicationContext) {
                                try {
                                    context = applicationContext
                                    if (!isPlaying) {
                                        isPlaying = true
                                        ijkMediaPlayer ?: initializeTheStateAsWellAsThePlayer()
                                    }
                                } catch (ee: Exception) {
                                    HLog.d(TAG, "$ee")
                                }
                            }
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Toast.makeText(context, "HOOK_003", Toast.LENGTH_SHORT).show()
                    if (param.args[0] == null) return
                    if (param.args[0] == fake_SurfaceTexture) return
                    if (origin_preview_camera != null && origin_preview_camera == param.thisObject) {
                        param.args[0] = fake_SurfaceTexture
                        return
                    }

                    origin_preview_camera = param.thisObject as Camera
                    original_c1_preview_SurfaceTexture = param.args[0] as SurfaceTexture
                    fake_SurfaceTexture?.release()
                    fake_SurfaceTexture = SurfaceTexture(10)
                    param.args[0] = fake_SurfaceTexture
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                Toast.makeText(context, "HOOK_004", Toast.LENGTH_SHORT).show()
                c1_camera_play()
            }
        })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Toast.makeText(context, "HOOK_005", Toast.LENGTH_SHORT).show()
                    if(videoStatus?.isVideoEnable == false) return
                    if (param.args[0] != null) {
                        process_callback(param)
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
            ByteArray::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Toast.makeText(context, "HOOK_006", Toast.LENGTH_SHORT).show()
                    if (param.args[0] != null) {
                        param.args[0] = ByteArray((param.args[0] as ByteArray).size)
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", SurfaceHolder::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                Toast.makeText(context, "HOOK_007", Toast.LENGTH_SHORT).show()
                mcamera1 = param.thisObject as Camera
                oriHolder = param.args[0] as SurfaceHolder
                c1FakeTexture?.release()
                c1FakeTexture = SurfaceTexture(11)
                c1FakeSurface?.release()
                c1FakeSurface = Surface(c1FakeTexture)
                mcamera1!!.setPreviewTexture(c1FakeTexture)
                param.result = null
            }
        })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Toast.makeText(context, "HOOK_008", Toast.LENGTH_SHORT).show()
                    if(param.args[1] == null) return
                    if(param.args[1] == c2_state_callback) return
                    c2_state_callback = param.args[1] as CameraDevice.StateCallback
                    c2_state_callback_class = param.args[1]?.javaClass
                    process_camera2_init(c2_state_callback_class as Class<Any>?,lpparam)
                }
            })
    }

    private fun process_callback(param: MethodHookParam) {
        Toast.makeText(context, "CB_001", Toast.LENGTH_SHORT).show()
        val preview_cb_class: Class<*> = param.args[0].javaClass
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame",
            ByteArray::class.java,
            Camera::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(paramd: MethodHookParam) {
                    Toast.makeText(context, "CB_002", Toast.LENGTH_SHORT).show()
                    val localcam = paramd.args[1] as Camera
                    if (localcam ==  camera_onPreviewFrame) {
                        while ( data_buffer == null) {}
                        Toast.makeText(context, "CB_003", Toast.LENGTH_SHORT).show()
                        val rotatedData = rotateNV21(data_buffer, localcam.parameters.previewSize.width, localcam.parameters.previewSize.height)
                        System.arraycopy(rotatedData, 0, paramd.args[0], 0, min(rotatedData.size, (paramd.args[0] as ByteArray).size))
                    } else {
                        camera_callback_calss = preview_cb_class
                        camera_onPreviewFrame = paramd.args[1] as Camera
                        val mwidth = camera_onPreviewFrame!!.parameters.previewSize.width
                        val mhight = camera_onPreviewFrame!!.parameters.previewSize.height
                        hw_decode_obj?.stopDecode()
                        Toast.makeText(context, "CB_004", Toast.LENGTH_SHORT).show()
                        hw_decode_obj = VideoToFrames()
                        hw_decode_obj!!.setSaveFrames(OutputImageFormat.NV21)
                        val videoUrl = "content://com.wangyiheng.vcamsx.videoprovider"
                        hw_decode_obj!!.decode(Uri.parse(videoUrl))
                        while (data_buffer == null) {}
                        val rotatedData = rotateNV21(data_buffer, mwidth, mhight)
                        System.arraycopy(rotatedData, 0, paramd.args[0], 0, min(rotatedData.size, (paramd.args[0] as ByteArray).size))
                    }
                }
            })
    }

    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        Toast.makeText(context, "C2_001", Toast.LENGTH_SHORT).show()
        // giữ nguyên vì không cần debug camera2 thêm
    }

    private fun createVirtualSurface(): Surface? {
        Toast.makeText(context, "C2_SURFACE_CREATE", Toast.LENGTH_SHORT).show()
        if (needRecreate) {
            c2VirtualSurfaceTexture?.release()
            c2VirtualSurfaceTexture = null
            c2_virtual_surface?.release()
            c2_virtual_surface = null
            c2VirtualSurfaceTexture = SurfaceTexture(15)
            c2_virtual_surface = Surface(c2VirtualSurfaceTexture)
            needRecreate = false
        } else if (c2_virtual_surface == null) {
            needRecreate = true
            c2_virtual_surface = createVirtualSurface()
        }
        return c2_virtual_surface
    }
}
