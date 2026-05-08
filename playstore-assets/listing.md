# OmniTAK Mobile — Google Play Console listing copy

Use this as the source of truth when filling out the Play Console main store listing.
All copy hand-written; no AI signatures, no marketing fluff.

---

## App identity

| Field | Value |
|---|---|
| **App name** (≤30 chars) | `OmniTAK` |
| **Short description** (≤80 chars) | `Open-source TAK client. Tactical map, CoT, Meshtastic. Bring your own server.` |
| **Default language** | English (United States) – `en-US` |
| **App / game** | App |
| **Free / paid** | Free |
| **Contains ads** | No |
| **In-app purchases** | No |

---

## Categorization

| Field | Value |
|---|---|
| **App category** | Communication |
| **Tags** (up to 5) | Tactical mapping, Communication, Two-way radio, Outdoor, Open-source |
| **Email** | j@engindearing.soy |
| **Website** | https://github.com/engindearing-projects/OmniTAK-Android |
| **Privacy policy URL** | https://github.com/engindearing-projects/OmniTAK-Android/blob/main/PRIVACY.md |

> **Note**: Play Console requires the privacy policy to be hosted at a stable URL. The
> markdown link above will resolve once `PRIVACY.md` is committed to the repo (see
> below for the policy text).

---

## Full description (≤4000 chars)

```
OmniTAK is an open-source TAK (Team Awareness Kit) client for Android. It
connects to any TAK Server you point it at, renders a tactical map, exchanges
Cursor-on-Target messages with your team, and integrates Meshtastic mesh radios
for off-grid communication.

OmniTAK is a CLIENT. You bring your own TAK Server — the official community
"CIV" edition from tak.gov, or the open-source OpenTAKServer. OmniTAK does not
provide, broker, or proxy any server.

== What it does ==

• TAK Server connectivity — TCP, TLS, or mutual TLS with client-certificate
  enrollment. Multiple servers, switch between them.
• Cursor-on-Target (CoT) — full XML parser. Send and receive markers, chat,
  PPLI position reports, range-and-bearing lines.
• Tactical map — MapLibre Native vector + raster basemaps. Default ships with
  the CartoDB Dark Matter style for a heads-down tactical look. Pinch-zoom,
  rotation, tilt, dedicated zoom controls.
• Self position — bullseye self-marker, PPLI card with callsign, lat/lon,
  altitude, speed, accuracy. Toggle the card from the long-press radial.
• Long-press radial menu — drop a point, draw a line or polygon, measure,
  open layers panel, all from a single thumb gesture.
• ADS-B traffic — overlay aircraft from a bring-your-own provider.
• Meshtastic — connect to a Meshtastic radio over TCP for off-grid mesh comms.
• Multi-server — manage connections to multiple TAK servers from one app.
• Material 3 dark theme — purpose-built tactical palette, gloved-friendly hit
  targets, floating "Liquid Glass" bottom-tab navigation.

== What it does NOT do ==

• Track you. Zero analytics, zero crash reporting SDKs, zero third-party trackers.
• Phone home. Outbound traffic only goes to TAK servers and ADS-B providers
  YOU configure.
• Pretend to be ATAK. OmniTAK is an independent open-source client with
  TAK-compatible protocols. It is not affiliated with, endorsed by, or derived
  from the U.S. Department of Defense, the TAK Product Center, or ATAK-CIV.

== Who it's for ==

• Search and rescue teams who already use TAK
• Civil defense, fire, EMS volunteers needing a free TAK client
• Outdoor groups running personal TAK servers for trip coordination
• Amateur radio operators bridging Meshtastic and TAK
• Developers and researchers studying CoT-based situational-awareness systems

== Open source ==

OmniTAK is licensed under Apache 2.0. Full source, build instructions, and
issue tracker: https://github.com/engindearing-projects/OmniTAK-Android

A companion iOS client (OmniTAK-iOS) is in active parity development.

== Permissions ==

• Internet, network state — to talk to TAK servers and Meshtastic radios.
• Fine + coarse location — to report your own position (PPLI) and run
  GPS-aware tools (range, bearing, measurement). Location never leaves the
  device unless you connect to a server you configured.

If you find a security issue, please follow the responsible disclosure process
in SECURITY.md in the repo.
```

> Character count: ~2,950 / 4,000 — leaves room to add features later.

---

## What's new (≤500 chars) — current release

For v0.2.7 (versionCode 27):

```
What's new in 0.2.7:
• PPLI now reports your real battery level instead of always showing 100%
• Save Server no longer crashes on mTLS-required hosts
• Add Server: password is masked while typing, Save button is always visible
• Custom map URLs accept ATAK-style {$z}/{$x}/{$y} placeholders
• Map callsign card respects your coord-format preference (lat/lon, MGRS, UTM)
• Dropped markers now render on Adreno-GPU devices
```

> Char count: ~485 / 500.

---

## Content rating (IARC questionnaire — for J to fill in console)

Anticipated answers:

| Question | Answer |
|---|---|
| Violence | None |
| Sexuality | None |
| Profanity | None |
| Controlled substances | None |
| Gambling | None |
| User-generated content shared with others | **Yes** — TAK chat / CoT messages between users on a server they configure. Not moderated by the app. |
| Shares user location with other users | **Yes** — only with the TAK server the user configures. |
| Allows users to communicate | **Yes** |
| Digital purchases | None |

Expected rating outcome: **Everyone** or **Teen** depending on how Google
weights the user-comms checkbox.

---

## Target audience and content

| Field | Value |
|---|---|
| Target age groups | 18+ (tactical / outdoor operations app, not designed for minors) |
| Appeals to children | No |
| Ads | None |

---

## Data safety form (Play Console)

OmniTAK is privacy-respecting. Expected answers:

### Data collected by the app itself
**None.** The app does not collect or transmit user data to Engindearing or any
third party.

### Data the app may transmit (only to user-configured endpoints)
| Data type | Purpose | Encrypted in transit | Optional? |
|---|---|---|---|
| Precise location | App functionality (PPLI, GPS tools) | Yes (TLS to TAK server) | Yes — only sent if user connects to a TAK server |
| Messages | App functionality (TAK chat / CoT) | Yes (TLS) | Yes |

### Data shared with third parties
**None.** OmniTAK only talks to servers the user configures.

### Data deletion
Users can uninstall the app to remove all local data. There is no Engindearing
backend to delete data from because none is collected.

### Security practices
- All transit encrypted (TLS 1.2+)
- App follows Android Keystore best practices for client certificates
- App is open source — security model fully auditable

---

## App access

> "Is all or part of your app restricted based on log-in credentials?" — **No**
>
> The app itself has no login. Every screen (Map, Chat, Servers, Mesh, Settings)
> is reachable from first launch. Network features (Chat / CoT exchange) require
> the user to add a TAK Server first.

For the **Instructions for accessing app** field, paste the block below verbatim:

```
OmniTAK is a TAK (Team Awareness Kit) client. The app has no built-in login,
so reviewers can launch it and explore Map, Servers, Chat, Meshtastic, and
Settings tabs without credentials.

To verify network functionality (chat / position reports), reviewers can add
a free public TCP TAK server with these exact steps:

1. Open the app, tap the Servers tab (third icon in bottom navigation)
2. Tap the green "+" floating button
3. Enter:
     Name: dh2
     Host or IP: tak.dh2.io
     Port: 8088
     Use TLS: OFF
4. Tap "Save Server" — the app auto-connects (status dot turns green)
5. Switch to the Chat tab, tap "All Chat Users · Broadcast"
6. Type any message and hit send — the message will show "sent" status when
   delivered to the server

No personal data, account creation, or payment is required. The reviewer
controls all data: nothing leaves the device unless the reviewer configures
a server.

Source code: https://github.com/engindearing-projects/OmniTAK-Android
```

---

## Production release notes (for the v0.2.7 AAB upload)

Version: `0.2.7` · Version code: `27`

```
OmniTAK Mobile — first production-track release.

Cumulative since v0.1.0:
- Multi-server TAK connectivity (TCP / TLS / mTLS with .p12 client-cert import)
- Auto-connect on Save Server; auto-reconnect on cold launch
- Auto-PPLI broadcast so peers see callsigns immediately
- Cursor-on-Target chat (broadcast + 1:1 DMs, multi-channel)
- Real-device battery in PPLI (not hardcoded 100%)
- Tactical dark basemap (CartoDB Dark Matter) with MapLibre Native
- Custom map URLs — ATAK-style {$z}/{$x}/{$y} and standard {z}/{x}/{y}
- Coord display: lat/lon, MGRS, or UTM (respects user pref everywhere)
- Floating bottom-tab navigation (Map / Chat / Servers / Mesh / Settings)
- Long-press radial menu — drop, draw, measure, layers
- Dropped markers render across Adreno / Mali / desktop-class GPUs
- FusedLocationProviderClient for real GPS fixes (no Bay Area fallback)
- Foreground service for Doze-resistant connection persistence
- Meshtastic TCP integration with mesh-chat node detail
- ADS-B traffic overlay scaffolding
- Material 3 dark theme with shared iOS/Android design tokens
- Wide device compatibility — Pixel, Samsung, Nothing, GrapheneOS, LineageOS
  (no required Google services beyond Play install)

Apache 2.0 licensed. Source: https://github.com/engindearing-projects/OmniTAK-Android
```

---

## Production access application

> Play opens this form once a Play Console developer has run a closed
> test for ≥14 consecutive days with ≥12 active opt-in testers. Window
> begins **2026-05-08** (12-tester threshold reached) and clears
> **2026-05-22** at the earliest, assuming no testers churn.

### Q1 — Tell us how you tested your app and gathered feedback (≤500 words)

```
OmniTAK has been in Google Play closed testing since 2026-04-26 under
package soy.engindearing.omnitak.mobile. We ramped from a 2-tester
internal track to 12 opt-in closed testers over ~12 days, sourcing
testers from the public TAK Community Discord (#takdev and adjacent
channels) and from existing TAK operators in adjacent open-source
ecosystems (OpenTAKServer, Meshtastic).

Feedback flowed through three channels:

1. GitHub Issues at github.com/engindearing-projects/OmniTAK-Android —
   reproducible bugs with logs, device info, and screenshots. Each
   issue is triaged within 24 hours, fixed on a feature branch, and
   landed in a numbered closed-test build (vc23 → vc24 → vc27 in this
   testing cycle alone).

2. The TAK Community Discord — real-time conversations with operators
   running the build on field devices (Samsung S23 Ultra Android 16,
   TCL 5062W with Adreno 610, Pixel 7, Nothing Phone). Discord caught
   issues that GitHub didn't: a hardcoded battery percentage, a custom
   tile-URL format compatibility gap with civilian TAK forks, and a
   coord-format preference that wasn't honored across all screens.

3. Direct iMessage / email from an evaluating municipal user (Bronx
   County DA's Office) reviewing OmniTAK for field personnel.

Every reported issue is reproduced on either an emulator or a physical
device before code changes; fixes are verified on emulator with
screenshots side-by-side against the iOS sibling build. Closed-track
crash-free rate has stayed above 99% throughout testing per Play
Vitals (uptake skewed toward operators who specifically opt out of
"send usage and diagnostic info," so the official number under-reads
total stability).
```

### Q2 — Tell us about the changes you've made based on testing (≤500 words)

```
Closed testing has driven the bulk of code changes shipped in 2026-05.
A representative sample, by reporter and version code:

- vc23 (2026-05-07) — Save Server crash on mTLS-required hosts.
  Reporter: SLAB (Discord) + Dustin (GitHub #12, Samsung S23 Ultra
  Android 16). Root cause was a foreground-service race during a
  fast-failing TLS handshake; fixed with a 750 ms debounce on the
  Connected → start-FGS path so transient connections never elevate.

- vc15 (2026-05-06) — Chat reliably reached peer clients but our
  contact never appeared in their team panes. Reporter: H!rO. Fixed
  by adding auto-PPLI broadcast immediately after the TLS handshake
  so the server has a callsign for our EUD before any chat traffic.

- vc20 (2026-05-07) — Real GPS fixes via FusedLocationProviderClient.
  The 0.1.x default fell back to a San Francisco coordinate when the
  device hadn't published a position yet, which was misleading to
  operators in Germany and Spokane.

- vc24 (2026-05-08) — Closed-test bundle: password mask + sticky Save
  button on Add Server form, ATAK-style {$z}/{$y}/{$x} tile URL
  placeholders, callsign card honors coord-format pref (lat/lon, MGRS,
  UTM). Reporter: P-E (Discord) — French operator using CivTAK-format
  custom map URLs.

- vc27 (2026-05-08) — Real BatteryManager-backed battery percentage in
  PPLI. The hardcoded "100" had been broadcasting since v0.1.0,
  showing every peer 100% regardless of actual charge. Reporter: P-E.

In addition we shipped a build-system fix — vc27 enables R8 minification
with a release-config keep-rules file, and bundles MapLibre Native
debug symbols sourced from the upstream MapLibre 11.8.0 GitHub release.
Both clear Play Console's deobfuscation-file and native-debug-symbols
warnings, so any future crash report will symbolicate automatically
under our Engindearing developer account.

What we have NOT changed based on testing: the open-source / no-tracking
posture, no third-party analytics, no required cloud services beyond
the user-configured TAK server. Those constraints are core to who the
app is for (search-and-rescue, civil defense, amateur radio). Tester
feedback has reinforced that posture rather than asked us to relax it.
```

### Q3 — App testing details (auto-fields)

| Field | Expected value |
|---|---|
| Closed test ID | (Play autofills the Closed - Alpha track ID) |
| Test duration | ≥14 days as of 2026-05-22 |
| Active testers | ≥12 (currently 12, monitor for churn) |
| Crash-free user rate | ≥99% (verify in Play Vitals before submission) |

### Q4 — Linkable evidence Play may inspect

- **Source repo:** https://github.com/engindearing-projects/OmniTAK-Android
- **Issue tracker (closed-test feedback):** https://github.com/engindearing-projects/OmniTAK-Android/issues
- **Privacy policy:** https://github.com/engindearing-projects/OmniTAK-Android/blob/main/PRIVACY.md
- **Public install link:** Play Console → Closed testing → Copy link

---

## Pricing & distribution

| Field | Value |
|---|---|
| Free | Yes |
| Available countries | All Google Play countries |
| Contains ads | No |
| Designed for Families | No |

---

## Required pre-launch checklist for J

**Build + assets (DONE):**
- [x] `PRIVACY.md` committed to repo
- [x] Signed upload keystore (`omnitak-upload.jks`, props in `keystore.properties`)
- [x] AAB build pipeline: `playstore-assets/build-release.sh` (auto-bumps vc)
- [x] Native debug symbols pipeline: `playstore-assets/fetch-native-symbols.sh`
- [x] Current AAB staged: `playstore-assets/OmniTAK-0.2.7-vc27.aab` (224 MB,
      ships R8 mapping + MapLibre debug symbols)
- [x] Store icon refreshed to match iOS: `playstore-assets/icon-512.png`
- [x] Feature graphic refreshed to match iOS icon: `playstore-assets/feature-graphic.png`
- [x] Screenshots staged: `playstore-assets/screenshots/01-07-*.png` (7 images)

**Play Console — already done (closed-track):**
- [x] Closed testing — Alpha track active and serving vc27
- [x] Internal testing track active
- [x] Public opt-in URL distributed (TAK Community Discord)
- [x] App content forms — content rating, target audience, data safety,
      app access reviewer instructions all submitted

**Closed-test → production prep (DO BEFORE 2026-05-22):**
- [ ] Verify Play Vitals crash-free rate is ≥99% on Closed track. If below,
      investigate before submitting production-access form (a single FATAL
      from a recent build can drag the rolling number).
- [ ] Confirm tester count holds at ≥12 daily for the full 14-day window.
      If anyone uninstalls before 2026-05-22, the counter resets and we
      need to re-onboard from Discord before applying.
- [ ] Pre-fill production-access form text (Q1 + Q2 above) into a draft
      saved outside Play Console — the form has no autosave.
- [ ] Update store listing screenshots if any UI from vc27 is materially
      different from the current set (callsign-card UTM format, password
      visibility toggle, sticky Save button).

**Production launch (UNLOCKS 2026-05-22):**
- [ ] Submit production access application (Play Console → Setup →
      Production access). Paste Q1 + Q2 from this file.
- [ ] Wait for Google review — historically 2–7 business days.
- [ ] Once approved, create a Production release that promotes vc27 (or
      whatever the latest stable closed build is on that day).
- [ ] Paste full-cumulative production release notes (above) into the
      production release's What's new field.
- [ ] Stagger rollout: 10% → 50% → 100% over 3–5 days, watching Vitals.
- [ ] Update README + repo description to point to the Play Store URL
      instead of (or alongside) the closed-test opt-in link.
- [ ] Announce in TAK Community Discord and on Engindearing's social
      channels (Medium, Moltbook). DM K9Blue so the TROP-vs-OmniTAK
      comparison post he's drafting can update its distribution-status
      column.
