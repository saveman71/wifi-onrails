# Portal reference

What `wifi.sncf` serves, captured on board TGV INOUI 6603 (Paris Gare de Lyon to Lyon Perrache,
21 September 2026). None of it can be fetched off a train, so it lives here.

| File | Where it came from | What it is for |
| --- | --- | --- |
| `icons-sprite.svg` | the `<symbol>` elements of `/en/journey` | 266 icons. `ic_portal_*.xml` in `res/drawable` are converted from it |
| `style-dark.json`, `style-light.json` | `/karto/style-{dark,light}.json` | the MapLibre style `TrainMap` loads at runtime |
| `sprites-dark.json` | `/maps/sprites/dark/dark@2x.json` | the map sprite atlas the style points at |
| `avenir-black.woff`, `avenir-medium.woff` | `/86ff03bb…woff`, `/ebf4682e…woff` | Avenir LT W02 95 Black and Avenir Medium. `res/font/*.ttf` are converted from these |
| `train-graph.json` | `/router/api/train/graph` | one trip's rails, 1793 points, as a sample of the shape |
| `wordings-en.json` | `/router/api/media/wordings?language=en` | the portal's own labels |
| `co2-meta.json` | `/co2/meta.json` | CO2 saving per pair of UIC station codes |

Colours read off the rendered page, not guessed:

| | |
| --- | --- |
| Burgundy | `#9C0C35` |
| Navy | `#1A3E70` |
| Card grey | `#F7F8F8`, 20px radius |

Type on the stat tiles: value 24px weight 900 uppercase, unit 10px weight 500 uppercase in the same
burgundy, label 12px weight 500 navy.

`PortalDump` writes a fresh copy of the JSON endpoints to
`/sdcard/Android/data/fr.onrails.trainwifi/files/portal` on the first poll of every trip.
