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
"CIV" edition from tak.gov, or the open-source FreeTAKServer. OmniTAK does not
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

## What's new (≤500 chars) — for v0.1.0 launch

```
First public release. Tactical dark map (CartoDB Dark Matter), Liquid Glass
bottom navigation, long-press radial menu with quick draw + layers, PPLI
self-position card with hide-from-layers toggle, ATAK-style bullseye self
marker, zoom controls, multi-server TAK connectivity, Meshtastic TCP, ADS-B
overlay scaffolding. Apache 2.0, source on GitHub. Bring your own TAK Server.
```

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

> "Is all or part of your app restricted based on log-in credentials?" — **Yes**

Provide tester credentials path:

> "OmniTAK requires the user to have access to a TAK Server. Reviewers can use
> the public FreeTAKServer demo at `demo.freetakteam.org:8089` (no certificate
> required for TCP) or stand up their own. The app itself has no log-in. All
> features are usable from first launch; only data exchange requires a server
> the reviewer brings."

---

## Production release notes (for the first AAB upload)

Version: `0.1.0` · Version code: `1`

```
First public release of OmniTAK Mobile for Android.

Highlights:
- Tactical dark basemap (CartoDB Dark Matter) by default
- Floating Liquid Glass bottom-tab navigation (Map / Chat / Servers / Mesh / Settings)
- Long-press radial menu with quick draw and layers controls
- PPLI self-position card with hide-from-layers toggle
- ATAK-style tactical bullseye self-marker
- Zoom controls and locator FAB
- Multi-server TAK connectivity (TCP / TLS / mTLS)
- Meshtastic TCP integration
- ADS-B traffic overlay scaffolding
- Material 3 dark theme with shared design tokens

Apache 2.0 licensed. Source: https://github.com/engindearing-projects/OmniTAK-Android
```

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

- [ ] Add `PRIVACY.md` to the repo (text below)
- [ ] Create signed upload keystore (or copy the existing one from the private monorepo)
- [ ] Build signed AAB: `./gradlew bundleRelease`
- [ ] Upload screenshots from `playstore-assets/screenshots/` (7 images)
- [ ] Upload `playstore-assets/icon-512.png` as the app icon
- [ ] Upload `playstore-assets/feature-graphic.png` as the feature graphic
- [ ] Paste copy from this file into Play Console listing form
- [ ] Fill out content rating questionnaire (anticipated answers above)
- [ ] Fill out data safety form (anticipated answers above)
- [ ] Submit for internal testing first (recommended) before production
