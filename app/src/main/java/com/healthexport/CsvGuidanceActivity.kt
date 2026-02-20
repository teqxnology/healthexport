package com.healthexport

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CsvGuidanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_csv_guidance)

        findViewById<TextView>(R.id.txtCsvGuide).text =
            """
Bulk import CSV into Google Sheets (recommended for large exports)

1) Export CSV from this app (prefer: All time + split by year).
   Files are saved in your Downloads folder.

2) On a computer (easier), open Google Drive or Google Sheets.

Option A (recommended): create a new spreadsheet per tab
- Create a new Google Sheet.
- File → Import → Upload → select the CSV.
- Choose “Replace spreadsheet” (for a new sheet) or “Insert new sheet(s)”.

Option B: import into an existing spreadsheet
- Open the target spreadsheet.
- File → Import → Upload → select the CSV.
- Choose “Insert new sheet(s)”.
- Rename the imported sheet to match: Activity, Vitals, Sleep, Body Measurements, Nutrition, Cycle Tracking.

Why this helps:
- Importing CSV is a bulk operation and avoids Google Sheets API rate limits.
- After the big initial import, you can use “Export Now” for small incremental updates.
""".trimIndent()
    }
}