<div align="center">
<h1>SuperLyric</h1>

![stars](https://img.shields.io/github/stars/HChenX/SuperLyric?style=flat)
![downloads](https://img.shields.io/github/downloads/HChenX/SuperLyric/total)
![Github repo size](https://img.shields.io/github/repo-size/HChenX/SuperLyric)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/HChenX/SuperLyric)](https://github.com/HChenX/SuperLyric/releases)
[![GitHub Release Date](https://img.shields.io/github/release-date/HChenX/SuperLyric)](https://github.com/HChenX/SuperLyric/releases)
![last commit](https://img.shields.io/github/last-commit/HChenX/SuperLyric?style=flat)
![language](https://img.shields.io/badge/language-java%2Fkotlin-purple)

<p><b><a href="README-en.md">English</a> | <a href="README.md">简体中文</a></b></p>
<p>Lyrics Fetcher | Super Lyric</p>
</div>

---

## ✨ Module Introduction

- A lyrics acquisition and publishing module for music applications based on Binder data
  transmission!
- Completely abandons the traditional broadcast approach, fully embracing the higher-performance,
  lower-latency Binder cross-process capabilities!
- Lyrics can be acquired via Hook or the network: the acquisition mode is switchable per app for
  NetEase and Honor Music, Spotify uses the network path, and online lyrics are locally cached.

---

## 🛠 Supported Applications

- Apple Music (com.apple.android.music) -> Hook Acquisition
- BoDian Music (cn.wenyu.bodian) -> Hook Acquisition
- Kugou Music (com.kugou.android) -> Hook Acquisition
- NetEase Cloud Music (com.netease.cloudmusic) -> Network
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
- Kugou Music Lite (com.kugou.android.lite) -> Hook Acquisition
- Kuwo Music (cn.kuwo.player) -> Bluetooth Lyrics
- LMusic (com.lalilu.lmusic) -> Meizu Status Bar Lyrics
- LX Music (cn.toside.music.mobile) -> Desktop Lyrics
- Meizu Music (com.meizu.media.music) -> Meizu Status Bar Lyrics
- Mimicry Music (com.mimicry.mymusic) -> Meizu Status Bar Lyrics
- Mi Music (com.miui.player) -> Bluetooth Lyrics
- Migu Music (cmccwm.mobilemusic) -> Meizu Status Bar Lyrics
- MusicFree (fun.upup.musicfree) -> Desktop Lyrics
- OPPO Music (com.oppo.music) -> Bluetooth Lyrics
- Poweramp (com.maxmpz.audioplayer) -> Lyrics available only when on the app's lyrics interface
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
