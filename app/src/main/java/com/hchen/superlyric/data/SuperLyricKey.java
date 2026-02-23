/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.data;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Key 表
 *
 * @author 焕晨HChen
 */
public final class SuperLyricKey {
    public static final String SUPER_LYRIC = "super_lyric";
    @Deprecated
    public static final String SUPER_LYRIC_OLD = "Super_Lyric";

    public static final String SUPER_LYRIC_BINDER = "super_lyric_binder";
    public static final String SUPER_LYRIC_INFO = "super_lyric_info";

    public static final String SUPER_LYRIC_EXEMPT_PACKAGE = "super_lyric_exempt_package";
    @Deprecated
    public static final String SUPER_LYRIC_EXEMPT_PACKAGE_OLD = "super_lyric_add_package";

    public static final String SUPER_LYRIC_REGISTER = "super_lyric_register";
    @Deprecated
    public static final String SUPER_LYRIC_REGISTER_OLD = "super_lyric_binder";
    public static final String SUPER_LYRIC_UNREGISTER = "super_lyric_unregister";
    @Deprecated
    public static final String SUPER_LYRIC_UNREGISTER_OLD = "super_lyric_un_binder";

    public static final String SUPER_LYRIC_CONTROLLER = "super_lyric_controller";
    @Deprecated
    public static final String SUPER_LYRIC_UN_CONTROLLER = "super_lyric_un_controller";
    public static final String SUPER_LYRIC_CONTROLLER_REGISTER = "super_lyric_controller_register";
    @Deprecated
    public static final String SUPER_LYRIC_CONTROLLER_REGISTER_OLD = "super_lyric_controller_package";

    public static final String SUPER_LYRIC_SELF_CONTROL = "super_lyric_self_control";
    @Deprecated
    public static final String SUPER_LYRIC_SELF_CONTROL_OLD = "super_lyric_self_control_package";
    public static final String SUPER_LYRIC_UN_SELF_CONTROL = "super_lyric_un_self_control";
    @Deprecated
    public static final String SUPER_LYRIC_UN_SELF_CONTROL_OLD = "super_lyric_un_self_control_package";

    public static final String REPLY = "reply";

    private SuperLyricKey() {
    }

    public static String getStringExtra(@NonNull Intent intent, @NonNull String key, @NonNull String oldKey) {
        String result = intent.getStringExtra(key);
        if (Objects.isNull(result)) result = intent.getStringExtra(oldKey);
        return result;
    }

    public static IBinder getBinder(@NonNull Bundle bundle, @NonNull String key, @NonNull String oldKey) {
        IBinder result = bundle.getBinder(key);
        if (Objects.isNull(result)) result = bundle.getBinder(oldKey);
        return result;
    }

    public static String getString(@NonNull Bundle bundle, @NonNull String key, @NonNull String oldKey) {
        String result = bundle.getString(key);
        if (Objects.isNull(result)) result = bundle.getString(oldKey);
        return result;
    }
}
