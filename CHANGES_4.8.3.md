# KhodroYar 4.8.3

## Maintenance (oil / timing belt / spark plugs / brake pads / fuel filter)
- Remaining distance = configured interval − Σ(service km since last replacement)
- Example: interval 5000 km + four 100 km services → 4600 km remaining
- Each item has its own interval (Rates), driven km, remaining, due notification, and reset on new maintenance record
- Home (Dashboard) and Maintenance tab show status cards for all five tracked items
- Notifications cover all tracked service types

## Records
- Edit + view details for personal expenses and incomes
- Yearly filter for purchases, incomes, and fuel records
- More personal purchase categories (food, transport, health, home, entertainment, education, bills, …)
- Category selection is a horizontal chip bar

## Reports
- Service type filter is a horizontal chip bar
- Top period selectors are four boxed cards: This Month, Last Month, This Week, Year

## Font scale / accessibility
- Cards use soft min-heights and grow with content
- MetricPair uses IntrinsicSize so neighbours stay aligned under large system fonts
- Labels/values allow more lines and ellipsize instead of clipping

## DB
- Room v4: sparkPlugKmInterval, brakePadKmInterval, fuelFilterKmInterval on rates_by_year

## Version
- versionName **4.8.3** · versionCode **36**
