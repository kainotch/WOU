# WOU

An ad-free Android music player for YouTube Music, built because the official app locks basic things like background playback behind a paywall and keeps getting heavier with every update.

## Getting the app

Download the latest pre-built APK from the [Releases](https://github.com/kainotch/WOU/releases/latest) page.

If you use [Obtainium](https://github.com/ImranR98/Obtainium) to track updates, add `https://github.com/kainotch/WOU` directly to install and update builds automatically.

## What it does

- Plays music in the background and with the screen off, with no ads or subscription prompts.
- Fetches real-time synced lyrics through LRCLIB and Kugou, with word-by-word timing and translation support.
- Caches tracks locally so songs you have already played load instantly, and lets you download playlists or albums for offline listening.
- Audio engine options: gapless playback, crossfade, silence skipping, and volume normalization (EBU R128).
- Clean Material You interface that adapts its palette to the current album artwork or system colors.
- Search with direct shortcuts for music and podcasts, keeping track of songs you have actually clicked on rather than filling the screen with old text queries.
- Optional YouTube account login to import your existing library, likes, and playlists.
- Discord Rich Presence integration to show what track you are currently playing.

## Building from source

Requirements:
- JDK 17
- Android SDK 34 or higher

Clone the repository and build the debug APK:

```bash
git clone https://github.com/kainotch/WOU.git
cd WOU
./gradlew assembleDebug
```

On Windows:
```cmd
gradlew.bat assembleDebug
```

The output file will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Known issues

- Lyric fetching can be slow on first play over weak mobile data, and less popular tracks might only have unsynced plain lyrics or none at all.
- YouTube occasionally throttles or alters media streams, which can cause a brief buffer hitch before playback continues.
- Android Auto is not supported yet.
- Google account login with brand accounts or complex 2FA prompts can sometimes take more than one attempt to complete.

## Credits and license

WOU builds upon work from the open-source music community, particularly InnerTune, Metrolist, and Innertube. Lyrics are powered by LRCLIB.

Licensed under the GNU General Public License v3.0 (GPL-3.0).
