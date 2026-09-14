package com.budgety.myapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView

class ExpenseAdapter(
    context: Context,
    expenses: List<Expense>,
    private val onEditClick: (Expense) -> Unit,
    private val onDeleteClick: (Expense) -> Unit
) : ArrayAdapter<Expense>(context, 0, expenses) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var itemView = convertView
        if (itemView == null) {
            itemView = LayoutInflater.from(context).inflate(R.layout.item_expense, parent, false)
        }

        val expense = getItem(position)

        val tvTitle = itemView!!.findViewById<TextView>(R.id.tvTitle)
        val tvAmount = itemView.findViewById<TextView>(R.id.tvAmount)
        val tvDateTime = itemView.findViewById<TextView>(R.id.tvDateTime)
        val btnEdit = itemView.findViewById<ImageView>(R.id.btnEdit)
        val btnDelete = itemView.findViewById<ImageView>(R.id.btnDelete)

        if (expense != null) {
            tvTitle.text = expense.title
            tvAmount.text = String.format("-₱%.2f", expense.amount)
            tvDateTime.text = expense.dateTime

            btnEdit.setOnClickListener { onEditClick(expense) }
            btnDelete.setOnClickListener { onDeleteClick(expense) }
        }

        return itemView
    }
}
