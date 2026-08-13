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
package com.hchen.superlyric.patches.kugou;

import static com.hchen.hooktool.core.CoreTool.getField;
import static com.hchen.hooktool.core.CoreTool.hookMethod;

import android.content.Context;

import com.hchen.hooktool.hook.AbsHook;

/**
 * 酷狗系列 App 的崩溃修复。
 *
 * @author 焕晨HChen
 */
public final class KuGouCrashFix {
    /**
     * 修复 WiFi ServiceFetcher 空指针导致的概率性崩溃。
     */
    public static void fixWifiServiceFetcherCrash() {
        hookMethod("com.kugou.framework.hack.ServiceFetcherHacker$FetcherImpl",
            "createServiceObject",
            Context.class, Context.class,
            new AbsHook() {
                @Override
                public void after() {
                    String serviceName = (String) getField(getThisObject(), "serviceName");
                    if (serviceName == null) return;

                    if (serviceName.equals(Context.WIFI_SERVICE)) {
                        if (getThrowable() != null) {
                            setThrowable(null);
                            setResult(null);
                        }
                    }
                }
            }
        );
    }
}
