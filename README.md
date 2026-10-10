<div align="center">
  <a href="https://github.com/Mashrafi-Zaman/webdm" target="_blank">
    <img width="180" src="assets/branding/webdm-logo.svg" alt="WebDM Logo">
  </a>
</div>
<h1 align="center">WebDM — Web Download Manager</h1>
<p align="center">
    <a href="https://github.com/Mashrafi-Zaman/webdm/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/Mashrafi-Zaman/webdm?color=FF6A3D&label=latest%20release"></a>
</p>

<a href="https://github.com/Mashrafi-Zaman/webdm" target="_blank">
    <img alt="WebDM Banner" src="assets/banners/webdm-banner.png"/>
</a>

## Introduction

WebDM (Web Download Manager) is a Windows download manager with a browser extension. It is a modified fork of
[AB Download Manager](https://github.com/amir1376/ab-download-manager) by amir1376 (Apache License 2.0); see
[NOTICE](NOTICE) and [LICENSE](LICENSE).

## Features

- ⚡️ Fast downloads with multiple connections, pause/resume, queues and schedulers
- 🎞️ Video downloads: HLS (incl. AES-128), streams rebuilt from their segments, separate video + audio, and video
  sites through yt-dlp (YouTube, Facebook, Instagram ...), merged with ffmpeg (both included)
- 🌐 Browser extension for Chromium browsers (Chrome, Edge, Brave, Opera)
- 🖱️ Drag and drop, with an optional floating drop target
- 🌙 Multiple themes with a modern UI
- 🪟 Windows 10/11 x64

## Installation

Download the installer (`WebDM_<version>_windows_x64.exe`) from
[GitHub Releases](https://github.com/Mashrafi-Zaman/webdm/releases/latest).

> ⚠️ **Warning:** WebDM is only published on this GitHub repository. Any other copy claiming to be WebDM should be
> considered unsafe.

### Browser Extension

Install the **WebDM Integration** browser extension to integrate the app with your browser.
It works in Chromium browsers (Chrome, Edge, Brave, Opera); Firefox is not supported.
It is not in the Chrome Web Store yet; build it from
[Mashrafi-Zaman/webdm-extension](https://github.com/Mashrafi-Zaman/webdm-extension) and load it unpacked.

## Screenshots

<div align="center">
  <img alt="WebDM main window" src="./assets/screenshots/webdm-home.png">
</div>

## Repositories And Source Code

| Repository                                                                  | Description                                                    |
|-----------------------------------------------------------------------------|----------------------------------------------------------------|
| [WebDM](https://github.com/Mashrafi-Zaman/webdm) (You are here)             | The **application** that runs on your **PC**                  |
| [WebDM Integration](https://github.com/Mashrafi-Zaman/webdm-extension)      | The **browser extension** to install in your **browser**       |

WebDM is based on [AB Download Manager](https://github.com/amir1376/ab-download-manager) and its
[browser integration](https://github.com/amir1376/ab-download-manager-browser-integration) by amir1376.
WebDM is not affiliated with or endorsed by the AB Download Manager project.

## Bug Report

If you notice any bugs, please report them in [GitHub Issues](https://github.com/Mashrafi-Zaman/webdm/issues).

## Build From Source

To compile and test the desktop app on your local machine (Windows x64),
follow these steps:

1. Clone the project.
2. Download and extract the [JBR](https://github.com/JetBrains/JetBrainsRuntime/releases) (JDK 25), and make it
   available by either:

    - Adding it to your `PATH`, or
    - Setting the `JAVA_HOME` environment variable to its installation path.

3. Navigate to the project directory, open your terminal and execute the following command (the build downloads
   the bundled ffmpeg and yt-dlp and checks their checksums):

    ```bash
    ./gradlew createReleaseFolderForCi
    ```

4. The output will be available at:

    ```
    <project_dir>/build/ci-release
    ```

> **Note**. This project is compiled and published by GitHub actions [here](./.github/workflows/publish.yml), so if you
> faced any problem you can check that too.

## Translations

The translations come from AB Download Manager's
[Crowdin project](https://crowdin.com/project/ab-download-manager); WebDM doesn't have its own translation project yet.

## Contribution

If you want to contribute to this project, please read [Contributing Guide](CONTRIBUTING.md) first.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The installer also ships ffmpeg (LGPL) and yt-dlp
(Unlicense) as separate programs, with their licenses.
