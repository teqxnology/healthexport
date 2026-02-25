
<img width="360" height="151" alt="Health Connect" src="https://github.com/user-attachments/assets/3761cce5-f5f2-4090-be70-da1374c58c2c" />

<a href="https://play.google.com/store/apps/details?id=com.teqxnology.healthdataexport&pcampaignid=web_share">
  <img src="https://github.com/user-attachments/assets/1cb8443a-2312-48f4-a31b-e2eb52a95fce" alt="Get it on Google Play" width="343" height="343">
</a>

# Health Data Export

### Description
Export your Health Connect data to Google Sheets or CSV. And because it pull data directly from Health Connect, it is compatible most health and fitness apps (Strava, Garmin, Fitbit, Coros, Samsung, Polar, Withings, Zepp/Amazfit, etc.). Choose which health metrics to include. Flexibility to select a date range. Supports the following data types

<img width="354" height="237" alt="app-diagram" src="https://github.com/user-attachments/assets/ebb5ec1d-2821-4a83-ba15-b6f9ba9c246b" />

##### Activity
- Active calories burned
- Distance
- Elevation gained
- Exercise sessions
- Floors climbed
- Power
- Speed
- Steps
- Total calories burned
- VO2 max
- Wheelchair pushes

##### Body measurements
- Body fat %
- Bone mass
- Height
- Lean body mass
- Weight

##### Cycle tracking
- Cervical mucus
- Menstruation
- Ovulation test
- Sexual activity

##### Sleep
- Sleep sessions (light, deep, REM sleep & awake)

##### Vitals
- Blood glucose
- Blood pressure
- Body temperature
- Heart rate (min/max/avg)
- Heart rate variability (min/max/avg)
- Oxygen saturation (min/max/avg)
- Respiratory rate (min/ax/avg)
- Resting heart rate

### App Information
Android 9+
<img width="324" height="702" alt="Screenshot_20260220-223508" src="https://github.com/user-attachments/assets/c6e3f62c-c198-46ec-a83a-a85a2ee45601" />
<img width="324" height="702" alt="Screenshot_20260220-223517" src="https://github.com/user-attachments/assets/7f79e9fe-6697-4ff9-ba97-6d46aba4f24c" />
<img width="324" height="702" alt="Screenshot_20260220-223536" src="https://github.com/user-attachments/assets/84c377d2-49b1-4b86-8c2b-28476806e0ae" />
<img width="324" height="702" alt="Screenshot_20260220-223545" src="https://github.com/user-attachments/assets/45169032-16bb-4d9b-9699-f628ca2c24fe" />
<img width="324" height="702" alt="Screenshots_2026-02-20-22-37-21" src="https://github.com/user-attachments/assets/fecc0c29-b85b-4b81-90a3-30e02c06107c" />


## Download & Install (Android only)

<a href="https://play.google.com/store/apps/details?id=com.teqxnology.healthdataexport&pcampaignid=web_share">
  <img src="https://github.com/user-attachments/assets/69c6cffb-093c-4a34-b896-0523709117b4" alt="Get it on Google Play" width="528" height="343">
</a>

---

## Create a Google Spreadsheet + Get the Sheet ID

### 1) Create a new spreadsheet
1. Open Google Sheets: https://sheets.google.com
2. Click **Blank** (or create a new spreadsheet in Google Drive)
3. Name it (example: `Health Export`)

### 2) Get the Spreadsheet ID
1. Open the spreadsheet in a browser
2. Copy the ID from the URL:

Example URL:
```
https://docs.google.com/spreadsheets/d/1AbCDefGhIJkLmNoPqrStuVwxYZ1234567890/edit#gid=0
```

✅ **Spreadsheet ID** is the part between `/d/` and `/edit`:
```
1AbCDefGhIJkLmNoPqrStuVwxYZ1234567890
```

3. Paste this into the app’s **Google Sheet ID** field

---

## Authorize Google Sign-In (Google Sheets access)

### 1) Connect your Google account
1. In the app, tap **Connect Google Account**
2. Choose the Google account you want to use

### 2) Approve permissions
During sign-in you’ll see a consent prompt asking to allow access to Google Sheets.

✅ Tap **Allow** to grant access so the app can write into your spreadsheet.

> ⚠️ If you don’t approve, exports to Google Sheets will fail.

---

## Authorize Health Connect (read permissions)

### 1) Install/Update Health Connect
- Make sure **Health Connect** is installed and set up on your phone (some Android versions integrate it into Settings).
- Health Connect is available on Play Store for older versions of Android

### 2) Grant permissions
When prompted (or if you denied earlier):
1. Open **Health Connect**
2. Settings > Apps > App info > Health Data Export > Open
3. Go to **App permissions**
4. Find this app
5. Allow read permissions for the data you want to export (Steps, Sleep, Heart Rate, etc.)

✅ Without permission, that data type will export as empty.

---

## High-volume data warnings (important)

Some metrics generate *a lot* of records and can:
- take much longer,
- drain battery,
- trigger Health Connect rate limits,
- produce very large CSV files.

⚠️ **High-volume examples:**
- Heart rate (min/max/avg)
- HRV (min/max/avg)
- Oxygen saturation (min/max/avg)
- Respiratory rate (min/max/avg)

**Recommendation:** For large date ranges (especially **All time**), enable these only if you really need them.

---

## Safer Export Mode (slower but more reliable)

The **Safer export mode** toggle is designed to reduce rate-limit errors by exporting more slowly.

When enabled, the app will:
- ⏳ use **larger time chunks**
- 🐢 add a **2-second delay between chunks**
- 📄 tune page sizes to reduce request bursts

✅ Use this if you frequently see:
> “Rate limited request quota has been exceeded…”

> ⏱️ Expect exports to take longer—this is normal.

---

## Recommended workflow (best practice)

### ✅ Preferred approach: Bulk CSV first, then Auto Export for ongoing updates
This avoids Google Sheets API limits and is usually faster for large history exports.

#### Step 1: Export bulk history to CSV
1. Select your data categories + metrics
2. Choose a large date range (e.g., **All time**)
3. Tap **Export CSV to Downloads**
4. The CSV file(s) will be saved to your phone’s **Downloads** folder

#### Step 2: Import CSV into Google Sheets (bulk load)
Options:
- Upload CSV to Google Drive → open with Google Sheets
- Or import from within Google Sheets (File → Import)

✅ This is the best way to load large historical datasets.

#### Step 3: Use “Export to Google Sheets” for incremental updates
After the bulk import:
1. Use a smaller date range (e.g., last 7/30 days)
2. Tap **Export to Google Sheets**
3. Repeat as needed

#### Step 4: Enable Auto Export for ongoing sync
1. Enable **Auto Export**
2. Choose a frequency (e.g., every 12 or 24 hours)
3. **Keep date range reasonable** (e.g., 7 days. Small ranges recommended)

---

## Tips & Troubleshooting

### “Only Vitals exported”
- Ensure you enabled at least one metric under each category (e.g., Steps for Activity, Sleep Sessions for Sleep, Weight for Body).

### Rate limit errors
- Turn on **Safer export mode**
- Reduce date range
- Disable high-volume metrics
- Try again later (quota replenishes over time)
