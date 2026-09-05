package com.phonelock.app.util

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 월간 통계 리포트(이미지, 82차 §9) — 새 Compose 버전(graphicsLayer 캡처)이 필요 없는 방식으로 화면을
 * 그대로 캡처한다. 이 프로젝트 Compose BOM(2024.06.00)은 `rememberGraphicsLayer`(1.7.0+)가 없어, 대신
 * OS 표준 [PixelCopy]로 현재 창을 통째로 비트맵화한다 — Compose 버전과 무관하게 항상 동작.
 */
object ScreenCapture {
    /** 현재 액티비티 창을 캡처해 파일로 저장한다. 실패하면 null. */
    suspend fun captureWindowToFile(activity: Activity, file: File): File? {
        val bitmap = captureWindowBitmap(activity) ?: return null
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun captureWindowBitmap(activity: Activity): Bitmap? = suspendCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            cont.resume(null)
            return@suspendCoroutine
        }
        val window = activity.window
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) {
            cont.resume(null)
            return@suspendCoroutine
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) cont.resume(bitmap) else cont.resume(null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            cont.resume(null)
        }
    }
}
