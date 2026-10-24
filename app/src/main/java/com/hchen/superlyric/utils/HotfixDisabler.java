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
package com.hchen.superlyric.utils;

import static com.hchen.hooktool.core.CoreTool.findClassIfExists;
import static com.hchen.hooktool.core.CoreTool.hookMethodIfExists;
import static com.hchen.hooktool.core.CoreTool.returnResult;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

/**
 * 禁用宿主应用热更新框架的工具类。
 *
 * <p>热更新会让宿主应用在运行期替换自身字节码，导致本模块基于
 * 反编译得出的 Hook 点失配、目标类被补丁换掉后 Hook 失效。
 * 因此在模块注入早期主动关闭宿主的热更新，保证歌词 Hook 始终作用于
 * 应用原始代码。</p>
 *
 * @author 焕晨HChen
 */
public final class HotfixDisabler {
    private static final String TAG = "HotfixDisabler";
    private static volatile boolean sDisabled;

    /**
     * 禁用宿主应用热更新服务。
     *
     * <p>依次关闭腾讯 Tinker、字节 Frankie、腾讯剑（Sword）等主流热更新框架，
     * 防止宿主在运行期替换自身字节码导致 Hook 点失配、目标类被补丁换掉。</p>
     */
    public static void disableAllHotfixes() {
        if (sDisabled) return;
        sDisabled = true;
        AndroidLog.logD(TAG, "Disabling host hotfix frameworks");
        disableTencentTinker();
        disableBytedanceHotfix();
        disableTencentSword();
    }

    /**
     * 禁用腾讯 Tinker（dex 类加载器替换）热更新。
     */
    private static void disableTencentTinker() {
        if (findClassIfExists("com.tencent.tinker.loader.shareutil.ShareTinkerInternals") == null) {
            AndroidLog.logD(TAG, "Tinker not present");
            return;
        }
        AndroidLog.logD(TAG, "Tinker present; disabling");
        logHook("Tinker.isTinkerEnableWithSharedPreferences",
            hookMethodIfExists("com.tencent.tinker.loader.shareutil.ShareTinkerInternals",
                "isTinkerEnableWithSharedPreferences",
                new AbsHook() {
                    @Override
                    public void before() {
                        setResult(false);
                    }
                }
            ));

        Class<?> tinkerApplication = findClassIfExists("com.tencent.tinker.loader.app.TinkerApplication");
        if (tinkerApplication == null) {
            AndroidLog.logD(TAG, "TinkerApplication not present; only preferences hook installed");
            return;
        }
        logHook("Tinker.TinkerLoader.tryLoad",
            hookMethodIfExists("com.tencent.tinker.loader.TinkerLoader",
                "tryLoad",
                tinkerApplication,
                new AbsHook() {
                    @Override
                    public void before() {
                        setResult(null);
                    }
                }
            ));
    }

    /**
     * 禁用字节跳动 Frankie（Robust 思路）方法级热修复。
     */
    private static void disableBytedanceHotfix() {
        Class<?> frankieConfig = findClassIfExists("com.bytedance.frankie.IFrankieConfig");
        Class<?> frankieListener = findClassIfExists("com.bytedance.frankie.IFrankieListener");
        if (frankieConfig == null) {
            AndroidLog.logD(TAG, "Frankie not present");
            return;
        }
        AndroidLog.logD(TAG, "Frankie present; disabling");
        if (frankieConfig != null) {
            logHook("Frankie.init(config)",
                hookMethodIfExists("com.bytedance.frankie.Frankie",
                    "init",
                    frankieConfig,
                    returnResult(null)
                ));
            if (frankieListener != null) {
                logHook("Frankie.init(config, listener)",
                    hookMethodIfExists("com.bytedance.frankie.Frankie",
                        "init",
                        frankieConfig,
                        frankieListener,
                        returnResult(null)
                    ));
            }
        }
    }

    private static void logHook(@NonNull String name, @Nullable Object handle) {
        if (handle == null) {
            AndroidLog.logW(TAG, name + " hook not installed (method/class missing)");
        } else {
            AndroidLog.logD(TAG, name + " hooked");
        }
    }

    /**
     * 禁用腾讯剑（Sword）热更新。
     */
    private static void disableTencentSword() {
        if (findClassIfExists("com.tencent.qqmusic.sword.cmd.CmdManager") == null) {
            AndroidLog.logD(TAG, "Sword not present");
            return;
        }
        AndroidLog.logD(TAG, "Sword present; disabling");
        logHook("Sword.CmdManager.init",
            hookMethodIfExists("com.tencent.qqmusic.sword.cmd.CmdManager",
                "init",
                String.class,
                returnResult(null)
            ));
    }
}
