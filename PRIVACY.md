# OmniTAK Mobile — Privacy Policy

_Last updated: 2026-04-25_

OmniTAK Mobile ("the app") is an open-source TAK (Team Awareness Kit) client
published by Engindearing ("we", "us"). This document describes what data the
app handles and how it is treated.

## TL;DR

- We do not collect, store, or transmit any of your data to Engindearing.
- The app contains no analytics SDKs, no crash-reporting SDKs, and no
  third-party trackers.
- All network traffic from the app goes only to TAK servers, ADS-B providers,
  or Meshtastic radios that **you** configure.
- The full source code is published under Apache 2.0 and can be audited at
  <https://github.com/engindearing-projects/OmniTAK-Android>.

## What the app stores on your device

- **TAK server profiles** (name, host, port, transport, certificate references)
- **User preferences** (callsign, theme, layer visibility, last-selected basemap)
- **Client certificates** for mTLS, stored in the Android Keystore
- **Cached map tiles** (standard MapLibre tile cache for offline performance)

This data lives only on your device. Uninstalling the app removes all of it.

## What the app sends over the network

The app only initiates outbound connections to endpoints you configure:

| Endpoint type | What is sent | When |
|---|---|---|
| TAK Server (you configure) | CoT messages: your position (PPLI), markers, chat messages, drawings | While connected to a server you configured |
| ADS-B provider (you configure) | Aircraft data requests | While the ADS-B layer is active |
| Meshtastic radio (you configure) | Mesh packets per the Meshtastic protocol | While connected to a radio you configured |
| Map tile servers (defaults: OpenStreetMap, CartoDB) | Map tile requests for the rendered viewport | While the map is visible |

We — Engindearing — operate none of these endpoints. We never receive any of
your data.

## Permissions the app requests

| Permission | Purpose |
|---|---|
| `INTERNET` | Connecting to TAK servers, ADS-B providers, Meshtastic radios, and map tile servers you configure |
| `ACCESS_NETWORK_STATE` | Detecting connectivity changes to manage server reconnects |
| `ACCESS_FINE_LOCATION` | Reporting your position to your TAK server (PPLI) and powering GPS-aware tools (range, bearing, measurement) |
| `ACCESS_COARSE_LOCATION` | Fallback when fine location is denied or unavailable |

The app does not request access to contacts, photos, microphone, camera,
calendar, SMS, or call logs.

## Children

The app is intended for adult users involved in tactical, search-and-rescue,
or outdoor operations. It is not directed to children under 13, and we do not
knowingly handle any data from children under 13.

## Open source

OmniTAK Mobile is licensed under Apache 2.0. The full source — including all
network code — is publicly auditable at:

<https://github.com/engindearing-projects/OmniTAK-Android>

## Contact

Privacy questions, security disclosures, or general inquiries:

- **Email**: j@engindearing.soy
- **Security**: see [SECURITY.md](SECURITY.md) for responsible disclosure
- **Issues**: <https://github.com/engindearing-projects/OmniTAK-Android/issues>

## Changes to this policy

If we change this policy, the updated version will be committed to the repo
with the new `Last updated` date. The git history is the authoritative log of
changes.
