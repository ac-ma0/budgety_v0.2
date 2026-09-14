package com.budgety.myapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView

class AuditLogAdapter(
    context: Context,
    logs: List<AuditLog>
) : ArrayAdapter<AuditLog>(context, 0, logs) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var itemView = convertView
        if (itemView == null) {
            itemView = LayoutInflater.from(context).inflate(R.layout.item_transaction, parent, false)
        }

        val item = getItem(position)

        val tvTitle = itemView!!.findViewById<TextView>(R.id.tvTitle)
        val tvAmount = itemView.findViewById<TextView>(R.id.tvAmount)
        val tvDateTime = itemView.findViewById<TextView>(R.id.tvDateTime)
        val tvTag = itemView.findViewById<TextView>(R.id.tvTag)
        val btnEdit = itemView.findViewById<ImageView>(R.id.btnEdit)
        val btnDelete = itemView.findViewById<ImageView>(R.id.btnDelete)

        if (item != null) {
            // Use action as tag, details as title, and date
            tvTitle.text = item.details
            tvDateTime.text = item.date
            tvAmount.text = "" // no amount for audit logs

            tvTag.text = item.action

            // Hide edit/delete buttons for full history
            btnEdit.visibility = View.GONE
            btnDelete.visibility = View.GONE
        }

        return itemView
    }
}
