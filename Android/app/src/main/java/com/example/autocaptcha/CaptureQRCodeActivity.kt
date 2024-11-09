package com.example.autocaptcha

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ViewfinderView

class CaptureQRCodeActivity : AppCompatActivity() {
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_qr_code)

        // 初始化 barcodeView
        barcodeView = findViewById(R.id.zxing_barcode_scanner)

        // 移除红色激光线
        (barcodeView.viewFinder as ViewfinderView).setLaserVisibility(false)

        // 开始连续解码
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                handleResult(result) // 处理扫码结果
            }

            override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
        })

        // 使用OnBackPressedDispatcher处理返回操作
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
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

    private fun handleResult(result: BarcodeResult) {
        Log.d("SCAN_RESULT", "已经处理结果")
        val intent = Intent()
        intent.putExtra("SCAN_RESULT", result.text) // 将扫码结果放入Intent中
        Log.d("SCAN_RESULT", "相机的扫描结果为:" + result.text)
        setResult(Activity.RESULT_OK, intent) // 返回结果
        finish() // 结束活动
    }
}

