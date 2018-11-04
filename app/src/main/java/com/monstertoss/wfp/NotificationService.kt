package com.monstertoss.wfp

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.util.Base64
import androidx.room.Room
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.*
import kotlin.collections.HashMap

class NotificationService : Service() {

    class BootCompleteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && !isServiceRunning(context, NotificationService::class.java))
                context.startService(Intent(context, NotificationService::class.java).putExtra("action", ACTIONS.START_ALL))
        }
    }

    class ACTIONS {
        companion object {
            const val SEND_INFO = "SEND_INFO"
            const val START_DEVICE = "START_DEVICE"
            const val START_ALL = "START_ALL"
        }
    }

    private lateinit var keystore: KeyStore
    private lateinit var firebaseDatabase: FirebaseDatabase
    private lateinit var database: DeviceDatabase
    private lateinit var broadcastHelper: BroadcastHelper

    // No binding
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        firebaseDatabase = FirebaseDatabase.getInstance()
        database = Room.databaseBuilder(this, DeviceDatabase::class.java, DEVICE_DATABASE).run {
            allowMainThreadQueries()
            build()
        }
        broadcastHelper = BroadcastHelper.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val err: (message: String) -> Int = {
                broadcastHelper.response(intent, Intent().putExtra("ok", false).putExtra("message", it))
                START_STICKY
            }

            val action = intent.getStringExtra("action") ?: return START_STICKY
            when (action) {
                ACTIONS.SEND_INFO -> {
                    val id = intent.getStringExtra("id")
                            ?: return err("Called SEND_INFO with invalid id parameter")
                    val info = intent.getParcelableExtra<Info>("info")
                            ?: return err("Called SEND_INFO with invalid value parameter")

                    firebaseDatabase.getReference("i").child(id).setValue(info)
                    broadcastHelper.response(intent, Intent().putExtra("ok", true))
                }

                ACTIONS.START_DEVICE -> {
                    val id = intent.getStringExtra("id")
                            ?: return err("Called START_DEVICE with invalid id parameter")
                    val device = database.devices.load(id) ?: return err("Device not found")
                    subscribeToDevice(device)
                    broadcastHelper.response(intent, Intent().putExtra("ok", true))
                }

                ACTIONS.START_ALL -> {
                    startAll()
                    broadcastHelper.response(intent, Intent().putExtra("ok", true))
                }
            }
        } else
            startAll()

        return START_STICKY
    }

    fun startAll() {
        for (device in database.devices.load()) {
            subscribeToDevice(device)
        }
    }

    fun subscribeToDevice(device: Device) {
        println("Subscribing to device: ${device.id}")

        val otherKey = Base64.decode(device.otherKey, BASE64_FLAGS)
        val challenges = firebaseDatabase.getReference("c")
        val cancelMap: HashMap<String, () -> Unit> = HashMap()
        challenges.child(device.id).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val challenge = (database.challenges.fromSnapshot(snapshot, device)
                        ?: return).apply {
                    if(this.signature == null) {
                        this.signature = snapshot.value.toString()
                        database.challenges.save(this)
                    } else {
                        // This does not have to be an indicator for a security issue, it can also be Google delivering notifications twice due to i.e. app restarts
                        println("Challenge already known, skipping: $challenge")
                        return
                    }
                }

                println("Child added: $challenge")

                val duration = System.currentTimeMillis() - challenge.timestamp.time
                when {
                    duration > 35 * 1000 -> {
                        println("Detected old entry ${challenge.challenge} ($duration old), removing...")
                        snapshot.ref.removeValue()
                    }
                    duration < -5 * 1000 -> {
                        println("Found future entry ${challenge.challenge} ($duration old), removing...")
                        snapshot.ref.removeValue()
                    }

                    else -> {
                        Handler().postDelayed({
                            snapshot.ref.removeValue()
                        }, 45*1000)

                        println(" ... got challenge: ${challenge.challenge} with signature ${challenge.signature}")

                        val chal = Base64.decode(challenge.challenge, BASE64_FLAGS)
                        val sig = Base64.decode(challenge.signature, BASE64_FLAGS)

                        // Verify challenge signature
                        val pubKey = KeyFactory.getInstance(SIGNATURE_KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(otherKey))
                        val ok = Signature.getInstance(SIGNATURE_ALGORITHM).run {
                            initVerify(pubKey)
                            update(chal)
                            verify(sig)
                        }

                        if (ok) {
                            println(" ... signature genuine, proceeding")
                            cancelMap[challenge.challenge] = sign(device.id, chal) {
                                cancelMap.remove(challenge.challenge)
                                if (it != null) {
                                    val response: String = Base64.encodeToString(it, BASE64_FLAGS)
                                    println(" ... response: $response")
                                    challenge.response = response
                                    challenge.responseAt = Date()
                                    database.devices.updateLastUsed(device, Date())
                                    database.challenges.save(challenge)
                                    snapshot.ref.setValue(response)
                                } else {
                                    // Canceled
                                    challenge.canceled = true
                                    challenge.responseAt = Date()
                                    database.challenges.save(challenge)
                                    snapshot.ref.removeValue()
                                }
                            }
                        } else {
                            println(" ... signature unsound")
                        }
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val challenge = database.challenges.fromSnapshot(snapshot, device) ?: return
                println("Child changed: $challenge to ${snapshot.value.toString()}")
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val challenge = database.challenges.fromSnapshot(snapshot, device) ?: return
                cancelMap[challenge.challenge]?.run { this() }
                println("Child removed: $challenge")
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {

            }

            override fun onCancelled(databaseError: DatabaseError) {
                println(databaseError.message)
            }
        })
    }

    fun sign(id: String, challenge: ByteArray, callback: (response: ByteArray?) -> Unit): () -> Unit {
        println("Signing $id")
        val intent = Intent(this, BiometricScanner::class.java).apply {
            putExtra("id", id)
            putExtra("challenge", challenge)
            addFlags(ACTIVITY_SIGN_FLAGS)
        }
        broadcastHelper.request(intent) {
            callback(it.getByteArrayExtra("response"))
        }
        startActivity(intent)
        return {
            println("Canceled $id")
            broadcastHelper.cancel(intent, Intent())
        }
    }
}