package com.monstertoss.wfp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView

class QrCodeScanner : Activity(), ZXingScannerView.ResultHandler {
    private val REQUEST_CAMERA_PERMISSION = 1
    private var permissionCallback: () -> Unit = {}

    private var scannerView: ZXingScannerView? = null

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        ensurePermissions {
            scannerView = ZXingScannerView(this).apply {
                setFormats(listOf(BarcodeFormat.QR_CODE))
                setContentView(this)
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        ensurePermissions {
            scannerView?.apply {
                setResultHandler(this@QrCodeScanner)
                startCamera()
            }
        }
    }

    public override fun onPause() {
        scannerView?.stopCamera()
        super.onPause()
    }

    override fun handleResult(rawResult: Result) {
        scannerView?.stopCamera()
        val data = Intent().apply {
            putExtra("data", rawResult.text)
            putExtra("bytes", rawResult.rawBytes)
            putExtra("type", rawResult.barcodeFormat)
        }
        BroadcastHelper.getInstance(this).response(intent, data)
        finish()
    }

    private fun ensurePermissions(callback: () -> Unit) {
        permissionCallback = callback

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            return permissionCallback()

        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    permissionCallback()
                else
                    ensurePermissions(permissionCallback)
            }
        }
    }
}