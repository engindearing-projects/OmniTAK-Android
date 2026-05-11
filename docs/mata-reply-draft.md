# Draft reply to mata (Discord DM)

Use whatever shape feels right — the table + links carry the receipts.
Suggested raw text below (Discord renders the markdown).

---

Quick update — the two things you asked about are both in the next build:

**Supported preferences:** full list lives at
https://github.com/engindearing-projects/OmniTAK-Android/blob/main/PREFERENCES.md
(mirror in the iOS repo). It covers callsign, team color, units,
coord format, basemap, mesh device config, and the on/off layer
toggles, plus the ATAK-side aliases each one accepts so a portal
generating prefs for ATAK works against OmniTAK unchanged.

**QR enrolment + import** — `tak://` deep links now work on both
platforms. Subpath matrix:

| Verb | Android | iOS |
|---|---|---|
| `tak://…/connect?host=…&port=…&proto=…` | ✅ | ✅ |
| `tak://…/import?url=https://…/file.zip` | ✅ | ✅ |
| `tak://…/preference?key1=…&type1=…&value1=…&keyN=…` | ✅ | ✅ |
| `tak://…/enroll?host=…&username=…&token=…` | partial (stages the server) | ✅ full CSR flow |

The Android `/enroll` path still owes you the full CSR exchange against
TAK Server / OpenTAKserver port 8446 — that's tracked as GAP-081, in
flight. iOS has the full thing today (QR scanner → CSR generation →
signed cert in keychain → auto-connect).

Data-package `.zip` import (server.pref + .p12 certs) works on both
platforms via Files (iOS) / share intent or `<app>/files/import/`
(Android). Auto-import on first launch ships on Android today; iOS
parity is GAP-112, also in flight.

**On idiot-proof scaling** — your event with 80 nodes is exactly the
operating point we want OmniTAK to own. The big missing piece for
that, server-pushed remote config (operator publishes PLI intervals,
basemap defaults, callsign rules from the portal → every connected
EUD picks them up), is filed as GAP-108. Today you can get most of
the way there by generating a `tak://preference?…` QR or link
per-team in your OTS onboarding portal — that should let you onboard
folks at the event without per-device hand-tuning.

**Netherlands** — Play closed-testing country list should now include
NL [^pending-confirm]; let me know if it still 404s for you and I'll
chase it down in the console.

Keep the suggestions coming — Discord or repo issues, no wrong door.

-engie

---

[^pending-confirm]: J — verify Netherlands is in the Play Console country
list for closed testing before sending. Path: Play Console → OmniTAK →
Testing → Closed testing → engindearing track → Countries / regions.
