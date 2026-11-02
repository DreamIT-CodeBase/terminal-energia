package com.terminalscout.app.prod.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.terminalscout.app.prod.databinding.ItemAppErrorBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders [ErrorLog.errors] into the banner that hovers over every screen.
 *
 * No `DiffUtil`: the list is a handful of standing conditions that change on a human timescale, so
 * `notifyDataSetChanged` costs nothing measurable and avoids a diff callback that would have to be kept
 * in step with [AppError]'s fields.
 *
 * @param onRowClick invoked with the tapped row's error. The listener lives on the ROW rather than on
 *   the RecyclerView: a `setOnClickListener` on a scrolling RecyclerView does not fire reliably, because
 *   the touch is consumed by the scroll handling before it can become a click.
 *
 *   It is handed the [AppError] rather than nothing, so the caller can send the user to the screen that
 *   fixes *that* problem — a battery exemption and a missing permission live behind different intents.
 */
class ErrorLogAdapter(
    private val onRowClick: (AppError) -> Unit
) : RecyclerView.Adapter<ErrorLogAdapter.Row>() {

    private var items: List<AppError> = emptyList()

    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(errors: List<AppError>) {
        items = errors
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
        ItemAppErrorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Row, position: Int) {
        val error = items[position]
        // Bound here rather than in onCreateViewHolder so the listener closes over the error itself,
        // with no position lookup to go stale when the list changes under a recycled row.
        holder.binding.root.setOnClickListener { onRowClick(error) }
        holder.binding.errorMessage.text = error.message
        // The source line carries the technical origin and the time, so a tester reporting a problem
        // can say which subsystem raised it without opening Logcat.
        holder.binding.errorSource.text = listOfNotNull(
            error.source,
            clock.format(Date(error.atMs))
        ).joinToString(" · ")
    }

    class Row(val binding: ItemAppErrorBinding) : RecyclerView.ViewHolder(binding.root)
}
