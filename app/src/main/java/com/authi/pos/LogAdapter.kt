package com.authi.pos

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : ListAdapter<LogEntry, LogAdapter.ViewHolder>(Diff()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.tvLogTime)
        val message: TextView = view.findViewById(R.id.tvLogMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.time.text = entry.formattedTime
        holder.message.text = entry.message
        holder.message.setTextColor(colorFor(entry.level))
    }

    private fun colorFor(level: LogLevel): Int = when (level) {
        LogLevel.INFO      -> Color.parseColor("#8B949E")
        LogLevel.SENT      -> Color.parseColor("#58A6FF")
        LogLevel.RECEIVED  -> Color.parseColor("#56D364")
        LogLevel.SUCCESS   -> Color.parseColor("#00E676")
        LogLevel.ERROR     -> Color.parseColor("#F85149")
        LogLevel.WARNING   -> Color.parseColor("#E3B341")
    }

    private class Diff : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.timestamp == b.timestamp
        override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
    }
}
