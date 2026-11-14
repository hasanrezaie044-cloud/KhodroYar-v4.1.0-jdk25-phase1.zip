# Isfahan route catalogue

The existing `shiraz`, `zobahan`, `kharrazi` entries are retained. The three additions below share **one catalogue** (`TrafficRepository.CORRIDORS`) across the screen, worker and agent. The endpoint pairs span the named roads in **one explicitly displayed driving direction**. As in the working 5.5.6 system, Neshan selects the actual route between the endpoints; the app does not guarantee every segment or both carriageways.

## New roads and endpoint evidence

Coordinates are latitude, longitude. Public OpenStreetMap road/node data were inspected; no device location, private key, or live Neshan traffic call was used.

| ID | Displayed road | Start | End | Coverage/direction |
|---|---|---|---|---|
| `keshvari` | اتوبان شهید کشوری | `32.6144146,51.7266836` | `32.5910471,51.6675926` | میدان قوچانی → پل دفاع مقدس / اتصال شهدای صفه؛ east to west |
| `meysami` | بزرگراه شهید میثمی | `32.6235237,51.6172508` | `32.6043362,51.6241549` | حبیب‌اللهی → اتصال آقارب‌پرست؛ north to south, including the terminal ramp |
| `habibollahi` | بزرگراه شهید حبیب‌اللهی | `32.6350839,51.6271786` | `32.6235237,51.6172508` | میرزا کوچک‌خان → میثمی؛ north to south |

### Keshvari

- [Way 391992729 (full JSON)](https://www.openstreetmap.org/api/0.6/way/391992729/full.json): starts at node **3293918441**, `32.6144146,51.7266836`. Its [connected ways](https://www.openstreetmap.org/api/0.6/node/3293918441/ways.json) establish میدان شهید قوچانی, not an arbitrary neighbourhood coordinate.
- [40288317](https://www.openstreetmap.org/api/0.6/way/40288317/full.json), [311039194](https://www.openstreetmap.org/api/0.6/way/311039194/full.json), [311039197](https://www.openstreetmap.org/api/0.6/way/311039197/full.json) continue the same named carriageway.
- [844796909](https://www.openstreetmap.org/api/0.6/way/844796909/full.json) and [180313362](https://www.openstreetmap.org/api/0.6/way/180313362/full.json) cover پل دفاع مقدس through node **1907445148**. Its [connected ways](https://www.openstreetmap.org/api/0.6/node/1907445148/ways.json) change to شهدای صفه (1308966588), establishing the western boundary.

### Meysami

- [Node 1934453554 connected ways](https://www.openstreetmap.org/api/0.6/node/1934453554/ways.json) establish the Habibollahi/Meysami name boundary at `32.6235237,51.6172508`.
- [183086260](https://www.openstreetmap.org/api/0.6/way/183086260/full.json) and [478259199](https://www.openstreetmap.org/api/0.6/way/478259199/full.json) cover the southbound mainline through junction 6B.
- [380725627](https://www.openstreetmap.org/api/0.6/way/380725627/full.json) is the terminal connection. [Node 1934453300 connected ways](https://www.openstreetmap.org/api/0.6/node/1934453300/ways.json) establish its merge with آقارب‌پرست at `32.6043362,51.6241549`. The request does not stop halfway down the mainline.

### Habibollahi

- [478273170](https://www.openstreetmap.org/way/478273170), [478273168](https://www.openstreetmap.org/way/478273168), [478273167](https://www.openstreetmap.org/way/478273167), [1184993784](https://www.openstreetmap.org/way/1184993784), [1091780918](https://www.openstreetmap.org/way/1091780918), [183086300](https://www.openstreetmap.org/api/0.6/way/183086300/full.json) cover the chosen southbound carriageway, including the terminal tunnel.
- The [Nominatim geometry result](https://nominatim.openstreetmap.org/search?q=Habibollahi%20Expressway%2C%20Isfahan&format=jsonv2&polygon_geojson=1&dedupe=0&limit=40) supplies 478273170's start `32.6350839,51.6271786`; its opposite carriageway ends at [1934453381](https://www.openstreetmap.org/api/0.6/node/1934453381/ways.json), where the name changes to میرزا کوچک‌خان.
- [1934453554](https://www.openstreetmap.org/api/0.6/node/1934453554/ways.json) is the southern change to میثمی, **not** the southern end of the whole combined corridor.
- Relation 7521008 alone is incomplete for full highway endpoints and includes service roads. It was **not** treated as the entire highway.

## 5.5.8 compatibility repair: the working 5.5.6 system

The owner reported that traffic stopped working after the 5.5.7 route additions.
That release had also changed requests for new roads and required a matching
`overview_polyline`. A perfectly usable duration/distance response without that
geometry could fail one route and therefore the whole snapshot. The user's live
reply/key was not collected, so this is a reproduced compatibility case, not a
claim to know the exact live provider error.

**All six roads now use the same old contract:**

- `GET https://api.neshan.org/v4/direction?type=car&origin=<lat,lng>&destination=<lat,lng>`
- The same private user-supplied `Api-Key` header; no AI Bearer token or device location.
- Only `type`, `origin` and `destination` query parameters. No `waypoints`,
  `alternative`, alternate endpoint, extra provider permission or hidden fallback.
- The **unchanged 5.5.6 parser** sums `duration.value` and `distance.value` from the
  first route's legs. Optional geometry is not needed or inspected. The original
  HTTP/key checks, finite positive time, nonnegative distance, 128 KiB body limit
  and 1–100-leg bound remain. Invalid data still fails; no made-up traffic is shown.
- The three original endpoint pairs/reference times are unchanged. The three new
  endpoint pairs in the table above remain; only the added waypoint/trace machinery
  is removed. Neshan may choose a detour between endpoints, as with the old system.
- `6.4`, `3.0`, `2.0` minute reference times remain **approximate classification
  baselines**, not measured free-flow data. Smooth/moderate/heavy is the old
  duration/reference estimate, not a congestion measurement at each road segment.
- Tab, worker and agent continue using the same repository/catalogue. The API key's
  storage is unchanged and an update does not clear the saved key.

JVM regressions use the old reply shape **without any polyline for all six routes**,
pin exact legacy URLs, and keep invalid-response/key-redaction checks. A native UI
test uses mocked Neshan HTTP, verifies all six rendered statuses, then taps manual
refresh and verifies the next six requests. Synthetic evidence is named
`traffic-legacy.json` and `37-traffic-legacy-response-six-routes.png`. These tests
are not live Neshan/account/network availability or physical-phone proof. No
personal key is needed or sent. Endpoint-source evidence above is unchanged.

## Attribution

Route coordinates derived from **© OpenStreetMap contributors**, available under [ODbL 1.0](https://www.openstreetmap.org/copyright). Attribution is also visible on the traffic screen. No map tiles or bulk map database are bundled.
