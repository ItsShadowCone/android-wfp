package com.monstertoss.wfp

import android.app.Activity
import androidx.room.Room
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import moe.feng.support.biometricprompt.BiometricPromptCompat
import java.security.KeyStore
import java.security.Signature
import android.view.Display


class BiometricScanner : Activity() {
    private val flags: Int = {
        Int
        when {
            Build.VERSION.SDK_INT < 26 -> WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            Build.VERSION.SDK_INT == 26 -> WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            else -> 0
        }
    }() or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER

    private lateinit var id: String
    private lateinit var challenge: ByteArray
    private lateinit var device: Device
    private lateinit var entry: KeyStore.PrivateKeyEntry

    private lateinit var database: DeviceDatabase
    private lateinit var keystore: KeyStore
    private lateinit var broadcastHelper: BroadcastHelper

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        window.addFlags(flags)

        id = intent.getStringExtra("id") ?: return close(null, "Could not get id")
        challenge = intent.getByteArrayExtra("challenge") ?: return close(null, "Could not get challenge")

        database = Room.databaseBuilder(this, DeviceDatabase::class.java, DEVICE_DATABASE).run {
            allowMainThreadQueries()
            build()
        }

        broadcastHelper = BroadcastHelper.getInstance(this)

        device = database.devices.load(id) ?: return close(null, "Could not load device")

        keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        entry = keystore.getEntry(device.ownKey, null) as? KeyStore.PrivateKeyEntry ?: return close(null, "Could not get key: ${device.ownKey}")
    }

    override fun onResume() {
        super.onResume()

        if(isScreenOn()) {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(entry.privateKey)
            }

            var canceled = false
            var signal = CancellationSignal()
            broadcastHelper.onCancel(intent) {
                canceled = true
                signal.cancel()
            }
            val biometricPrompt: BiometricPromptCompat = BiometricPromptCompat.Builder(this).run {
                setTitle("Allow access to your computer")
                setSubtitle("Device ${device.name}")
                setNegativeButton("Cancel") { _: DialogInterface?, _: Int ->
                    canceled = true
                    signal.cancel()
                }
                build()
            }
            val crypto: BiometricPromptCompat.ICryptoObject = BiometricPromptCompat.DefaultCryptoObject(signature)

            biometricPrompt.authenticate(crypto, signal, object : BiometricPromptCompat.IAuthenticationCallback {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    when {
                        canceled -> close(null, "Authentication error: canceled")
                        signal.isCanceled -> {
                            println("onAuthenticationError $errorCode: $errString")
                            signal = CancellationSignal()
                            biometricPrompt.authenticate(crypto, signal, this)
                        }
                        else -> {
                            signal.cancel()
                            close(null, "Authentication error: not canceled and signal did not receive cancel (i.e. Screen turned off). Got code $errorCode: $errString")
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    onError("Authentication Failed")
                }

                override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence?) {
                    onError(helpString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPromptCompat.IAuthenticationResult) {
                    close(signature.run {
                        update(challenge)
                        sign()
                    })
                }

                fun onError(error: String) {
                    println(error)
                }
            })
        }
    }

    fun close(data: ByteArray?) {
        window.clearFlags(flags)
        val response = Intent()
        response.putExtra("response", data)

        broadcastHelper.response(intent, response)
        finish()
    }

    fun close(data: ByteArray?, message: String) {
        println("Closed with message: $message")
        close(data)
    }

    fun isScreenOn(): Boolean {
        return (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays[0].state != Display.STATE_OFF
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        database.close()
    }
}