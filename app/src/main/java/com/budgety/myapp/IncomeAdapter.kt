package com.budgety.myapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView

class IncomeAdapter(
    context: Context,
    incomes: List<Income>,
    private val onEditClick: (Income) -> Unit,
    private val onDeleteClick: (Income) -> Unit
) : ArrayAdapter<Income>(context, 0, incomes) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var itemView = convertView
        if (itemView == null) {
            itemView = LayoutInflater.from(context).inflate(R.layout.item_income, parent, false)
        }

        val income = getItem(position)

        val tvTitle = itemView!!.findViewById<TextView>(R.id.tvTitle)
        val tvAmount = itemView.findViewById<TextView>(R.id.tvAmount)
        val tvDateTime = itemView.findViewById<TextView>(R.id.tvDateTime)
        val btnEdit = itemView.findViewById<ImageView>(R.id.btnEdit)
        val btnDelete = itemView.findViewById<ImageView>(R.id.btnDelete)

        if (income != null) {
            tvTitle.text = income.title
            tvAmount.text = String.format("+₱%.2f", income.amount)
            tvDateTime.text = income.dateTime

            btnEdit.setOnClickListener { onEditClick(income) }
            btnDelete.setOnClickListener { onDeleteClick(income) }
        }

        return itemView
    }
}
