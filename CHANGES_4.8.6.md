# KhodroYar 4.8.6

## Fix periodic service km calculation
- Single shared **vehicle current km** (کیلومتر فعلی خودرو)
- Auto-increments when services with km are registered; manually editable
- Per type: replacement km + next replacement km
- remaining = next replacement km − vehicle current km
- Example: current 100000, next belt 130000 → 30000 remaining

## Rental mode
- Settings → Work mode → **اجاره**
- Rates: Daily/fixed salary + Leave amount
- Service types: **حقوق ثابت** and **مرخصی** (fixed amount from Rates, no km/hour calc)
- Shown only when Rental is enabled

## Version
- versionName 4.8.6 · versionCode 39
