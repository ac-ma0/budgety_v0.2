package com.budgety.myapp

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale

class TransactionAdapter(
    context: Context,
    transactions: List<Transaction>,
    private val onEditClick: (Transaction) -> Unit,
    private val onDeleteClick: (Transaction) -> Unit,
    private val amountsHidden: Boolean = false
) : ArrayAdapter<Transaction>(context, 0, transactions) {

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
            tvTitle.text = item.title
            tvDateTime.text = item.dateTime
            tvTag.text = if (item.type == TransactionType.INCOME) "INCOME" else item.category.toUpperCase(Locale.getDefault())

            if (item.type == TransactionType.INCOME) {
                tvAmount.text = if (amountsHidden) "••••" else String.format("+₱%.2f", item.amount)
                tvAmount.setTextColor(Color.parseColor("#059669"))
                tvTag.setTextColor(Color.parseColor("#059669"))
            } else {
                tvAmount.text = if (amountsHidden) "••••" else String.format("-₱%.2f", item.amount)
                tvAmount.setTextColor(Color.parseColor("#DC2626"))
                tvTag.setTextColor(Color.parseColor("#DC2626"))
            }

            btnEdit.setOnClickListener { onEditClick(item) }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }

        return itemView
    }
}
