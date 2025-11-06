package com.okumi.qlabcontroller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SavedConnectionAdapter(
    private val connections: MutableList<SavedConnection>,
    private val onConnectionClick: (SavedConnection) -> Unit,
    private val onDeleteClick: (SavedConnection) -> Unit
) : RecyclerView.Adapter<SavedConnectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val workspaceNameText: TextView = view.findViewById(R.id.workspaceNameText)
        val addressText: TextView = view.findViewById(R.id.addressText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_connection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val connection = connections[position]

        holder.workspaceNameText.text = connection.workspaceName
        holder.addressText.text = "${connection.ipAddress}:${connection.port}"
        holder.timeText.text = "Last connected: ${getTimeAgo(connection.lastConnected)}"

        holder.itemView.setOnClickListener {
            onConnectionClick(connection)
        }

        holder.deleteButton.setOnClickListener {
            onDeleteClick(connection)
        }
    }

    override fun getItemCount() = connections.size

    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
            }
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diff)
                "$hours ${if (hours == 1L) "hour" else "hours"} ago"
            }
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                "$days ${if (days == 1L) "day" else "days"} ago"
            }
            else -> {
                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    fun removeAt(position: Int) {
        connections.removeAt(position)
        notifyItemRemoved(position)
    }

    fun updateConnections(newConnections: List<SavedConnection>) {
        connections.clear()
        connections.addAll(newConnections)
        notifyDataSetChanged()
    }
}
