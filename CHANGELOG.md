# Changelog

WebDM 1.0.0 is based on AB Download Manager 1.10.4; the earlier history is in
[docs/AB-DOWNLOAD-MANAGER-CHANGELOG.md](docs/AB-DOWNLOAD-MANAGER-CHANGELOG.md).

## Unreleased

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## 1.0.0

First release of WebDM (Web Download Manager), a Windows x64 fork of AB Download Manager 1.10.4.

### Added

- Video streams: HLS with a quality list, AES-128 encryption, fMP4, live recording (kept across restarts) and
  merging into one file; streams rebuilt from their segments when a page has no playlist
- Separate video + audio files (DASH-style players such as Facebook) downloaded in parallel parts and merged
- Video sites through yt-dlp (YouTube, Facebook, Instagram, X, TikTok, Vimeo, ...) with a quality list
- ffmpeg (LGPL) and yt-dlp ship with the installer; Settings > Tools can update them
- Drag and drop: drop links or .txt files of links on the main window or on an optional floating drop target
- Local API: GET /capabilities and the new "segments", "av" and "page" job types (see REST-API.yml)

### Changed

- New name, logo, app ID, data folder (.webdm), integration port (15515) and native-messaging host (com.webdm.app),
  so WebDM can run next to AB Download Manager
- Windows x64 only; the browser extension (WebDM Integration) is for Chromium browsers (Chrome, Edge, Brave, Opera)
- A silent add renames a duplicate to "name (1).ext" instead of skipping it

### Fixed

- The uninstaller removes the autostart entry and the browser native-messaging registrations
- Drops from Explorer are copies (a dropped file is never deleted afterwards)
