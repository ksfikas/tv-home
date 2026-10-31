# Changelog

All notable changes to Advanced TV Home. Newest first. Releases are tagged `vX.Y.Z` and published on
[GitHub](https://github.com/ksfikas/advanced-tv-home/releases).

## Unreleased

- **Tvheadend tools** (Inputs → Tvheadend server): server status (tuners, signal, who is watching, recordings disk), channel manager (show/hide channels, change numbers), refresh the guide now, rescan channels.
- **Search** finds channels as well as programmes, by voice or keyboard.
- **Phone remote**: channel filter box on the Channels tab, and a Tvheadend status card on the Remote tab. Five wrong PIN attempts lock pairing for a minute.
- **Screensaver**: "Coming up" slide (your scheduled recordings and reminders) and a night mode that dims to a small drifting clock from a chosen hour until 06:00.
- **Box status** shows the installed version and a notice when a newer release exists (checked once a day, can be switched off).
- **First-run setup**: a short checklist (language, weather, Live TV, phone remote, screensaver) offered once on a fresh install, and always under Inputs → Set up…
- Faster channel start: playback begins after 250 ms of data and the zap pause is 250 ms.
- Tests for the news-feed parser, channel search, server status and update check (70 in all).

## 3.0.1

- **Picture in picture** for live TV and recordings: from the player options, or automatically when you press Home. The player has its own task so Home no longer closes it.

## 3.0.0

- **Renamed to Advanced TV Home**; new app ID `io.github.ksfikas.tvhome` (an old install must be disabled, not upgraded: see the README).
- **Bilingual** (English and Greek): system language by default, with a switch in Control Center; the phone remote page and README follow.
- **Screensaver**: photos with a clock, weather, news ticker, what is on TV now, popular films and headlines; options under Control Center → Screensaver.
- Bluetooth tile opens the pairing screen; radio list keeps the playing channel centred.
- Updates: Gradle 9.8, Android Gradle Plugin 9.5, Media3 1.11.1, ZXing 3.5.4.

## 2.2.0

- **Top Shelf** modes (continue watching, picks and news) with photos and grouped cards.
- **Control Center** as a frosted capsule panel with Material Symbols icons; frosted menus.
- Phone remote: scan a QR code to open and pair, standby and wake, a Guide tab with now and next for every channel.
- Dock editing, weather details and wallpapers sent from the phone.

## 2.1.0

- Phone remote and voice search.
- Network tuners (HDHomeRun) and tuner apps as Live TV sources.
- Dock shows what you last watched in each app.
- Mini guide, series recording, reminders, sleep timer; Guide, Search, Recordings and Continue as a small block on Home.
- CI runs unit tests and lint before every build.

## 2.0.0

- First release of the Tvheadend (Live TV) client on top of the launcher.
