package com.monstertoss.wfp

import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class DeviceActivity : AppCompatActivity() {

    lateinit var database: DeviceDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        actionBar?.setDisplayHomeAsUpEnabled(true)

        database = Room.databaseBuilder(this, DeviceDatabase::class.java, DEVICE_DATABASE).run {
            allowMainThreadQueries()
            build()
        }

        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        val device = database.devices.load(intent.getStringExtra("id") ?: return) ?: return

        val challengesAdapter = ChallengesAdapter(database, keystore)
        database.challenges.bind(device).observe(this, Observer {
            challengesAdapter.submitList(it)
        })

        findViewById<RecyclerView>(R.id.challenges).apply {
            layoutManager = LinearLayoutManager(this@DeviceActivity)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            adapter = challengesAdapter
        }

        findViewById<TextView>(R.id.alias).apply {
            text = device.name
        }
        findViewById<TextView>(R.id.device_ok).apply {
            text = if (run run@{
                        val pubKey = KeyFactory.getInstance(SIGNATURE_KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.decode(device.otherKey, BASE64_FLAGS)))
                        val entry = keystore.getEntry(device.ownKey, null) as? KeyStore.PrivateKeyEntry
                                ?: return@run false

                        database.challenges.load(device).all {
                            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                                // Verify challenge signature
                                initVerify(pubKey)
                                update(Base64.decode(it.challenge, BASE64_FLAGS))
                                verify(Base64.decode(it.signature, BASE64_FLAGS))
                                        && run {
                                    // Verify our response
                                    if (it.response != null) {
                                        initVerify(entry.certificate.publicKey)
                                        update(Base64.decode(it.challenge, BASE64_FLAGS))
                                        verify(Base64.decode(it.response, BASE64_FLAGS))
                                    } else
                                        true
                                }
                            }

                        }
                    })
                context.getString(R.string.signature_ok)
            else
                context.getString(R.string.signature_invalid)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        database.close()
    }
}