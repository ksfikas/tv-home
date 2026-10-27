# tv-home · Live TV edition

**A fast, clean Apple TV-style home screen for Android TV, with live TV from your own
[Tvheadend](https://tvheadend.org) server built right in:** channels on the home screen, a TV
guide, recordings, radio with a live spectrum, and pausing live TV. One small app, no ads, no
separate TV client.

> Forked from [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home). The launcher,
> the debloat tools and the wallpapers are the original author's work (MIT). This fork adds
> everything Live TV, a Xiaomi TV Box S profile and Windows scripts.

![Home screen: a dock of streaming apps, a Live TV row with Guide, Recordings and channel logos showing what's on, and a Radio row](docs/screenshots/home-live-tv.jpg)

## What it does

**Home screen** (from the original)
- A dock of your favourite apps under the clock, over rotating nature wallpapers, with a glow
  coloured from the focused app. Every other app in a grid below.
- Plain Android views, nothing running in the background. The whole APK is about 5.7 MB, most of
  it wallpapers.

**Live TV** (this fork)
- A **LIVE TV** row with every channel: logo, number, what's on now and a progress bar. Pick one
  and it plays full screen on the box's **hardware decoders** (MPEG-2, H.264, HEVC, MP2, AAC, AC3).
- **Guide**: a full-screen programme grid with channel logos, a "now" line and the selected
  programme's description. OK watches what's on or records what's coming.
- **Recordings**: what's recording, scheduled, recorded or failed. Play with seeking, cancel,
  delete. Record from the guide or from the player.
- **Radio**: stations get their own row and a calm now-playing screen with a **live spectrum**.
- **Pause live TV**, then carry on from where you paused, or jump back to live.
- **Audio and subtitle tracks**, with the language remembered from channel to channel.
- **Favourites**: hold OK on a channel to pin it; the row then shows only your favourites, in your order.
- **Hide apps** from the grid by holding OK on them.
- Optional: start the last channel when the box boots.

<table>
<tr>
<td><img src="docs/screenshots/guide.jpg" alt="TV guide grid with channel logos, programmes over two hours, a red now line and the selected programme's description"></td>
<td><img src="docs/screenshots/radio.jpg" alt="Radio now-playing screen with a mirrored white spectrum above the station name, current show and next show"></td>
</tr>
<tr>
<td><img src="docs/screenshots/record-from-guide.jpg" alt="Guide with a red dot on a programme set to record"></td>
<td><img src="docs/screenshots/player-options.jpg" alt="Player options: Pause, Audio, Subtitles, Record this programme, TV guide, Recordings"></td>
</tr>
<tr>
<td><img src="docs/screenshots/player-banner.jpg" alt="Info banner while watching: channel logo, number and name, programme, progress, what's next"></td>
<td><img src="docs/screenshots/recordings.jpg" alt="Recordings screen with a scheduled recording"></td>
</tr>
</table>

<sub>The video picture is missing from the screenshots on purpose: the box draws hardware-decoded
video on its own layer, which screenshots don't capture.</sub>

## Remote control

| Where | Button | Does |
|---|---|---|
| Home | OK on a channel | Watch it |
| | Hold OK on a channel | Favourites (add, remove, reorder), Live TV setup |
| | Hold OK on an app in the grid | Hide it (bring back: *Inputs → Show hidden apps*) |
| Watching TV | Up / Down, Channel ± | Next / previous channel |
| | Left | Back to the previous channel |
| | Right, Info | Now/next banner |
| | OK | Channel list |
| | Hold OK, Menu | Options: pause, back to live, audio, subtitles, record, guide, recordings |
| | Play/Pause | Pause or resume live TV |
| | 0–9 | Tune by number |
| Guide | Arrows | Move around; more of the schedule loads as you go right |
| | OK | Watch (on now) or record / cancel (later) |
| | Hold OK | Record what's on now |
| Recordings | OK, hold OK | Play (or cancel if scheduled), delete |
| Playing a recording | Left / Right, Up / Down | Back / forward 30 s, 5 min |
| | OK | Pause |

## Install

1. **Turn on debugging on the TV**: Settings → System → About → press OK on **Build** 7 times,
   then Developer options → **USB debugging** (and **Network debugging** if your box has it).
2. **Get the APK**: download `tv-home.apk` from [Releases](../../releases), or build it:

   ```bash
   ./gradlew :launcher:assembleRelease     # JDK 17 + Android SDK (platform 34)
   ```

3. **Install it and make it the home screen** (Google TV):

   ```bash
   adb connect <tv-ip>:5555                                   # accept the prompt on the TV
   adb install -r tv-home.apk
   adb shell pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION   # Wi-Fi name only
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx       # stock launcher
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith    # else it grabs Home
   adb shell cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
   ```

   Undo with `tools/restore.sh` (macOS/Linux) or `tools/windows/restore.ps1` (Windows).

4. **Connect Live TV**: on the home screen, select **Set up Live TV** (or *Inputs → Live TV
   setup…*), enter `http://<server>:9981` and your Tvheadend username and password, and Save. It
   checks the login right away.

Then turn debugging off again when you're done: while it's on, anyone you've approved can run
commands on the TV.

### Tvheadend tips

- Your Tvheadend user needs **streaming** rights, plus **video recorder** rights for recordings.
- Channel logos come from Tvheadend. If the server's picon path points somewhere without your
  channels, logos won't load; set each channel's *icon* to a URL instead. The community
  [tv-logo/tv-logos](https://github.com/tv-logo/tv-logos) set covers many countries.
- Radio stations are recognised by Tvheadend's own *Radio channels* tag.

## How it stays light

The box this was built on has 2 GB of RAM, so every feature had to earn its place.

- **No extra UI libraries.** Everything is drawn with plain Android views. The guide is one
  custom-drawn view, not hundreds of widgets, and the only dependency is Media3 ExoPlayer for playback.
- **Nothing in the background.** The player is released the moment you leave it. The home row
  refreshes what's on only while it's on screen.
- **Streams go untouched.** Tvheadend's `pass` profile sends the broadcast as-is, so the server
  doesn't transcode and the box decodes in hardware.
- **Pause costs nothing until you pause.** The stream runs through a small in-memory buffer.
  Only while paused does it spill to a temp file, capped at 700 MB (or the free space minus
  400 MB), and the file is deleted when you go back to live or leave.
- **The spectrum** taps the decoded audio the player already has (no microphone permission) and
  runs only while the radio screen is visible.
- **Logos** are cached on disk, and in memory at exactly the size they're drawn.
- **Logins** use Digest auth. Live streams use Tvheadend's short-lived ticket links, so the player
  never holds your password. The login is stored only in the app's private storage on the TV.

Measured on a Xiaomi TV Box S (2nd Gen): about 98 MB for the whole launcher on the home screen
with both rows of logos, and about 86 MB while playing HD.

## Debloating

The original project's tools work as before. Lists are per model in [examples/](examples/); see
[examples/xiaomi-tv-box-s-gen2](examples/xiaomi-tv-box-s-gen2/debloat.txt) for the box this fork
was built on (telemetry, Google TV's ambient ads, optional Xiaomi extras).

```bash
tools/disable.sh examples/xiaomi-tv-box-s-gen2/debloat.txt          # macOS / Linux
.\tools\windows\debloat.ps1 examples\xiaomi-tv-box-s-gen2\debloat.txt   # Windows (adb on PATH)
```

Undo with `tools/restore.sh` or `tools/windows/restore.ps1` and the same list. Everything is
disabled for user 0, never uninstalled, and a factory reset brings it all back.

On that box, the whole clean-up (debloat, launcher swap, 0.5× animations, cleared caches, unused
apps removed) took free memory from about **535 MB to 940 MB** after a restart, and free storage
from 1.1 GB to 1.9 GB.

### With Claude Code

This fork was built in a Claude Code session, the same way the original suggests. Clone the repo,
open Claude Code in it, and describe your TV. The original prompt is in [PROMPT.md](PROMPT.md).

## What's here

| Path | What it is |
|---|---|
| [launcher/](launcher/) | The app: home screen, Live TV, guide, recordings, player |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, grid columns |
| [examples/](examples/) | Debloat lists per TV model |
| [tools/](tools/) | ADB helpers and the debloat/restore scripts (macOS/Linux) |
| [tools/windows/](tools/windows/) | The debloat/restore scripts for Windows |
| [.github/workflows/build.yml](.github/workflows/build.yml) | Builds the APK on every push; tag `v*` to publish a release |
| [PROMPT.md](PROMPT.md) | The original Claude Code prompt |

## Gotchas

From this fork:
- **Netflix doesn't exit on Back.** Netflix's TV app consumes Back on its own home screen; use
  Home (or Netflix's *Exit* item). A launcher can't change this.
- **Back from an app opened outside the launcher** (from a cast, say) returns to the previous app,
  not home. That's standard Android task behaviour; Home always works.
- **Long-press in tests.** `adb shell input keyevent --longpress` doesn't trigger long-click on
  ordinary views, which time a real held button. The player and guide read key events directly,
  so they handle both.
- **Recordings need the login per request.** Tvheadend's ticketed recordings playlist only lists
  finished recordings, so the player signs each (seeking) request with Digest auth instead.
- **Channel logos missing?** Usually the server's picon path, not the launcher: see
  *Tvheadend tips* above.

From the original:
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) outranks any third-party launcher. Disable it too.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per call.
- **macOS blocks the `adb` binary** on the local network. `tools/tv.py` uses the `adb-shell`
  Python package instead.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed.
  The launcher re-checks on every return.

## Tested on

- Xiaomi TV Box S (2nd Gen), Google TV, Android 14, with Tvheadend 4.3 (Greek DVB-T: ERT, Digea).
- TCL Google TV, Android 11 (the original project).

## Credits

The launcher, debloat tools and wallpapers are by
[mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home), inspired by
[Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are from
Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Channel logos are not included;
they come from your Tvheadend server. Playback uses [AndroidX Media3](https://github.com/androidx/media).
Code is MIT, see [LICENSE](LICENSE).
