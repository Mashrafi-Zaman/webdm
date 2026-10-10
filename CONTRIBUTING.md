# Contributing to WebDM

Thanks for taking the time to help. WebDM is a small project, so clear reports and focused changes go a long way.

## Reporting a bug

1. Check that you are on the [latest release](https://github.com/Mashrafi-Zaman/webdm/releases/latest).
2. Search the [existing issues](https://github.com/Mashrafi-Zaman/webdm/issues) first.
3. Open a new issue with:
   - your Windows version, the WebDM version (Help → About) and, for the extension, the browser and its version
   - the steps to reproduce it and what you expected instead
   - a public link to the page or file, if the problem is site-specific
   - logs if you have them. The extension's toolbar popup can export a detection log (cookie and authorization values
     are removed); please check that nothing private is left before attaching it.

Problems with the browser extension are tracked here too, not in the extension repository.

Requests to download DRM-protected streams (Widevine, PlayReady, FairPlay) will be closed: WebDM doesn't support
them and won't.

## Suggesting a feature

Open an issue that describes the problem you want to solve, not only the solution. Mockups or examples from other
open-source tools are welcome; please don't attach screenshots or assets from proprietary download managers.

## Pull requests

- **Talk first.** For anything bigger than a small fix, open an issue before you start, so we can agree on the
  approach.
- **Keep it focused.** One change per pull request, without unrelated reformatting or renames. The Kotlin packages
  keep their original `com.abdownloadmanager` names on purpose.
- **Both sides of the API.** A change to the local API (port, endpoints, payloads) has to be made in this repository
  and in [webdm-extension](https://github.com/Mashrafi-Zaman/webdm-extension), and documented in
  [REST-API.yml](REST-API.yml).
- **Build and test it.** Build with `gradlew.bat createReleaseFolderForCi` (see the README), install or unzip the
  result and test your change on Windows 10/11 x64. Say in the pull request how you tested it.
- **Own your code.** You should understand every line you submit and be ready to fix review comments yourself. Large
  generated patches that leave the debugging to the reviewer will be closed.
- **License.** Contributions are accepted under the Apache License 2.0, the license of this project. Keep the existing
  copyright notices and don't add code, icons or text you don't have the rights to.

## Translations

The app's translations come from AB Download Manager and live in
`shared/resources/src/commonMain/resources/com/abdownloadmanager/resources/locales/`. Fixes to WebDM-specific texts
are welcome as pull requests to those `.properties` files.
