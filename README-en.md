# SuperLyric

[简体中文](README.md) | English

An LSPosed module that publishes lyrics from music apps to Android over Binder.

## Features

- Acquires lyrics through host hooks or online endpoints, choosing the path per app.
- Publishes lyrics to 4.0 clients through the super_lyric.v4 frame interface and keeps the 3.x super_lyric interface.
- Playback state and progress come from the system MediaSession; the module publishes full lyrics only.

## Supported apps

| App | Package | Mode |
| :--- | :--- | :--- |
| Apple Music | com.apple.android.music | Hook |
| BoDian Music | cn.wenyu.bodian | Hook + Kugou fallback |
| Kugou Music | com.kugou.android | Network |
| Kugou Music Lite | com.kugou.android.lite | Network |
| Kuwo Music | cn.kuwo.player | Hook + Kugou fallback |
| Kuwo Music Car | cn.kuwo.kwmusiccar | Hook + Kugou fallback |
| Migu Music | cmccwm.mobilemusic | Hook + Kugou fallback |
| Mi Music | com.miui.player | Hook |
| NetEase Cloud Music | com.netease.cloudmusic | Network |
| Poweramp | com.maxmpz.audioplayer | Hook (local LRC / lyric cache) + Kugou fallback |
| QQ Music | com.tencent.qqmusic | Hook |
| Salt Music | com.salt.music | Hook + Kugou fallback |
| Soda Music | com.luna.music | Hook + Kugou fallback |
| Spotify | com.spotify.music | Network |
| Flamingo | No fixed package | Native API |
| [Cone Player](https://coneplayer.trantor.ink) | ink.trantor.coneplayer | Native API |
| [BreezyMusic](https://github.com/BlueEve04/BreezyMusic) | com.breezy.music | Native API |

## Acquisition modes

- Hook: reads the host's internal lyric model without network access.
- Hook + Kugou fallback: reads the host lyric first; if the hook misses or times out, searches the Kugou library by title, artist, and duration.
- Network: uses host media information and online endpoints, with local caching.
- Native API: the host integrates SuperLyric API directly.

## Unsupported

- YouTube Music
- Apps not listed above are not supported.

## API project

[SuperLyricApi](https://github.com/HChenX/SuperLyricApi)

## Notice

- Use of this module is at the user's own risk.
- This project is not responsible for derivative projects.
- Credit the author when using code from this project.
