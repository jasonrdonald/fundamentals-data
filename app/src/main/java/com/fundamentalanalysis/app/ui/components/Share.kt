package com.fundamentalanalysis.app.ui.components

import android.content.Context
import android.content.Intent

/** Hands a CSV report to the system share sheet (email, Drive, Sheets, ...). */
fun shareCsv(context: Context, title: String, csv: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    context.startActivity(Intent.createChooser(send, "Export $title"))
}
