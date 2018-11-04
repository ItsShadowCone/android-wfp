package com.monstertoss.wfp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcelable
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue
import java.util.*
import kotlin.collections.HashMap

class BroadcastHelper {

    private lateinit var localBroadcastManager: LocalBroadcastManager

    companion object {
        @Volatile
        private var INSTANCE: BroadcastHelper? = null

        fun getInstance(context: Context): BroadcastHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BroadcastHelper().apply {
                    localBroadcastManager = LocalBroadcastManager.getInstance(context.applicationContext)
                    INSTANCE = this
                }
            }
        }
    }

    @Parcelize
    data class RequestObject(val response: String = UUID.randomUUID().toString(),
                             val cancel: String = UUID.randomUUID().toString()) : Parcelable

    fun request(intent: Intent, callback: (response: Intent) -> Unit) {
        val request = RequestObject()
        intent.putExtra("request", request)

        localBroadcastManager.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                localBroadcastManager.unregisterReceiver(this)
                val dataIntent = intent.getParcelableExtra<Intent>("intent") ?: return
                callback(dataIntent)
            }
        }, IntentFilter(request.response))
    }

    fun onCancel(startIntent: Intent, callback: (response: Intent) -> Unit) {
        val request = startIntent.getParcelableExtra<RequestObject>("request") ?: return

        localBroadcastManager.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                localBroadcastManager.unregisterReceiver(this)
                val dataIntent = intent.getParcelableExtra<Intent>("intent") ?: return
                callback(dataIntent)
            }
        }, IntentFilter(request.cancel))
    }

    fun response(startIntent: Intent, dataIntent: Intent) {
        val request = startIntent.getParcelableExtra<RequestObject>("request") ?: return

        localBroadcastManager.sendBroadcast(Intent(request.response).putExtra("intent", dataIntent))
    }

    fun cancel(startIntent: Intent, dataIntent: Intent) {
        val request = startIntent.getParcelableExtra<RequestObject>("request") ?: return

        localBroadcastManager.sendBroadcast(Intent(request.cancel).putExtra("intent", dataIntent))
    }
}