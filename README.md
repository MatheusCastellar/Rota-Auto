# Rota Auto

**Android Auto on compatible Royal Enfield Tripper Dash displays.**

[Português (Brasil)](README.pt-BR.md) · English

Created by **Matheus Castellar**. A free, community project developed for riders, with no Rota Auto account required. Current source version: **0.1.47**.

Rota Auto receives Android Auto video on the phone and streams it over the motorcycle's Wi-Fi connection to the Tripper Dash. Maps and compatible media apps run through Android Auto; Rota Auto handles the connection, display adaptation and motorcycle controls. This is the main Android Auto project, not a standalone Google Maps SDK app.

## Features

- Wi-Fi discovery and reconnection for the motorcycle, plus a touch-enabled phone preview.
- Adjustable video frame rate and bitrate, display fitting, taskbar visibility and a panel simulator.
- Optional motorcycle logo and clock/date/battery/mobile-network widget with positioning controls.
- Three configurable control profiles, separate map/media inputs and an optional radial menu with three profiles.
- App shortcuts, voice-assistant actions, music controls and scrolling track information.
- Navigation maneuver/distance information when supplied by Android Auto; optional native navigation circle.
- Map day/night controls and configuration import/export.
- Portuguese (Brazil), English, Spanish and Hindi interface.

Audio can stay on the phone or its connected intercom. Available behavior depends on the phone, Android Auto version, installed apps and dashboard firmware.

## Requirements and setup

- Android **10 or newer**, with Android Auto installed and working.
- A compatible **Tripper Dash with Wi-Fi**. The older Bluetooth-only Tripper is a different device.
- Install Rota Auto and grant the permissions needed for the features you use.

1. Open Android Auto settings on the phone. Tap **Version** ten times to enable Android Auto developer settings.
2. In the main Android Auto settings menu (⋮), select **Start head unit server**.
3. Open Rota Auto, press **Start Android Auto**, then connect to the motorcycle's `RE_` Wi-Fi network. Use the reconnect option for a previously selected motorcycle.
4. Set a route in Google Maps or another supported navigation app. Configure controls and display options in Rota Auto.

Menu wording can vary by language/version. The server setting belongs to **Android Auto**, not Android's USB debugging menu. A USB cable is not required for the motorcycle Wi-Fi connection. Some phones behave differently when locked; the in-app tutorial includes the USB-debugging workaround observed on affected devices, but it is not a universal requirement.

## Known limitations

- This is an **unofficial receiver**, not a Google- or Royal Enfield-certified product. Compatibility can change after updates.
- Hiding the native navigation circle can prevent the dashboard from sending some map controls. This is a dashboard behavior, not just a visual setting.
- Video delay and smoothness depend on encoding settings, Wi-Fi conditions and dashboard processing. The default is **4 fps**.
- Some app-opening shortcuts depend on the Android Auto interface layout. Not every app exposes the same behavior.
- Screen-off operation, intercom routing and reconnection should be checked on your own phone and motorcycle. Configure and test while parked.

## Build from source

Open this folder in Android Studio, install **Android SDK 36**, and select a Gradle-compatible **JDK 17 or newer**. The repository includes the Gradle wrapper (9.4.1); Android Gradle Plugin is 9.2.1.

Set the SDK path using Android Studio or a local, untracked `local.properties` file, then run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat`. The debug APK is created in `app/build/outputs/apk/debug/`. Release signing keys are not included; a build signed with another key cannot update an existing installation signed by the author.

## Contributing and privacy

Bug reports are welcome through GitHub Issues. Include the Rota Auto/Android versions, dashboard model and steps to reproduce. Review logs and screenshots before posting: remove locations, Wi-Fi names, account details and other personal information. Never post API keys or signing keys.

The source contains a **public receiver interoperability certificate/key fixture** inherited from Open Headunit (`res/raw/cert` and `res/raw/privkey`). These are protocol fixtures, not the author's private signing credentials. Original Royal Enfield APKs, firmware files, extracted credentials and personal diagnostic recordings are not part of this repository.

## License and acknowledgments

Distributed under **GNU AGPL v3**; see [LICENSE](LICENSE). Attribution and component licenses are preserved in [NOTICE](NOTICE) and [licenses/](licenses/).

Thanks to [OpenDash](https://github.com/Nukeio/open-dash), [NorthStar](https://github.com/adityadasika21/NorthStar), [better-dash](https://github.com/norbertFeron/better-dash), [Open Headunit](https://github.com/andreknieriem/open-headunit), [WayFindR Dash Bridge](https://github.com/1857rebels/WayFindR-Dash-Bridge) and [Bootstrap Icons](https://github.com/twbs/icons). See NOTICE for revisions, authors and the scope of reused components.

Royal Enfield, motorcycle model logos, Android Auto, Google Maps, WhatsApp and YouTube belong to their respective owners. Third-party trademarks are not relicensed by the code license. This project is not affiliated with or endorsed by those companies.

[Testers' WhatsApp group](https://chat.whatsapp.com/C5hLBC3MRAWK0Avu2F1eXS) · [Matheus's YouTube channel](https://www.youtube.com/@Castellar450)
