# Charts redesign (on 4.8.1)

## Build fix
- InteractiveDonutChart legend: `Surface(onClick)` → `Surface(modifier = Modifier.clickable)` + Material3 Surface import

## Visual / interaction upgrade
- **Line chart**: soft area fill, reveal animation, tap-nearest-point selection, theme tooltip, goal dashed line
- **Bar chart**: rounded animated bars, selection highlight, tooltip
- **Donut**: animated ring, tap on arc or legend, center shows label/value/percent/total, dim unselected slices
- Dark/Light via MaterialTheme colors; LTR forced only for chronological axis labels

No new dependencies. Public APIs unchanged. Attachment/Quick-copy/goal card remain as in 4.8.1 product decisions.
