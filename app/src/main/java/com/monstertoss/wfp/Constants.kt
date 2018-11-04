package com.monstertoss.wfp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SIZEOF_LONG = 8

const val BASE64_FLAGS = Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE

const val ACTIVITY_SIGN_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NO_ANIMATION

const val ANDROID_KEYSTORE = "AndroidKeyStore"
const val KEY_EXCHANGE_ALGORITHM = KeyProperties.KEY_ALGORITHM_HMAC_SHA256
const val SIGNATURE_KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_EC
const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
const val SIGNATURE_PURPOSES = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
const val SIGNATURE_DIGEST = KeyProperties.DIGEST_SHA256

const val DEVICE_DATABASE = "devices"

fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

fun Long.bytes(): ByteArray = ByteBuffer.allocate(SIZEOF_LONG).order(ByteOrder.BIG_ENDIAN).putLong(this).array()