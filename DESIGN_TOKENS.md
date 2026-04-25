# OmniTAK design tokens

Single source of truth for color, typography, spacing, and shape tokens used across both [OmniTAK-iOS](https://github.com/engindearing-projects/OmniTAK-iOS) and [OmniTAK-Android](https://github.com/engindearing-projects/OmniTAK-Android). This file lives identically in both repos.

When you add a new visual element, **reach for a token from this file first**. If you need a value that isn't here, propose adding it as a token rather than inlining a hex.

---

## Color tokens

All colors are sRGB. Hex values are the source of truth; per-platform constants below are the canonical mapping.

### Surface palette — tactical dark theme

| Token | Hex | iOS | Android | Purpose |
|---|---|---|---|---|
| `surface.bg` | `#0A1628` | `Color.tacticalBackground` | `TacticalBackground` | App background, deep navy |
| `surface.bg.translucent85` | `#0A1628 @ 0.85α` | `tacticalBackground.opacity(0.85)` | `TacticalBackground.copy(alpha = 0.85f)` | Status bar, panel chrome |
| `surface.elevated` | `#162033` | `Color.tacticalSurface` | `TacticalSurface` | Cards, sheets, dialogs |
| `surface.glass` | `#0F1115 @ 0.80α` | n/a (Liquid Glass native) | `Color(0xCC0F1115)` (LiquidGlassNavBar) | Floating capsule nav, drawers |
| `surface.scrim` | `#000000 @ 0.50α` | `Color.black.opacity(0.5)` | `Color.Black.copy(alpha = 0.5f)` | Status bar dim, modal scrim |

### Brand & accent

| Token | Hex | iOS | Android | Purpose |
|---|---|---|---|---|
| `accent.primary` | `#4ADE80` | `Color.tacticalAccent` | `TacticalAccent` | Active state, logo green, CTA |
| `accent.primary.dark` | `#16A34A` | n/a | `TacticalPrimary` | Pressed state, deeper accent |
| `accent.warning` | `#FFFC00` | `Color(red:1.0, green:0.988, blue:0.0)` | n/a yet | Caution, "Done" button (iOS legacy) |

### Tactical / affiliation palette

Mirrors ATAK 2525C affiliation colors. Use these for marker tints, callout chips, and per-affiliation UI states.

| Token | Hex | Affiliation |
|---|---|---|
| `affil.friendly` | `#2196F3` | Blue — friendly forces |
| `affil.hostile` | `#F44336` | Red — hostile / threat |
| `affil.neutral` | `#FFC107` | Yellow — neutral / unverified |
| `affil.unknown` | `#9C27B0` | Purple — unknown affiliation |

### Status / network counters

| Token | Hex | Use |
|---|---|---|
| `counter.down` | `#2196F3` (light blue) | Inbound message count `↓` |
| `counter.up` | `#FFA000` (amber) | Outbound message count `↑` |
| `state.connected` | `#4CAF50` | Connection live |
| `state.disconnected` | `#F44336` | Connection failed |
| `state.connecting` | `#FFC107` | Reconnecting |

### Bottom-nav tab tints (Liquid Glass)

Per-tab brand color so the nav row reads as a tactical traffic-stop.

| Tab | Hex | Symbolism |
|---|---|---|
| Map | `#4FA8FF` | Map cyan-blue |
| Chat | `#34C759` | Comm green |
| Servers | `#5AC8FA` | Network sky-blue |
| Mesh | `#FF9F0A` | Radio amber |
| Settings | `#8E8E93` | Neutral gray |

### Foreground text

| Token | Hex | Use |
|---|---|---|
| `text.primary` | `#FFFFFF` | Headings, primary labels |
| `text.muted` | `#B0B0B0` | Secondary labels |
| `text.subtle` | `#FFFFFF @ 0.70α` | Tab labels, hint text |
| `text.disabled` | `#FFFFFF @ 0.40α` | Disabled controls |

---

## Typography

Both platforms use the system default font (SF Pro on iOS, Roboto on Android) for primary UI text. **Monospace digits** (`UIFont.monospacedDigitSystemFont` / `FontFamily.Monospace`) for any number that should align across rows: status-bar counters, accuracy badge, time, PPLI card metrics.

| Token | Size | Weight | Use |
|---|---|---|---|
| `text.title.lg` | 28sp / 28pt | Bold | Section headings |
| `text.title.md` | 17sp / 17pt | Semibold | Sheet titles, list section heads |
| `text.body` | 15sp / 15pt | Regular | Primary body text |
| `text.label` | 13sp / 13pt | Medium | Tab labels, button captions |
| `text.caption` | 11sp / 11pt | Medium | Status bar values, timestamps |
| `text.mono.sm` | 12sp / 12pt | Regular (mono) | PPLI card lines |

---

## Spacing scale

4dp / 4pt base grid.

| Token | Value | Use |
|---|---|---|
| `space.1` | 4dp | Tight pairs (icon ↔ label) |
| `space.2` | 8dp | Default padding |
| `space.3` | 12dp | Card padding, row gap |
| `space.4` | 16dp | Edge insets |
| `space.5` | 24dp | Section breathing room |
| `space.6` | 32dp | Sheet header padding |

---

## Shape

| Token | Radius | Use |
|---|---|---|
| `shape.pill` | full circle | LocationFAB, halos, switches |
| `shape.lg` | 34dp | LiquidGlass nav capsule |
| `shape.md` | 16dp | Sheets, cards |
| `shape.sm` | 8dp | Buttons, chips |
| `shape.xs` | 6dp | PPLI card |

---

## Elevation

| Token | iOS | Android | Use |
|---|---|---|---|
| `elevation.floating` | shadow radius 20, opacity 0.45 | `Modifier.shadow(16.dp)` | Liquid Glass nav, FABs |
| `elevation.sheet` | system default | `Modifier.shadow(8.dp)` | Bottom sheets, side panels |
| `elevation.tooltip` | shadow radius 4 | `Modifier.shadow(2.dp)` | Toasts, callouts |

---

## How to apply on each platform

### iOS

Tracked under [GAP-061](PARITY.md). Add a `Color+Tactical.swift` extension with a static `palette` namespace; replace inline `Color(hex:...)` calls site-by-site. Don't break existing screens in one PR — refactor by feature.

### Android

Tracked under [GAP-062](PARITY.md). Existing `ui/theme/Color.kt` already holds the partial palette; expand it to cover every token in this file. Mirror new entries in `app_assets/android/values/colors.xml` so the res-xml styles stay in sync.

### Both

When adding a token: **update this file first**, then propagate to both platforms in paired commits referencing the gap ID.

---

## See also

- [PARITY.md](PARITY.md) — full parity tracker
- iOS color usage: `OmniTAKMobile/**/*.swift`
- Android color usage: `app/src/main/kotlin/**/ui/theme/`, `app_assets/android/values/colors.xml`
