All of @P-E's feedback shipped in 0.4.0 vc39, going up to closed testing now.

**5/8 batch:**
- Password masked with dots + visibility toggle; Save Server pinned to bottom of the form
- Custom map URLs: silent failure was `&amp;` reaching MapLibre as literal text. URL normalizer now decodes HTML entities (single + double-encoded). French IGN WMTS and ArcGIS REST `tile/{$z}/{$y}/{$x}` URLs both work
- Coord format propagation: marker drop sheet and Contacts panel were hardcoding decimal lat/lon. Both now read Settings (UTM, MGRS, DMS, Lat/Lon)

**5/11 batch:**
- Add Server form display bug: double IME-padding collapsed the form when the keyboard opened. Fixed
- Edit existing server: pencil icon on each row opens the form pre-filled
- Top-left header stale after switching servers + bright vs dark green dot: same root cause, fixed by the multi-server refactor below
- ATAK on a non-default server not seeing the OmniTAK EUD: ServerManager was single-connection with switching. Refactored to true multi-server — every enabled server holds its own live socket, PPLI fans out to all of them, Servers tab shows real per-row state

iOS was already multi-server, which is why @P-E's iOS friend worked.

vc39 lands in tester slots ~30 min after Play processes it.
