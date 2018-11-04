package com.monstertoss.wfp

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView

class DevicesAdapter(private val activity: SetupActivity, private val database: DeviceDatabase) : RecyclerView.Adapter<DevicesAdapter.Holder>() {
    private var devices = database.devices.bind()

    init {
        devices.observe(activity, Observer {
            notifyDataSetChanged()
        })
    }

    class Holder(val view: ConstraintLayout) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.device_part, parent, false) as ConstraintLayout)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val device = devices.value?.get(position) ?: return
        holder.view.apply {
            setOnClickListener {
                activity.openDetails(device.id)
            }

            setOnLongClickListener {
                println("Long click ${device.name}")
                true
            }

            findViewById<TextView>(R.id.alias).apply {
                text = device.name
            }
            findViewById<TextView>(R.id.lastUsed).apply {
                text = context.getString(R.string.device_last_used, DateUtils.getRelativeTimeSpanString(device.lastUsed.time))
            }
        }
    }

    override fun getItemCount() = devices.value?.size ?: 0

    fun add(device: Device) {
        database.devices.save(device)
    }
}