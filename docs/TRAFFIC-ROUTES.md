# Isfahan route catalogue

The existing `shiraz`, `zobahan`, `kharrazi` entries are retained. The three additions below share **one catalogue** (`TrafficRepository.CORRIDORS`) across the screen, worker and agent. These are full-road requests in **one explicitly displayed driving direction**, not claims about both carriageways or every street segment.

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

## Request and response safeguards

The official Neshan v4 directions contract supports `waypoints` in `latitude,longitude` order, separated by `|`; `alternative=false` requests one route. [1](https://platform.neshan.org/api/direction)

- New entries send their intermediate, same-carriageway trace points as URL-encoded waypoints, not just two loose endpoints.
- A new-road status requires a bounded precision-5 `overview_polyline`. Both endpoints must match in the right direction within **50 m**, every trace checkpoint must be near the response route, and the response must not detour away from the road trace (150 m path/simplification tolerance). A short terminal section cannot disappear merely because the wider path tolerance allowed it. Missing, reversed, partial, malformed or off-road geometry fails closed. It is not relabelled as that highway's fresh traffic.
- `6.4`, `3.0`, `2.0` minute reference times are **approximate classification baselines**, not measured free-flow data. As before, smooth/moderate/heavy is a duration/reference estimate, not a sensor measurement for each segment. The screen discloses this limitation and the one-way coverage.
- Automated tests use **synthetic JSON/geometry and mocked transport**. They establish URL/geometry/catalogue contracts, not current road conditions, live account access, or Neshan availability.

## Attribution

Route coordinates derived from **© OpenStreetMap contributors**, available under [ODbL 1.0](https://www.openstreetmap.org/copyright). Attribution is also visible on the traffic screen. No map tiles or bulk map database are bundled.
