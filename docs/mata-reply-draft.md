# Draft reply to mata (Discord DM) — v2

Receipts-first version. No "in flight" hedges; every verb claimed
below ships in the build that's hitting Play closed testing today.

---

hey, follow-up on your two questions —

**1. preference list** — full canonical reference at
https://github.com/engindearing-projects/OmniTAK-Android/blob/main/PREFERENCES.md
(mirror in the iOS repo). callsign, team color, units, coord format,
basemap, mesh device config, layer toggles. each row lists the
ATAK-side aliases it accepts (`locationCallsign`, `locationTeam`,
`coord_display_format`, `rangeSystem`, etc.) so QR codes generated for
full ATAK route to the right OmniTAK field unchanged.

**2. QR enrolment + import** — `tak://` URL scheme, both platforms:

- `tak://com.atakmap.app/connect?host=…&port=…&proto=…` — works
- `tak://com.atakmap.app/import?url=https://…/file.zip` — works
- `tak://com.atakmap.app/preference?key1=…&type1=…&value1=…&keyN=…` — works
- `tak://com.atakmap.app/enroll?host=…&username=…&token=…` — **full CSR flow on both platforms now**: client generates RSA-2048 key pair locally, builds PKCS#10 CSR, POSTs to `:8446/Marti/api/tls/signClient/v2` with Bearer (Basic + token-query fallbacks for the OpenTAKserver builds that need them), parses the signed cert + CA chain, assembles a PKCS#12, stores it, auto-connects. tested against TAK 5.7; need a live OTS to flush any edge cases — happy to hand you an APK if you want to bang on it before it hits closed testing.

data-package `.zip` import (server.pref + .p12 certs + MANIFEST) works
on both via Files (iOS) / share intent or `<app>/files/import/`
(Android). first-launch auto-import shipped on both platforms — closed
testers land on `tak.engindearing.soy:8089:ssl` with the bundled cert,
no manual config.

**3. on idiot-proof scaling 80 nodes** — your strategic ask got first-class
support, not roadmap. new `configBundleUrl` preference on both
platforms. operator publishes one URL on the OTS onboarding portal,
every EUD points at it once (via Settings or a `tak://preference`
QR), and on each launch the app fetches it and applies:

- if the response is a TAK `.zip` data package → goes through the
  importer (server + certs + prefs all in one)
- if the response is `text/plain` of
  `tak://com.atakmap.app/preference?…` URLs (one per line, `#`
  comments allowed) → preferences applied directly

your workflow: edit the file server-side as the event evolves, every
EUD picks up changes at next launch without anyone re-scanning. PLI
intervals, basemap defaults, team-color rules — all centralisable
with one URL.

deferred (deliberate): periodic background polling. doze on Android +
BGTask scheduling on iOS make hourly polls a battery footgun for
event-day scale; pull-on-launch + a "refresh config now" button
covers the operator pattern without burning radios. if you'd want
polling for a specific reason, say the word.

**4. Netherlands** — vc32 going up to Play closed testing now. NL
country list should be live; tell me if it 404s and i'll chase it.

new Android build: `0.3.0 vc32` (228 MB AAB, going to closed testing
today). iOS 2.15.0 archiving through Xcode → TestFlight in the next
push.

keep the feedback coming — here or repo issues, no wrong door. genuinely
excited to see the 80-node setup come together.

-engie
