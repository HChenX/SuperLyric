<div align="center">
<h1>SuperLyric</h1>

![stars](https://img.shields.io/github/stars/HChenX/SuperLyric?style=flat)
![downloads](https://img.shields.io/github/downloads/HChenX/SuperLyric/total)
![Github repo size](https://img.shields.io/github/repo-size/HChenX/SuperLyric)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/HChenX/SuperLyric)](https://github.com/HChenX/SuperLyric/releases)
[![GitHub Release Date](https://img.shields.io/github/release-date/HChenX/SuperLyric)](https://github.com/HChenX/SuperLyric/releases)
![last commit](https://img.shields.io/github/last-commit/HChenX/SuperLyric?style=flat)
![language](https://img.shields.io/badge/language-java-purple)

<p><b><a href="README-en.md">English</a> | <a href="README.md">简体中文</a></b></p>
<p>歌词获取器 | Super Lyric Getter</p>
</div>

---

## ✨ Module Introduction

- A brand-new lyric getter module, utilizing a completely new API!

---

## 🛠 Supported Applications

- NetEase Cloud Music (Meizu status bar lyric)
- NetEase Cloud Music Glory Edition (Meizu status bar lyric)
- Kugou Music (Meizu status bar lyric)
- Kugou Music Concept Edition (Meizu status bar lyric)
- Migu Music (Meizu status bar lyric)
- APlayer (Meizu status bar lyric)
- LMusic (Meizu status bar lyric)
- Yinliu Music (Meizu status bar lyric)
- Meizu Music (Meizu status bar lyric)
- Pepper Salt Music (Meizu status bar lyric)
- Tangcu Music (Meizu status bar lyric)
- Qinyan Music (Meizu status bar lyric)
- Gramophone (Meizu status bar lyric)
- MusicFree (Desktop Lyric)
- Luoxue Music (Desktop Lyric)
- Bodian Music (Status bar lyric)
- QQ Music (Status bar lyric)
- QQ Music Xiaomi Edition (Bluetooth lyric)
- QQ Music Meizu Edition (Bluetooth lyric)
- OPPO Music (In-car Bluetooth lyric)
- RPlayer (In-car Bluetooth lyric)
- Qishui Music (In-car Bluetooth lyric)
- Kuwo Music (Bluetooth lyric)
- Nisheng Music (Bluetooth lyric)
- Huawei Music (Bluetooth lyric)
- Kde (Bluetooth lyric)
- Apple Music (Hook)
- Symfonium (Hook)
- Flamingo (Native API support)
- [Cone Player](https://coneplayer.trantor.ink) (Native API support)
- Poweramp (Can only get lyrics on the app's lyric interface)

---

## 🛠 Applications Not Supported

- YouTube Music
- Spotify
- If you have good adaptation methods, pull requests are welcome.

---

## 🔧 Technical Overview

- Uses **Binder** for lyric transmission, abandoning the traditional broadcast method used by other similar APIs.
- Avoids Hooking system interfaces directly; it is parasitic on the system framework and relies on the module's robust crash handling for greater stability.
- Utilizes Binder for synchronous inter-process hookData transfer, offering better performance, lower latency, and the capability to transmit more complex hookData, and is less likely to trigger broadcast hookData restrictions.
- Supports free binding or unbinding registration anytime, anywhere; even if not manually unbound, the module handles it automatically.

---

## 🌟 How to Use

- Install this lyric getter module, import the API into your application, and register it with a few simple lines of code.
- API Project Address: [SuperLyricApi](https://github.com/HChenX/SuperLyricApi)

---

## 📢 Project Declaration

- ⚠ **By using this module, you agree to bear all consequences**!
- ⚠ **This project is not responsible for any derivative projects**!
- ⚠ **Plagiarism will lead to the project becoming closed source! Please credit the author!**

## 🎉 Conclusion

💖 **Thank you for your support, Enjoy your day!** 🚀
