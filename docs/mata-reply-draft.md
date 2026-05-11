# Discord reply to mata — 0.4.0 / 2.16.0

Two messages. Send #1 first; #2 only if you want to elaborate on the
deeper architecture points. Each fits under Discord's 2000-char limit.

---

## Message 1 (the receipts)

hey, follow-up on everything we talked about today. both platforms are at parity — android **0.4.0** and ios **2.16.0** going up now.

**preference list** → https://github.com/engindearing-projects/OmniTAK-Android/blob/main/PREFERENCES.md (mirror in iOS repo). every row lists ATAK aliases (`locationCallsign`, `dispatchLocationCotInterval`, `locationReportingInterval`, etc.) so a config bundle generated for ATAK works against OmniTAK unchanged.

**`tak://` URL scheme** — `/connect`, `/import?url=…`, `/preference?keyN=…`, and `/enroll?host=…&username=…&token=…` all work on both platforms now. `/enroll` does the full CSR flow: generates RSA-2048 key, builds PKCS#10 CSR, POSTs to `:8446` with Bearer + Basic + token-query fallbacks, assembles PKCS#12, auto-connects. data-package `.zip` import (server.pref + .p12 + MANIFEST) works via Files (iOS) / share intent or `<app>/files/import/` (Android).

**in-app reporting intervals** — the exact thing you flagged. new `pliIntervalSecs` pref (5–600s). pushable via `tak://preference` or `configBundleUrl`. accepts ATAK aliases so a portal targeting ATAK reaches us too. broadcaster re-reads each tick — no restart.

**server-driven config (your "would really set this apart" ask)** — new `configBundleUrl` pref. point at one URL; every launch fetches + applies either a `.zip` data package or `text/plain` of `tak://com.atakmap.app/preference?…` URLs (one per line, `#` comments ok). edit the file server-side as the event evolves, every EUD picks up changes at next launch. deferred deliberately: periodic background polling (doze + BGTask = event-day battery footgun). pull-on-launch covers the operator pattern.

netherlands country: should be live on the new push. tell me if play 404s.

-engie

---

## Message 2 (the architecture bits — optional follow-up)

couple of related ones from your 12:07 message:

**TAK_TRACKER "see yourself twice"** — fixed. `hideSelfFromMeshContacts` pref (default on) drops any mesh node whose callsign matches your TAK callsign. and the two names stay aligned: your TAK callsign auto-syncs to the connected mesh node's owner name (`set_owner` AdminMessage) every time it changes or a new radio comes online. so the dedup just works, even swapping radios mid-event. that's step 1 toward your unified-identity vision; step 2 (shared UID convention) is next.

**true multi-server** — bigger architectural one, surfaced by another tester. the listing advertises multi-server but the old impl held a single connection and switched. now every enabled server holds its own live socket in parallel, PPLI fans out to all, servers tab shows per-row state. edit-existing-server pencil ships too — port or cert edits reconnect the affected socket on save.

genuinely useful set of inputs — half of 0.4.0 is yours. keep them coming.
