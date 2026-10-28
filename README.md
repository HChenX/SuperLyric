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
<p>Poweramp 适配分支 | SuperLyric</p>
</div>

> 本仓库基于 [HChenX/SuperLyric](https://github.com/HChenX/SuperLyric) 4.1，
> 新增 Poweramp（`com.maxmpz.audioplayer`）歌词 Provider：切歌时直接读取本地 LRC
> 或宿主歌词缓存，无需打开 Poweramp 歌词界面。原始项目版权归 HChenX 所有。

---

## ✨ 模块介绍

- 基于 Binder 数据传递的音乐软件歌词获取与发布模块！
- 完全抛弃传统的广播模式，全面拥抱更高性能更低延迟的 Binder 跨进程能力！
- 支持 Hook / 网络双路径获取歌词：网易云、荣耀音乐可按应用切换获取方式，Spotify 走网络获取，在线歌词带本地缓存。

---

## 🎧 Poweramp 适配

- 支持版本：Poweramp `build-1031-uni`（versionCode `1031009`）实测通过
- **切歌自动取词**：读取宿主数据库里的 `cached_lyrics.content` / `lrc_files`，无需打开歌词界面
- 混淆类通过 DexKit 字符串锚点定位，调用 Poweramp 自己的 `LrcParser` 读取 LRC，绕过 scoped storage
- LRC 原始字节按 UTF-8 严格解码，失败自动回退 GBK
- 仅支持本地 LRC 或已缓存歌词；只有内嵌标签歌词的曲目仍需打开一次歌词界面
- **更新模块 APK 后需重启手机**，让 system_server 重新加载模块，否则系统服务仍为旧版，服务不正常

---

## 🛠 支持的软件

- Apple Music (com.apple.android.music) -> Hook 获取
- 波点音乐 (cn.wenyu.bodian) -> Hook 获取
- 酷狗音乐 (com.kugou.android) -> 网络获取
- 酷狗概念版 (com.kugou.android.lite) -> 网络获取
- 咪咕音乐 (cmccwm.mobilemusic) -> Hook 获取
- 小米音乐 (com.miui.player) -> Hook 获取
- 网易云音乐 (com.netease.cloudmusic) -> 网络获取
- Poweramp (com.maxmpz.audioplayer) -> Hook 获取（本地 LRC / 歌词缓存）
- QQ 音乐 (com.tencent.qqmusic) -> Hook 获取
- 椒盐音乐 (com.salt.music) -> Hook 获取
- Spotify (com.spotify.music) -> 网络获取
- Flamingo (unknown) -> Api 原生支持
- [光锥音乐](https://coneplayer.trantor.ink) (ink.trantor.coneplayer) -> Api 原生支持
<!-- 暂时未适配至 4.0 统一架构的应用
- APlayer (remix.myplayer) -> 魅族状态栏歌词
- 音流音乐 (cn.aqzscn.stream_music) -> 魅族状态栏歌词
- 留声机 (org.akanework.gramophone) -> 魅族状态栏歌词
- OPPO 音乐 (com.heytap.music) -> 蓝牙歌词
- 海贝音乐 (com.hiby.music) -> 蓝牙歌词
- 荣耀音乐 (com.hihonor.cloudmusic) -> 魅族状态栏歌词 / 网络获取
- 华为音乐 (com.huawei.music) -> 蓝牙歌词
- Kde (org.kde.kdeconnect_tp) -> 蓝牙歌词
- 酷我音乐 (cn.kuwo.player) -> 蓝牙歌词
- LMusic (com.lalilu.lmusic) -> 魅族状态栏歌词
- LX Music (cn.toside.music.mobile) -> 桌面歌词
- 魅族音乐 (com.meizu.media.music) -> 魅族状态栏歌词
- 拟声音乐 (com.mimicry.mymusic) -> 魅族状态栏歌词
- MusicFree (fun.upup.musicfree) -> 桌面歌词
- OPPO 音乐 (com.oppo.music) -> 蓝牙歌词
- 青盐音乐 (com.xuncorp.qinalt.music) -> 魅族状态栏歌词
- 汽水音乐 (com.luna.music) -> 蓝牙歌词
- RPlayer (com.r.rplayer) -> 蓝牙歌词
- 糖醋音乐 (com.xuncorp.suvine.music) -> 魅族状态栏歌词
- Symfonium (app.symfonik.music.player) -> Hook 获取
- Tidal (com.aspiro.tidal) -> Hook 获取
-->

> 获取方式与使用方法说明：
> - **Hook 获取**：Hook 宿主应用内部歌词模型，无需额外设置，正常播放即可。
> - **网络获取**：通过媒体会话与网络栈在线拉取，带本地缓存，保持网络畅通，正常播放即可。
> - **Api 原生支持**：宿主应用原生集成 SuperLyric API，正常播放即可。

---

## 🛠 不支持的软件

- YouTube Music
- 如果你有好的适配方法欢迎提交 PR

---

## 🌟 API 项目

- API 项目地址：[SuperLyricApi](https://github.com/HChenX/SuperLyricApi)

---

## 📢 项目声明

- ⚠ **使用本模块即代表愿意承担一切后果！**
- ⚠ **任何衍生项目，本项目不承担任何责任！**
- ⚠ **使用本项目代码时请注明作者！**

## 🎉结尾

💖 **感谢你的支持，Enjoy your day!** 🚀
