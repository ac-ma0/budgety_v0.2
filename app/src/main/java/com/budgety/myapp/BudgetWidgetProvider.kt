package com.budgety.myapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class BudgetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val dbHelper = DatabaseHelper(context)
            // Kinukuha natin by default ang User ID 1 (Main Account) para sa widget
            val totalIncome = dbHelper.getTotalIncomeByUser(1)
            val totalExpense = dbHelper.getTotalExpensesByUser(1)
            val balance = totalIncome - totalExpense
            val views = RemoteViews(context.packageName, R.layout.widget_budget)
            views.setTextViewText(R.id.widgetTvBalance, String.format("₱%.2f", balance))

            // Para mag-open ang MainActivity kapag pinindot ang widget
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTvBalance, pendingIntent)
            val quickAddIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_QUICK_ADD_EXPENSE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val quickAddPendingIntent = PendingIntent.getActivity(
                context, 1, quickAddIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetBtnQuickAdd, quickAddPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
