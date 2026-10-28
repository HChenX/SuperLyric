/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.miui;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * 小米音乐（QQ 音乐小米版）歌词提供者。
 *
 * <h3>为什么取词走 QQ 接口</h3>
 * 小米音乐的曲库与播放器就是 QQ 音乐引擎（QQLite）：宿主的 {@code SongInfo} 带着 QQ 音乐的
 * songID，接口返回的 QRC 也是同一套协议数据。因此本 Provider 只从宿主取<b>曲目标识</b>，
 * 歌词本体由 {@code QQNetwork} 向官方接口拉取并自行解密，与 QQ 音乐共用同一条取词链路。
 *
 * <h3>为什么不在 buildIdentity 里用 songID</h3>
 * 实测 {@code SongInfo} 的更新<b>早于</b> {@code MediaSession} 的 {@code MEDIA_ID}：若把
 * songID 当作稳定标识，元数据还停留在上一首时身份就会翻成下一首，切歌判定与 Hook 捕获
 * 会互相错位。这里沿用宿主自己上报的 {@code MEDIA_ID} 作为“哪一首”的判据（默认实现），
 * songID 只用于拉取歌词。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.miui.player")
public final class MiuiProvider extends MusicProvider {
    private static final String TAG = "MiuiProvider";

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new MiuiHookSource();
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new MiuiApiSource();
    }
}
