# OmniTAK Quick Start: for first-time operators

This guide walks you from "I just installed the app" to "I can see my team, share my location, and send a message." It assumes no prior experience with TAK, CoT, or military-style mapping software. If you can use a smartphone messaging app, you can use OmniTAK.

**Reading time:** about 10 minutes. **Time to operational on your device:** about 5 minutes once your administrator has prepared the configuration file you need.

---

## What OmniTAK does for you

OmniTAK is a shared map and messaging tool for teams who need to know where each other are in real time. Think of it as a private group chat layered on top of a live map. When you open the app, you see:

- **Where you are**: a marker for your own position, drawn from your phone's GPS.
- **Where your teammates are**: markers for everyone else on your channel, updated as they move.
- **A shared chat**: text messages between you and your team.
- **Map notes**: markers, lines, and shapes anyone on the team can drop on the map for everyone else to see.

OmniTAK speaks the same language ("Cursor-on-Target") as other TAK clients, so your team can mix OmniTAK on Android, OmniTAK on iPhone, and other TAK clients on the same channel. Everyone sees the same picture.

---

## Before your first day: what your administrator needs to give you

OmniTAK is a client. To use it, your team needs a **TAK Server** running somewhere, usually inside your agency's network or a service your administrator hosts. Your administrator will give you one of two things to get connected:

- **A data package**: a single `.zip` file (typically named something like `team-mtls.zip`) that contains the server address and your personal login credentials. This is the recommended path. It removes every technical step.
- **A server address and login**: a hostname (`tak.example.gov`), a port number (usually `8089`), and either a password or a personal certificate file.

If you do not have either of these, ask your administrator before you start. Without one, the app will install but you cannot connect to your team.

You should also choose a **callsign** before you start, this is the short identifier that will appear next to your marker on every teammate's map. Most teams use a convention like first initial plus last name (`J-WYLIE`), a unit number (`UNIT-12`), or an animal name (`FALCON-3`). Eight characters or fewer is best so it fits on the map.

---

## Step 1: Install the app

1. Open the link your administrator sent you, or go to the **Google Play Store** and search for "OmniTAK Mobile."
2. Tap **Install**. The app is about 60 MB.
3. When prompted, allow:
   - **Location**: required for your own position to appear on the map.
   - **Notifications**: used for the persistent "connected to your team" indicator.
   - **Bluetooth** (optional): only needed if your team uses Meshtastic radios.

If you do not allow Location, the map will work but your teammates will not see you and you will not be able to share your position.

---

## Step 2: First-time setup (about 90 seconds)

Open the app. You will land on the map screen. The top-right menu (the three lines) has a **Settings** option. Open it.

1. **Set your callsign.** Tap the Callsign field, type your callsign in capitals, tap Save. The marker on the map updates immediately.
2. **Pick your team color.** Most teams use blue. If your administrator told you a color, use that, otherwise blue is a safe default.
3. **Choose your coordinate format.** Three common choices:
   - **Lat/Lon (decimal)**: for everyday use, easiest to read aloud over the radio.
   - **MGRS**: for military or rescue operations that use grid coordinates.
   - **Lat/Lon (DMS)**: degrees / minutes / seconds, the format on most paper maps.
   The map and HUD will show coordinates in whichever format you pick. You can change it later.
4. **Pick your distance units.** Metric (meters / kilometers) or Imperial (feet / miles). Whichever your team uses on the radio.

Back out of Settings. You are ready to connect.

---

## Step 3: Connect to your team's server

### If your administrator gave you a data package (the easy path)

1. Save the `.zip` file your administrator sent you to your phone's **Downloads** folder. (Tap the email attachment, choose "Save," pick Downloads.)
2. Open OmniTAK. Tap the **Servers** tab in the bottom navigation bar.
3. Tap **Import data package** and pick the `.zip` file.
4. The app will read the file, configure the server, and connect within a few seconds. You will see a green dot in the top-left of the screen and the server name (something like "TAK Bronx") next to it.

That is the entire setup. You are connected.

### If your administrator gave you an address + login

1. Tap **Servers** in the bottom navigation bar.
2. Tap **+** (top right) to add a server.
3. Fill in:
   - **Name:** anything you want, e.g., "Bronx Ops."
   - **Address:** the hostname your administrator gave you.
   - **Port:** usually `8089`.
   - **Username / password** OR **Client certificate**: whichever your administrator provided.
4. Tap **Save & Connect**.

You will see the green connection dot when the handshake completes.

---

## Reading the map screen

After you connect, the map screen has four areas you should know:

- **Top status bar.** Shows your connection state on the left (green dot = connected, red = offline) and the server name. The arrows `↓` and `↑` count CoT events received and sent: when these tick up, your team's data is flowing.
- **Map.** Pinch to zoom, drag to pan. Your own position is a colored marker (your team color); teammates appear as markers in their own team colors with their callsigns next to them.
- **Self-position card (lower right corner).** Shows your callsign, your live coordinates, your altitude, your speed, and your GPS accuracy. If this card says "Acquiring fix..." for more than 30 seconds, step outside or near a window: GPS needs sky visibility.
- **Bottom navigation bar.** Five tabs: **Map**, **Chat**, **Servers**, **Mesh** (Meshtastic radios: skip if your team does not use them), and **Settings**.

---

## Common task: share your live location

This is automatic once you are connected. As soon as the green dot lights up in the status bar, OmniTAK begins broadcasting your position to your team every 30 seconds. There is no button to push. Your teammates will see you appear on their map within a minute.

If you want to stop sharing your position (for a break or off-duty time), open the app and tap **Disconnect** under the Servers tab. Reconnect when you are ready to be visible again.

---

## Common task: see your team

Open the **Map** tab. Every teammate currently connected to the same TAK server will appear as a marker, in their team's color, with their callsign next to it. Tap any teammate's marker to see:

- Their full callsign
- Their last reported position (latitude / longitude or grid)
- How long ago they were last heard from
- How far they are from your current location

If a teammate's marker has a faded color, it means OmniTAK has not heard from them recently, they may have lost signal, gone out of range, or backgrounded the app on a device that does not support background TLS connections.

To **center the map on a specific teammate**, tap their marker and choose **Go to**. To **center the map on yourself**, tap the crosshair button on the left side of the map.

---

## Common task: send a message

OmniTAK has two kinds of messages: **broadcast** (everyone on the channel sees it) and **direct** (only one teammate sees it).

### Broadcast a message to the whole team

1. Tap the **Chat** tab in the bottom navigation.
2. The **All Chat Rooms** room is at the top, tap it.
3. Type your message in the field at the bottom and tap Send.

Everyone connected to the same TAK server will see your message immediately.

### Send a direct message to one teammate

1. Tap the **Chat** tab.
2. Find your teammate's callsign in the list and tap it. (If you do not see them, they may not be connected yet.)
3. Type your message and tap Send.

Direct messages are private to the two of you.

---

## Common task: drop a marker on the map

A marker is a labeled point on the map that the whole team can see, useful for "meet here," "victim located," "vehicle parked at this corner."

1. Long-press on any spot on the **Map** tab. A radial menu appears.
2. Tap the marker icon (a teardrop pin).
3. Choose the marker type:
   - **Friend** (green): friendly position.
   - **Hostile** (red): adversary position.
   - **Neutral** (yellow): non-combatant or unknown.
   - **Unknown** (purple): track requiring identification.
4. Optionally type a label, then tap Save.

The marker appears for everyone on the channel within a few seconds. Anyone on the team can long-press it to edit or delete it.

---

## What the persistent notification means

While OmniTAK is holding your team connection alive in the background, you will see a small notification in your phone's notification shade that says:

> **OmniTAK connected: Holding TLS to <your server name>**

This is **expected and required**. Without that notification, Android may kill your team connection within ten seconds of you switching to another app, and your teammates would lose sight of you. The notification consumes very little battery (less than 1 percent per hour on most devices). Do **not** swipe it away, if you do, the connection drops.

If you want OmniTAK to stop using battery and connection entirely, open the app and tap **Disconnect** under Servers. The notification will go away. Reconnect when you need to be visible again.

---

## Five-minute settings tour

You can run OmniTAK perfectly well with the defaults. These settings let you tune the experience:

| Setting | What it does | When to change it |
| --- | --- | --- |
| **Callsign** | Your name on every teammate's map | When you change roles or shifts |
| **Team color** | The color of your marker | When your team standardizes one |
| **Coordinate format** | How positions display on screen | When operating with paper maps (use MGRS) or Lat/Lon |
| **Distance units** | Metric vs Imperial | Match your team's radio conventions |
| **Map provider** | Which map tiles you see (street / satellite / topo) | When you need terrain detail or satellite imagery |
| **Auto-publish mesh to TAK** | Whether Meshtastic-only contacts appear on the TAK map | Only relevant if your team uses Meshtastic radios |
| **Mesh nodes layer visible** | Show or hide Meshtastic-origin contacts | Same as above |
| **Follow me** | Map auto-centers as you move | When driving or walking; off when you want to study a fixed area |

---

## Troubleshooting: the four problems we see most

**1. The app installed but I see no map / no location.**
Open Settings → Apps → OmniTAK → Permissions and confirm Location is allowed. Restart the app. If you are indoors, step near a window, GPS needs sky visibility.

**2. The status bar shows a red dot, I am not connected.**
Check that your data package was imported successfully under the Servers tab. If you see your server listed, tap it and choose Connect. If the server is not listed, re-import your data package or ask your administrator to send a fresh one.

**3. I can see my teammates on the map but they cannot see me.**
This usually means your phone is denying OmniTAK's background connection. Open Settings → Apps → OmniTAK → Battery and switch from "Optimized" to "Unrestricted." Also confirm the persistent notification is still in your notification shade, if it is gone, reopen the app to restart the connection.

**4. The persistent notification keeps reappearing, can I turn it off?**
No. Android requires the notification while OmniTAK is holding your team connection alive. If you want the notification to stop, tap Disconnect under Servers. The notification clears automatically and you go offline. Reconnect when you are ready to be visible again.

---

## Getting help

- **Bug reports and feature requests:** open an issue at [github.com/engindearing-projects/OmniTAK-Android/issues](https://github.com/engindearing-projects/OmniTAK-Android/issues). Include your phone model and what you were doing when the problem started.
- **Direct support for evaluating teams:** email j@engindearing.soy. Live demos and team onboarding sessions are available.
- **TAK ecosystem documentation:** [tak.gov](https://tak.gov) hosts the official TAK Server documentation if you are standing up your own server.

---

## A note for administrators

Distribute a **data package** rather than a server address + credentials whenever possible. It removes every step where a non-technical user could mistype something. The data package format is a standard `.zip` containing `manifest.xml`, the server's certificate authority, and (optionally) a per-user client certificate. Most TAK Server distributions can generate these for you.

If you are evaluating OmniTAK for an agency rollout and would like a 30-minute walkthrough or a custom training package for your specific deployment, reach out at j@engindearing.soy.
