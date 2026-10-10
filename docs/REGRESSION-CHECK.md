# WebDM regression check (Prompt 13)

Date: 2026-10-10. App `master` 416c04b2 + extension `master` 9dcee0a, checked on branch `regression-check`.
Fork points: app `1302ce8e` (AB Download Manager "improve native extractor"), extension `1871203`.

## 1. Inventory of changes since the fork

| Area | Commits | Main files | What could break |
|---|---|---|---|
| Rebrand | app 7b62bdc5, 06e7dcb0, ee0abec6, 1c6c7dba; ext e996964, a34f408, 114b5ed | settings.gradle.kts, VersionUtil.kt, shared/app/build.gradle.kts (buildConfig), AppInfo.kt, AboutPage, icons, installer art; ext manifest, messages.json, icons | data folder / app ID / port collide with AB DM; leftover AB DM texts; extension ID not trusted by the app |
| Windows-only build | app f8712f69 | build.gradle.kts, desktop/app/build.gradle.kts, publish.yml | builds needing an Android SDK; CI artifacts |
| Firefox removed | app eac85b8b; ext 7156e4a | NativeMessaging*.kt, SharedConstants.kt; ext manifest*, LinkGrabber.ts, package.json | native messaging registration for Chromium browsers |
| Extension detection: HLS / service worker | ext 47dff0e | background.ts, ContentScriptInjector.ts, DownloadLinkInterceptor.ts, MediaRegistry.ts | button missing after install/reload; worker restarts |
| Extension detection: iframes, page hook | ext aa2c666 | PageMediaReporter.ts, webdm-page-hook.js, MediaOnTab.ts | players in iframes; Referer of the frame |
| Segments (no flood, rebuilt streams) | ext a719583, fe39720; app dfecf37f, 2b80f141 | MediaOnTab.ts, SegmentUtils.ts; app HLS engine | segment flood; probing; jobs sent to old apps |
| Byte-range / DASH-style (Facebook) | ext e786050, c5eafad, 7365fd5; app 74db33cd, bcf0ae23 | MediaOnTab.ts (resources), SegmentUtils.ts (header sniffing), PageHeaders.ts; app HLSPlaylistBuilder, HLSPartDownloader | wrong pairing, tiny items, Range header replay, merge without ffmpeg |
| HLS engine | app dfecf37f, 82d096c4 | HLSDownloadJob, HLSStreamResolver, HLSKeyProvider, HLSDownloadDestination, SegmentedDownloadDestination | AES-128, fMP4, live, pause/resume, temp-file cleanup |
| Segments / av jobs, capabilities | app dfecf37f, 74db33cd; ext fe39720, e786050 | IntegrationHandlerImp, DownloadCredentialsFromIntegration, ApiCapabilities, REST-API.yml; ext Backend.ts, actions.ts | API compatibility with older/newer apps |
| ffmpeg bundled, Tools page | app dfecf37f, 12d56c4d | FfmpegManager, DesktopSettingsComponent, build.gradle.kts (downloadBundledFfmpeg) | build without network, license notice |
| yt-dlp (video sites) | app a9916f75; ext 925cdc3, 9dcee0a | YtDlpManager, HLSSiteResolver, HLSDownloaderInUi, SiteDownloader.ts, PopularSites.ts | Add URL routing (HLS first), silent add, quality list, cookies |
| Drag and drop | app 31835de4 | FloatingDropTarget.kt, HomePage.kt, DownloadItemListDataFlavor.kt, Ui.kt | drops, file deletion on "move", dragging rows out |

## 2-5. Results

| Feature | How it was tested | Result | Fix made |
|---|---|---|---|
| Unit tests | `gradlew test` (neither repo has unit-test sources; upstream had none) | pass (after fix) | `test` failed without an Android SDK: android:app is now only included with SKIP_ANDROID_BUILD=false |
| BASELINE 1 (direct files) | e2e-item1 (mock app) | pass | |
| BASELINE 3 (iframe players, HLS variants, DASH listing, SW-fetched playlists) | e2e-item3 (13 checks) | pass | |
| BASELINE 4a/4b (HLS demo, install/reload) | e2e-hls (A/B/C) | pass | |
| Segments, no flood, MSE pairing, debug log | e2e-8b (12 checks) | pass | |
| Prompt 9: AES-128 HLS (explicit IV, sequence IV, key rotation) | p9-test-aes | pass | |
| Prompt 9: fMP4 without playlist, pause/resume, old-app notice | p9-test-segments (9 checks) | pass | |
| Prompt 9b: Facebook-style byte ranges (hooked / stream / mixed, preloaded reel) | p9b-test-av ×4 (11 checks each) | pass | |
| Background playlist fetch with page Referer/Origin/UA | p9b-test-referer | pass | |
| Live recording kept across a restart | p9b-test-live | pass | |
| Per-site switches, YouTube page row | p10-test-sites | pass | |
| DASH manifest via yt-dlp | p10-test-page (local MPD) | pass | |
| HTTP multi-connection download | p13-core (24 MiB, 8 connections, sha256) | pass | |
| Pause / resume | p13-core (CLI pause, no traffic, resume) | pass | |
| Resume after app restart | p13-core (kill mid-download, restart, resume) | pass | |
| Server without Range (single connection) | p13-core | pass | |
| Server needing Cookie + Referer | p13-core (403 without) | pass | |
| Speed limiter global / per download | p13-core (time to finish) | pass | (test measured server bytes at first; Windows buffers MBs per socket, so the app's time-to-finish is used) |
| Queues (max concurrent) | p13-core (3 items, max 2 at once) | pass | |
| Scheduler start time | p13-core (queue start 2 min ahead) | pass | |
| Categories + folders | p13-core (.zip into Compressed) | pass | |
| Duplicate names | p13-core, silent-add test | pass | |
| Batch add | /add with 3 links | dialog opens (silent add applies to single downloads only, as in upstream) | none (original behaviour) |
| Browser download capture zip/exe/pdf (txt not) | p13-capture (local page, mock app) | pass | |
| Single-instance lock | p13-core (second WebDM.exe exits) | pass | |
| CLI (WebDMCli) | used by every core test | pass | |
| Local API /ping /add /capabilities /queues /start-headless-download | p13-core + all suites | pass | REST-API.yml: /ping was missing; downloadSource `type` documented |
| Native messaging host | p13-core (ping over stdio) | pass | |
| Start with Windows | portable app removes its Run value when the setting is off | pass | uninstaller didn't remove it (wrong key), fixed |
| Update checker | points at api.github.com/repos/Mashrafi-Zaman/webdm/releases/latest | 404 until a release exists; shown as "update check failed", no crash | none (Prompt 14 publishes releases) |
| Extension survives worker stop | p13-swstop-raw (raw DevTools protocol, ×3) | pass | (a Puppeteer-based version hung the restarted worker: harness artifact) |
| Extension reload | e2e-hls part C | pass | |
| AB DM (port 15151) never contacted | connection monitor on 15151 during 4 suites (AB DM installed and running here) | pass (0 connections) | |
| Uninstall cleanup | NSIS script review | fail → fixed | native-messaging host keys (Chrome/Edge/Brave, legacy Firefox) and the Run value are now removed; AB DM's keys have other names |
| Leftover branding | `git grep` both repos | fail → fixed | READMEs, CONTRIBUTING, DONATE rebranded; 21 translations said "AB Download Manager" in the exit dialog; winget workflow (published under amir1376's ID) and FUNDING.yml (sponsor button to AB DM) removed |

Allowed remaining mentions: LICENSE, NOTICE, credits in the READMEs/DONATE, CHANGELOG history, Kotlin package names
(`com.abdownloadmanager`, `ir.amirab`), extension message keys/CSS class names (`abdm-*`, not shown to users), and the
Android module (not built).

## Not tested automatically (manual steps)

See the end of the job report: Chrome and Edge by hand, every BASELINE item, the HLS demo, YouTube, a Facebook reel,
themes, tray, context menu, open file/folder, and install / upgrade / uninstall on a real install.
