package com.monstertoss.wfp

import android.text.format.DateUtils
import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class ChallengesAdapter(val database: DeviceDatabase, val keystore: KeyStore) : PagedListAdapter<Challenge, ChallengesAdapter.Holder>(DIFF_CALLBACK) {

    class Holder(val view: ConstraintLayout) : RecyclerView.ViewHolder(view)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        getItem(position)?.also {
            val device = database.devices.load(it.device ?: return) ?: return

            holder.view.apply {
                findViewById<TextView>(R.id.challenge).apply {
                    text = it.challenge
                }

                findViewById<TextView>(R.id.signature).apply {
                    text = if (run {
                                // Verify challenge signature
                                val pubKey = KeyFactory.getInstance(SIGNATURE_KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.decode(device.otherKey, BASE64_FLAGS)))

                                Signature.getInstance(SIGNATURE_ALGORITHM).run {
                                    initVerify(pubKey)
                                    update(Base64.decode(it.challenge, BASE64_FLAGS))
                                    verify(Base64.decode(it.signature, BASE64_FLAGS))
                                }
                            })
                        context.getString(R.string.signature_ok)
                    else
                        context.getString(R.string.signature_invalid)
                }

                findViewById<TextView>(R.id.response).apply {
                    text = when (run {
                        // Verify our response
                        val entry = keystore.getEntry(device.ownKey, null) as? KeyStore.PrivateKeyEntry
                                ?: return@run null

                        it.response?.run response@{
                            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                                initVerify(entry.certificate.publicKey)
                                update(Base64.decode(it.challenge, BASE64_FLAGS))
                                verify(Base64.decode(this@response, BASE64_FLAGS))
                            }
                        }
                    }) {
                        true -> context.getString(R.string.signature_ok)
                        false -> context.getString(R.string.signature_invalid)
                        null -> context.getString(R.string.signature_not_present)
                    }
                }

                findViewById<TextView>(R.id.timestamp).apply {
                    text = DateUtils.getRelativeTimeSpanString(it.timestamp.time)
                }
                findViewById<TextView>(R.id.response_at).apply {
                    val diff = ((it.responseAt.time-it.timestamp.time)/1000).toInt()
                    text = resources.getQuantityString(R.plurals.challenge_seconds_later, diff, diff)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.challenge_part, parent, false) as ConstraintLayout)

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Challenge>() {
            override fun areItemsTheSame(oldItem: Challenge, newItem: Challenge): Boolean = oldItem.challenge == newItem.challenge
            override fun areContentsTheSame(oldItem: Challenge, newItem: Challenge): Boolean = oldItem == newItem
        }
    }
}