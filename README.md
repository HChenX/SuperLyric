# SuperLyric

[English](README-en.md) | 简体中文

基于 Binder 的 LSPosed 歌词模块，将音乐应用歌词发布给系统和其他客户端。

## 功能

- 从宿主 Hook 或在线接口获取歌词，按应用选择获取路径。
- 通过 super_lyric.v4 帧接口向 4.0 客户端发布歌词，同时保留 3.x 的 super_lyric 接口。
- 播放状态和进度由系统 MediaSession 驱动；模块只发布全量歌词。

## 支持的应用

| 应用 | 包名 | 获取方式 |
| :--- | :--- | :--- |
| Apple Music | com.apple.android.music | Hook 获取 |
| 波点音乐 | cn.wenyu.bodian | Hook 获取 + 酷狗兜底 |
| 酷狗音乐 | com.kugou.android | 网络获取 |
| 酷狗概念版 | com.kugou.android.lite | 网络获取 |
| 酷我音乐 | cn.kuwo.player | Hook 获取 + 酷狗兜底 |
| 酷我音乐车机版 | cn.kuwo.kwmusiccar | Hook 获取 + 酷狗兜底 |
| 咪咕音乐 | cmccwm.mobilemusic | Hook 获取 + 酷狗兜底 |
| 小米音乐 | com.miui.player | Hook 获取 |
| 网易云音乐 | com.netease.cloudmusic | 网络获取 |
| Poweramp | com.maxmpz.audioplayer | Hook 获取（本地 LRC / 歌词缓存）+ 酷狗兜底 |
| QQ 音乐 | com.tencent.qqmusic | Hook 获取 |
| 椒盐音乐 | com.salt.music | Hook 获取 + 酷狗兜底 |
| 汽水音乐 | com.luna.music | Hook 获取 + 酷狗兜底 |
| Spotify | com.spotify.music | 网络获取 |
| Flamingo | 无固定包名 | API 原生支持 |
| [光锥音乐](https://coneplayer.trantor.ink) | ink.trantor.coneplayer | API 原生支持 |
| [BreezyMusic](https://github.com/BlueEve04/BreezyMusic) | com.breezy.music | API 原生支持 |

## 获取方式

- Hook 获取：读取宿主内部的歌词模型，不依赖网络。
- Hook 获取 + 酷狗兜底：优先读宿主歌词；Hook 拿不到或超时后，按曲名、歌手与时长检索酷狗曲库。
- 网络获取：通过宿主的媒体信息和在线接口获取，使用本地缓存。
- API 原生支持：宿主已集成 SuperLyric API。

## 未支持

- YouTube Music
- 未列出的应用暂不支持。

## API 项目

[SuperLyricApi](https://github.com/HChenX/SuperLyricApi)

## 项目声明

- 使用本模块产生的后果由使用者承担。
- 本项目不对衍生项目负责。
- 使用本项目代码时请注明作者。
