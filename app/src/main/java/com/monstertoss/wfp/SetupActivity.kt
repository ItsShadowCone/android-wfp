package com.monstertoss.wfp

import android.app.KeyguardManager
import androidx.room.Room
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AppCompatActivity
import android.util.Base64
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DividerItemDecoration
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SetupActivity : AppCompatActivity() {
    private lateinit var keystore: KeyStore
    private lateinit var devicesView: RecyclerView
    private lateinit var devicesAdapter: DevicesAdapter
    private lateinit var database: DeviceDatabase
    private lateinit var broadcastHelper: BroadcastHelper

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_setup)

        val keyguard: KeyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        println("Device Secure: ${keyguard.isDeviceSecure}")

        println("Fingerprint Supported: ${packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)}")

        database = Room.databaseBuilder(this, DeviceDatabase::class.java, DEVICE_DATABASE).run {
            allowMainThreadQueries()
            build()
        }

        keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        devicesAdapter = DevicesAdapter(this, database)

        devicesView = findViewById<RecyclerView>(R.id.connected_devices).apply {
            layoutManager = LinearLayoutManager(this@SetupActivity)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            adapter = devicesAdapter
        }

        broadcastHelper = BroadcastHelper.getInstance(this)
        val devices = database.devices.load()
        for (alias in keystore.aliases()) {
            var found = false
            for (device in devices) {
                if(device.ownKey == alias)
                    found = true
            }
            if(!found) {
                println("Found lone key $alias. Removing...")
                //keystore.deleteEntry(alias)
            }
        }
        for (device in devices) {
            if(keystore.getEntry(device.ownKey, null) as? KeyStore.PrivateKeyEntry == null) {
                println("Found lone device ${device.id}. Removing...")
                //database.devices.delete(device)
            }
        }

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swiperefresh)
        swipeRefresh.setOnRefreshListener {
            devicesAdapter.notifyDataSetChanged()
            swipeRefresh.isRefreshing = false
        }

        val addDeviceButton = findViewById<FloatingActionButton>(R.id.addDevice)
        addDeviceButton.setOnClickListener {
            val intent = Intent(this, QrCodeScanner::class.java)
            broadcastHelper.request(intent) it@{
                val response = it.getStringExtra("data") ?: return@it
                val (device: Device, info: Info) = parseDeviceJson(response) ?: return@it

                devicesAdapter.add(device)
                startService(Intent(this, NotificationService::class.java).apply {
                    putExtra("action", NotificationService.ACTIONS.START_DEVICE)
                    putExtra("id", device.id)
                })

                sendInfo(device.id, info)

                println("Public Key: ${info.publicKey}")
                println("Signature: ${info.signature}")
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceRunning(this, NotificationService::class.java))
            startService(Intent(this, NotificationService::class.java).putExtra("action", NotificationService.ACTIONS.START_ALL))
    }

    fun sendInfo(id: String, info: Info) {
        val intent = Intent(this, NotificationService::class.java).apply {
            putExtra("action", NotificationService.ACTIONS.SEND_INFO)
            putExtra("id", id)
            putExtra("info", info)
        }
        broadcastHelper.request(intent) it@{
            if (!it.getBooleanExtra("ok", false)) {
                val message = it.getStringExtra("message") ?: return@it
                println(message)
            }
        }
        startService(intent)
    }

    fun openDetails(id: String) {
        Intent(this, DeviceActivity::class.java).also {
            it.putExtra("id", id)
            startActivity(it)
        }
    }

    fun sign(id: String, challenge: ByteArray) {
        println("Signing $id")
        val intent = Intent(this, BiometricScanner::class.java).apply {
            putExtra("id", id)
            putExtra("challenge", challenge)
            addFlags(ACTIVITY_SIGN_FLAGS)
        }
        broadcastHelper.request(intent) it@{
            val response = it.getByteArrayExtra("response") ?: return@it
            println(Base64.encodeToString(response, BASE64_FLAGS))
        }
        startActivity(intent)
    }

    private fun parseDeviceJson(data: String): DeviceAndInfo? {
        try {
            val json = JSONObject(data)
            if (!json.has("id") || !json.has("name") || !json.has("publicKey") || !json.has("wrappingKey"))
                return null

            val id: String = json["id"] as? String ?: return null
            val name: String = json["name"] as? String ?: return null
            val publicKey = json["publicKey"] as? String ?: return null
            val wrappingKey = Base64.decode(json["wrappingKey"] as? String
                    ?: return null, BASE64_FLAGS)

            val dev = database.devices.load(id)
            if(dev != null) {
                Toast.makeText(this, getString(R.string.device_already_paired), Toast.LENGTH_SHORT).show()
                return null
            }

            // Make sure UUIDs in the keystore are always unique
            var ownKey: String
            do {
                ownKey = UUID.randomUUID().toString()
            } while (keystore.containsAlias(ownKey))

            val device = Device(id, name, ownKey, publicKey)

            // Generate own keypair and save it under device.ownKey
            val params = KeyGenParameterSpec.Builder(device.ownKey, SIGNATURE_PURPOSES).run {
                setDigests(SIGNATURE_DIGEST)
                setUserAuthenticationRequired(true)
                build()
            }
            val keypair = KeyPairGenerator.getInstance(SIGNATURE_KEY_ALGORITHM, ANDROID_KEYSTORE).run {
                initialize(params)
                generateKeyPair()
            }

            // Sign our public key with the wrapping key so the other party can verify it's genuine
            val signature = Mac.getInstance(KEY_EXCHANGE_ALGORITHM).run {
                init(SecretKeySpec(wrappingKey, KEY_EXCHANGE_ALGORITHM))
                doFinal(keypair.public.encoded)
            }

            return DeviceAndInfo(device, Info(Base64.encodeToString(keypair.public.encoded, BASE64_FLAGS), Base64.encodeToString(signature, BASE64_FLAGS)))
        } catch (e: JSONException) {
            return null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        database.close()
    }
}
