package soy.engindearing.omnitak.mobile.i18n

import soy.engindearing.omnitak.mobile.i18n.Loc.Language

/**
 * In-memory string catalogues for [Loc]. Keys mirror the iOS catalogue's
 * dotted-namespace convention (`settings.section.*`, `settings.*`) so the
 * two platforms stay legible side by side.
 *
 * v1 scope: the Settings screen — the surface that hosts the language
 * picker, and the Android counterpart to iOS's onboarding+Settings v1.
 * Other screens render English until later passes extend coverage; any
 * unkeyed string simply falls back through [Loc.t].
 *
 * Technical terms (TAK, ATAK, MGRS, UTM, OSM, FAA Remote ID, QR,
 * WMTS/XYZ) are intentionally left in English in every language, the same
 * call the iOS zh-Hant translation made.
 */
internal object LocStrings {

    fun catalogue(language: Language): Map<String, String> = when (language) {
        Language.ENGLISH -> EN
        Language.TRADITIONAL_CHINESE -> ZH_HANT
    }

    private val EN: Map<String, String> = mapOf(
        "settings.title" to "Settings",

        // Section headers (rendered uppercased by SectionHeader)
        "settings.section.identity" to "Identity",
        "settings.section.interface" to "Interface",
        "settings.section.units" to "Units",
        "settings.section.coordinates" to "Coordinates",
        "settings.section.mapTiles" to "Map tiles",
        "settings.section.selfPosition" to "Self position",
        "settings.section.droneDetection" to "Drone detection",
        "settings.section.language" to "Language",

        // Identity
        "settings.callsign" to "Callsign",
        "settings.team" to "Team",

        // Interface
        "settings.customizeToolbar" to "Customize Toolbar",
        "settings.customizeToolbar.desc" to "Build your own bottom-bar shortcuts",

        // Units
        "settings.unit.metric" to "Metric",
        "settings.unit.imperial" to "Imperial",

        // Coordinates
        "settings.coord.latlon" to "Lat/Lon",
        "settings.coord.dms" to "DMS",
        "settings.coord.mgrs" to "MGRS",
        "settings.coord.utm" to "UTM",
        "settings.coord.twd97" to "TWD97",
        // Go to Coordinate
        "coordentry.title" to "Go to Coordinate",
        "coordentry.format" to "Format",
        "coordentry.digitmode" to "Digits",
        "coordentry.input" to "Input",
        "coordentry.preview" to "Preview",
        "coordentry.dropmarker" to "Drop a marker here",
        "coordentry.easting" to "Easting",
        "coordentry.northing" to "Northing",
        "coordentry.lat" to "Latitude",
        "coordentry.lon" to "Longitude",
        "coordentry.go" to "Go",
        "common.cancel" to "Cancel",
        "coordentry.grid5.note" to "5+5 is relative to the current map area (100 km cell).",
        "coordentry.invalid" to "Invalid coordinate",
        "coordentry.enterprompt" to "Enter a coordinate to preview it.",
        "tools.gotoCoordinate" to "Go to Coordinate",
        "tools.gotoCoordinate.desc" to "Type TWD97 / MGRS / Lat-Lon — jump there, drop a marker",

        // Map tiles
        "settings.map.osm" to "OSM",
        "settings.map.satellite" to "Satellite",
        "settings.map.topo" to "Topo",
        "settings.map.custom" to "Custom",
        "settings.mapTiles.desc" to "OSM (street), Topo (OpenTopoMap), Satellite (Esri imagery), or a custom WMTS / XYZ tile URL. Picks apply immediately. Offline tile cache lands in a later release.",
        "settings.tileUrl" to "Tile URL",
        "settings.customTile.help" to "Must be an XYZ-style URL with {z}, {x}, {y} placeholders (ATAK-style {\$z}/{\$x}/{\$y} also works). WMTS endpoints from agency / private servers usually expose this. Falls back to OSM if invalid.",

        // Self position
        "settings.milStdSymbol" to "MIL-STD-2525 symbol",
        "settings.milStdSymbol.desc" to "Render your own pip as a friendly-combat ground frame (a-f-G-U-C) instead of the legacy tactical disc. Affects only the map; PPLI broadcast is unchanged.",

        // Drone detection
        "settings.faaRemoteIdScanner" to "FAA Remote ID scanner",
        "settings.faaRemoteId.desc" to "Listen for nearby drones (DJI Mavic, Skydio, Autel) broadcasting FAA Remote ID over Bluetooth. Detected drones appear on the map as unknown-air UAS contacts. Catches the Bluetooth-broadcast subset of Remote ID; WiFi-beacon broadcasts require a gy6 sensor. BLE scanning has a battery cost.",

        // Language
        "settings.appLanguage" to "App Language",
    )

    private val ZH_HANT: Map<String, String> = mapOf(
        "settings.title" to "設定",

        "settings.section.identity" to "身份",
        "settings.section.interface" to "介面",
        "settings.section.units" to "單位",
        "settings.section.coordinates" to "座標",
        "settings.section.mapTiles" to "地圖圖磚",
        "settings.section.selfPosition" to "自身位置",
        "settings.section.droneDetection" to "無人機偵測",
        "settings.section.language" to "語言",

        "settings.callsign" to "呼號",
        "settings.team" to "團隊",

        "settings.customizeToolbar" to "自訂工具列",
        "settings.customizeToolbar.desc" to "打造您自己的底部工具列捷徑",

        "settings.unit.metric" to "公制",
        "settings.unit.imperial" to "英制",

        "settings.coord.latlon" to "經緯度",
        "settings.coord.dms" to "度分秒",
        "settings.coord.mgrs" to "MGRS",
        "settings.coord.utm" to "UTM",
        "settings.coord.twd97" to "TWD97 / TM2（台灣）",
        // Go to Coordinate
        "coordentry.title" to "前往座標",
        "coordentry.format" to "格式",
        "coordentry.digitmode" to "位數",
        "coordentry.input" to "輸入",
        "coordentry.preview" to "預覽",
        "coordentry.dropmarker" to "在此放置標記",
        "coordentry.easting" to "橫座標 (E)",
        "coordentry.northing" to "縱座標 (N)",
        "coordentry.lat" to "緯度",
        "coordentry.lon" to "經度",
        "coordentry.go" to "前往",
        "common.cancel" to "取消",
        "coordentry.grid5.note" to "5+5 相對於目前地圖區域（100 公里方格）。",
        "coordentry.invalid" to "座標無效",
        "coordentry.enterprompt" to "輸入座標以預覽。",
        "tools.gotoCoordinate" to "前往座標",
        "tools.gotoCoordinate.desc" to "輸入 TWD97 / MGRS / 經緯度 — 跳至該處並放置標記",

        "settings.map.osm" to "OSM",
        "settings.map.satellite" to "衛星",
        "settings.map.topo" to "地形",
        "settings.map.custom" to "自訂",
        "settings.mapTiles.desc" to "OSM（街道）、地形（OpenTopoMap）、衛星（Esri 影像），或自訂 WMTS / XYZ 圖磚網址。選擇即時套用。離線圖磚快取將於後續版本推出。",
        "settings.tileUrl" to "圖磚網址",
        "settings.customTile.help" to "必須是包含 {z}、{x}、{y} 預留位置的 XYZ 樣式網址（ATAK 樣式的 {\$z}/{\$x}/{\$y} 亦可）。機關／私人伺服器的 WMTS 端點通常會提供此格式。若無效則回退至 OSM。",

        "settings.milStdSymbol" to "MIL-STD-2525 符號",
        "settings.milStdSymbol.desc" to "將您自身的標記繪製為友軍地面戰鬥框架（a-f-G-U-C），取代舊版戰術圓點。僅影響地圖；PPLI 廣播維持不變。",

        "settings.faaRemoteIdScanner" to "FAA Remote ID 掃描器",
        "settings.faaRemoteId.desc" to "監聽附近無人機（DJI Mavic、Skydio、Autel）透過藍芽廣播的 FAA Remote ID。偵測到的無人機將以未知空域 UAS 目標顯示在地圖上。可接收藍芽廣播的 Remote ID 子集；WiFi 訊號廣播需搭配 gy6 感測器。藍芽掃描會消耗電池。",

        "settings.appLanguage" to "應用程式語言",
    )
}
