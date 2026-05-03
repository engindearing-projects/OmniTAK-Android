# Release build — signed AAB for Play Store

## Current build to upload

- **Bundle**: `playstore-assets/OmniTAK-0.1.0-vc3.aab` (26.7 MB) ← UPLOAD THIS
- **Built**: 2026-04-26
- **Version name**: 0.1.0
- **Version code**: 3
- **Signing key**: `omnitak-upload.jks` (alias `upload`, RSA 2048, SHA256withRSA)
- **Cert subject**: `CN=J Wylie, OU=OmniTAK Mobile, O=Engindearing, L=Spokane, ST=Washington, C=US`

## Already-published versionCodes (do NOT reuse)

| versionCode | versionName | Track | Status |
|---|---|---|---|
| 1 | 0.1.0 | Closed (release "2.10.4") | Available to selected testers |
| 2 | 0.1.0 | Internal (release "0.1.0 — clean-room launch") | Available to internal testers |
| 2 | 0.1.0 | Closed (release "0.1.0 — clean-room launch") | In review |

**Next versionCode to use: 4** (vc3 is staged but not yet uploaded).

## Quick rebuild for the next iteration

```bash
./playstore-assets/build-release.sh
```

That script bumps `versionCode` by 1, runs `bundleRelease`, and stages the
output at `playstore-assets/OmniTAK-<versionName>-vc<N>.aab` so the filename
tells you exactly which versionCode is in the bundle.

## Reproducing the build

Both `keystore.properties` and `omnitak-upload.jks` must live at the repo root.
Both files are gitignored.

```bash
cd ~/Projects/OmniTAK-Android
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Verifying the signature

```bash
jarsigner -verify -verbose:summary \
  app/build/outputs/bundle/release/app-release.aab
```

Expect:
- `jar verified.`
- Signed by the `J Wylie / OmniTAK Mobile / Engindearing` cert above

## Bumping versions

In `app/build.gradle.kts`:
```kotlin
defaultConfig {
    versionCode = N + 1
    versionName = "0.X.Y"
}
```

Play Console rejects an upload whose `versionCode` is ≤ the highest one already
on any track. Always bump `versionCode` for every upload.

## Keystore source of truth

The canonical upload key lives in the **private monorepo** at:
```
~/Projects/omniTAK-Mobile/apps/omnitak_android/omnitak-upload.jks
~/Projects/omniTAK-Mobile/apps/omnitak_android/keystore.properties
```

A copy lives locally in this OSS repo (gitignored) for convenience. If anything
ever drifts, the monorepo is the source of truth.

> **Critical**: Do not lose this keystore. Once Play Console accepts it as
> the upload key, every future update to the app must be signed with it (or
> with a key Google has rotated to via Play App Signing). If lost, you
> effectively can't update the app under the same listing.

A backup of the keystore should live somewhere off-disk (encrypted cloud drive,
1Password, Bitwarden file attachment) before submitting to Play Console.

## Play Console upload flow

1. Open Play Console → Create app (if first time) or pick the OmniTAK app
2. **Production** → **Create new release**
3. Upload `playstore-assets/OmniTAK-v0.1.0.aab`
4. Paste release notes from `listing.md` § "Production release notes"
5. Save → Review → Roll out to production (after listing + content rating + data safety are filled in — see `listing.md` checklist)
