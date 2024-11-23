package com.example.autocaptcha

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.ViewfinderView


class CaptureQRCodeActivity : AppCompatActivity() {
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var customViewfinderView: CustomViewfinderView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_qr_code)

        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        customViewfinderView = findViewById(R.id.custom_viewfinder)

        (barcodeView.viewFinder as ViewfinderView).setLaserVisibility(false) // 移除默认的红色激光线
        customViewfinderView.visibility = View.VISIBLE

        val settings = barcodeView.cameraSettings
        settings.isContinuousFocusEnabled = true // 启用连续对焦
        barcodeView.cameraSettings = settings

        val hints = mutableMapOf<DecodeHintType, Any?>()
        hints[DecodeHintType.NEED_RESULT_POINT_CALLBACK] = null // 不需要可能的结果点回调
        val formats = listOf(BarcodeFormat.QR_CODE)
        val decoderFactory = DefaultDecoderFactory(formats, hints, null, 0)
        barcodeView.decoderFactory = decoderFactory

        // 动态获取扫码框区域, 用于等下绘制扫描线
        barcodeView.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val viewFinder = barcodeView.viewFinder as ViewfinderView
                val field = ViewfinderView::class.java.getDeclaredField("framingRect")
                field.isAccessible = true // 通过反射读取权限为 protected 的 framingRect 属性
                val framingRect = field.get(viewFinder) as? Rect
                if (framingRect != null) {
                    customViewfinderView.setFramingRect(framingRect)
                } else {
                    Log.d("CaptureQRCodeActivity", "framingRect: 获取扫码框区域失败")
                }
            } catch (e: Exception) {
                Log.e("CaptureQRCodeActivity", "framingRect: 获取扫码框区域失败", e)
            }
        }

        // 设置连续扫码
        barcodeView.decodeContinuous(
            object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    handleResult(result)
                }

                override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
                    // 未来可能的扫码点
                }
            })

        // 处理返回操作
        onBackPressedDispatcher.addCallback(this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val intent = Intent()
                    setResult(Activity.RESULT_CANCELED, intent)
                    finish()
                }
            })
    }

    // 启动相机预览
    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    // 暂停相机预览
    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    // 处理扫码结果
    private fun handleResult(result: BarcodeResult) {
        val intent = Intent()
        intent.putExtra("SCAN_RESULT", result.text)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}

class CustomViewfinderView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint()
    private val scannerLineColor = Color.parseColor("#0F826E") // 激光线颜色
    private val scannerLineThickness = 10 // 激光线厚度
    private var scannerLineY = 0 // 当前激光线的 Y 位置
    private val scannerSpeed = 5 // 激光线移动速度
    private val handler = Handler(Looper.getMainLooper())
    private var framingRect: Rect? = null // 扫描框区域
    private var gradient: LinearGradient? = null // 渐变效果

    var onLaserLineCrossed: (() -> Unit)? = null // 激光线经过中间时触发回调

    init {
        paint.color = scannerLineColor
        paint.strokeWidth = scannerLineThickness.toFloat()
        startScannerAnimation()
    }

    private fun startScannerAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                invalidate() // 触发重新绘制
                framingRect?.let {
                    scannerLineY += scannerSpeed
                    if (scannerLineY > it.bottom) {
                        scannerLineY = it.top // 激光线回到顶部
                    }

                    // 检测激光线经过中间位置
                    val middle = (it.top + it.bottom) / 2
                    if (scannerLineY in (middle - 10)..(middle + 10)) {
                        onLaserLineCrossed?.invoke() // 触发对焦回调
                    }
                }
                handler.postDelayed(this, 16) // 每 16ms 更新一次
            }
        })
    }

    // 设置扫描框区域
    fun setFramingRect(rect: Rect) {
        framingRect = rect
        scannerLineY = rect.top // 初始化激光线位置

        // 激光线渐变效果
        gradient = LinearGradient(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.top.toFloat(),
            intArrayOf(Color.TRANSPARENT, scannerLineColor, Color.TRANSPARENT),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = framingRect ?: return

        paint.shader = gradient

        // 拖尾效果
        frame.width() / 2
        canvas.drawRect(
            frame.left.toFloat(),
            scannerLineY.toFloat() - scannerLineThickness / 2,
            frame.right.toFloat(),
            scannerLineY.toFloat() + scannerLineThickness / 2,
            paint
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}




