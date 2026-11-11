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
package com.hchen.superlyric.provider.soda;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;
import com.hchen.superlyric.shared.HostIdentitySource;

@HookThis(targetPackage = "com.luna.music")
public class SodaProvider extends MusicProvider {
    private static final String TAG = "SodaProvider";

    private final SodaIdentitySource sodaIdentity = new SodaIdentitySource(TAG);

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return sodaIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(new SodaHookSource(), new KuGouApiSource());
    }
}
