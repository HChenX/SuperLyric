<div align="center">
<h1>SuperLyric-Poweramp</h1>

![stars](https://img.shields.io/github/stars/BlueEve04/SuperLyric-Poweramp?style=flat)
![downloads](https://img.shields.io/github/downloads/BlueEve04/SuperLyric-Poweramp/total)
![Github repo size](https://img.shields.io/github/repo-size/BlueEve04/SuperLyric-Poweramp)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/BlueEve04/SuperLyric-Poweramp)](https://github.com/BlueEve04/SuperLyric-Poweramp/releases)
[![GitHub Release Date](https://img.shields.io/github/release-date/BlueEve04/SuperLyric-Poweramp)](https://github.com/BlueEve04/SuperLyric-Poweramp/releases)
![last commit](https://img.shields.io/github/last-commit/BlueEve04/SuperLyric-Poweramp?style=flat)
![language](https://img.shields.io/badge/language-java%2Fkotlin-purple)

<p><b><a href="README-en.md">English</a> | <a href="README.md">简体中文</a></b></p>
<p>Poweramp adaptation branch | SuperLyric</p>
</div>

> Based on [HChenX/SuperLyric](https://github.com/HChenX/SuperLyric) 4.1, this branch adds a
> Poweramp (`com.maxmpz.audioplayer`) lyric provider that reads local LRC files or the host's
> lyric cache on track change, without opening Poweramp's lyrics screen. Original project
> copyright belongs to HChenX.

---

## ✨ Module Introduction

- A lyrics acquisition and publishing module for music applications based on Binder data
  transmission!
- Completely abandons the traditional broadcast approach, fully embracing the higher-performance,
  lower-latency Binder cross-process capabilities!
- Lyrics can be acquired via Hook or the network: the acquisition mode is switchable per app for
  NetEase and Honor Music, Spotify uses the network path, and online lyrics are locally cached.

---

## 🎧 Poweramp Adaptation

- Tested with Poweramp `build-1031-uni` (versionCode `1031009`)
- **Lyrics on track change**: reads `cached_lyrics.content` / `lrc_files` from the host database,
  so opening the lyrics screen is not required
- Obfuscated classes are located with DexKit string anchors; the host's own `LrcParser` is used to
  read LRC files, bypassing scoped storage
- Raw LRC bytes are decoded as strict UTF-8 with an automatic GBK fallback
- Only local LRC files or already-cached lyrics are supported; tracks whose lyrics exist only in
  embedded tags still require opening the lyrics screen once
- **Reboot the device after updating the module APK** so system_server reloads the module;
  otherwise the old system service keeps running
- Release APK: [Releases](https://github.com/BlueEve04/SuperLyric-Poweramp/releases)

---

## 🛠 Supported Applications

- Apple Music (com.apple.android.music) -> Hook Acquisition
- BoDian Music (cn.wenyu.bodian) -> Hook Acquisition
- Kugou Music (com.kugou.android) -> Network
- Kugou Music Lite (com.kugou.android.lite) -> Network
- Migu Music (cmccwm.mobilemusic) -> Hook Acquisition
- Mi Music (com.miui.player) -> Hook Acquisition
- NetEase Cloud Music (com.netease.cloudmusic) -> Network
- Poweramp (com.maxmpz.audioplayer) -> Hook Acquisition (local LRC / lyric cache)
- QQ Music (com.tencent.qqmusic) -> Hook Acquisition
- Salt Music (com.salt.music) -> Hook Acquisition
- Spotify (com.spotify.music) -> Network
- Flamingo (unknown) -> Native API Support
- [Cone Player](https://coneplayer.trantor.ink) (ink.trantor.coneplayer) -> Native API Support
<!-- Applications not yet adapted to the 4.0 Unified Architecture:
- APlayer (remix.myplayer) -> Meizu Status Bar Lyrics
- Stream Music (cn.aqzscn.stream_music) -> Meizu Status Bar Lyrics
- Gramophone (org.akanework.gramophone) -> Meizu Status Bar Lyrics
- OPPO Music (com.heytap.music) -> Bluetooth Lyrics
- HiBy Music (com.hiby.music) -> Bluetooth Lyrics
- Honor Music (com.hihonor.cloudmusic) -> Meizu Status Bar Lyrics / Network
- Huawei Music (com.huawei.music) -> Bluetooth Lyrics
- Kde (org.kde.kdeconnect_tp) -> Bluetooth Lyrics
- Kuwo Music (cn.kuwo.player) -> Bluetooth Lyrics
- LMusic (com.lalilu.lmusic) -> Meizu Status Bar Lyrics
- LX Music (cn.toside.music.mobile) -> Desktop Lyrics
- Meizu Music (com.meizu.media.music) -> Meizu Status Bar Lyrics
- Mimicry Music (com.mimicry.mymusic) -> Meizu Status Bar Lyrics
- MusicFree (fun.upup.musicfree) -> Desktop Lyrics
- OPPO Music (com.oppo.music) -> Bluetooth Lyrics
- QingYan Music (com.xuncorp.qinalt.music) -> Meizu Status Bar Lyrics
- Soda Music (com.luna.music) -> Bluetooth Lyrics
- RPlayer (com.r.rplayer) -> Bluetooth Lyrics
- Suvine Music (com.xuncorp.suvine.music) -> Meizu Status Bar Lyrics
- Symfonium (app.symfonik.music.player) -> Hook Acquisition
-->

> Acquisition and usage notes:
> - **Hook Acquisition**: Hooks host application's internal lyric models. No setup required, play normally.
> - **Network**: Fetches online lyrics via MediaSession and online API with local caching. Keep network connected, play normally.
> - **Native API Support**: Host application natively integrates SuperLyric API. Play normally.

---

## 🛠 Unsupported Applications

- YouTube Music
- If you have good adaptation methods, PRs are welcome!

---

## 🌟 API Project

- API Project Repository: [SuperLyricApi](https://github.com/HChenX/SuperLyricApi)

---

## 📢 Project Statement

- ⚠ **Using this module implies that you are willing to bear all consequences!**
- ⚠ **This project assumes no responsibility for any derivative projects!**
- ⚠ **Please credit the author when using code from this project!**

## 🎉 Closing

💖 **Thank you for your support, and enjoy your day!** 🚀
