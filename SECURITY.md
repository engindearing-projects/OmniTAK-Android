# Security Policy

## Reporting a vulnerability

If you discover a security vulnerability in OmniTAK-Android, please **do not** open a public issue.

Email **j@engindearing.soy** with:

- A description of the vulnerability
- Steps to reproduce, or a proof-of-concept
- Affected version(s) — git commit hash or App Store build number
- Whether you'd like public credit if a fix is published

We aim to respond within 5 business days. Coordinated disclosure timelines are negotiated case-by-case but generally do not exceed 90 days.

## Scope

In scope:

- The OmniTAK-Android Kotlin application source in this repository
- TAK / CoT protocol handling, certificate management, Android Keystore usage

Out of scope:

- Vulnerabilities in third-party dependencies (report upstream — MapLibre, AndroidX, kotlinx.serialization)
- Issues in operator-deployed TAK Servers
- Social engineering of OmniTAK contributors

## Supported versions

Only the latest tagged release is actively supported. Backports may be considered for high-severity issues.
