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
package com.hchen.superlyric.hook;

import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_EXEMPT_PACKAGE;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_EXEMPT_PACKAGE_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_REGISTER;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_REGISTER_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_SELF_CONTROL;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_SELF_CONTROL_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_UNREGISTER;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_UNREGISTER_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_UN_SELF_CONTROL;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_UN_SELF_CONTROL_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.getBinder;
import static com.hchen.superlyric.data.SuperLyricKey.getStringExtra;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import com.hchen.collect.Collect;
import com.hchen.hooktool.HCBase;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.binder.SuperLyricControllerService;
import com.hchen.superlyric.binder.SuperLyricService;
import com.hchen.superlyric.data.SuperLyricKey;
import com.hchen.superlyric.state.PlayStateListener;
import com.hchen.superlyricapi.ISuperLyric;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/**
 * 代理 Super Lyric 服务
 *
 * @author 焕晨HChen
 */
@Collect(targetPackage = "android", onApplication = false)
public class SuperLyricProxy extends HCBase {
    private static SuperLyricService mSuperLyricService;
    private static SuperLyricControllerService mSuperLyricControllerService;

    @Override
    protected void init() {
        hookAllMethod("com.android.server.am.ActivityManagerService",
            "systemReady",
            new IHook() {
                @Override
                public void after() {
                    try {
                        if (mSuperLyricService == null) {
                            mSuperLyricService = new SuperLyricService();
                            mSuperLyricControllerService = new SuperLyricControllerService(mSuperLyricService);

                            Context mContext = (Context) getThisField("mContext");
                            new PlayStateListener(mContext, mSuperLyricService).register();

                            logI(TAG, "Super lyric service is all ready!!");
                        }
                    } catch (Throwable e) {
                        logE(TAG, "Failed to init super lyric service!!", e);
                    }
                }
            }
        );

        Method registerReceiverWithFeature = null;
        if (
            existsMethod(
                "com.android.server.am.ActivityManagerService",
                "registerReceiverWithFeature",
                "android.app.IApplicationThread", String.class, String.class, String.class,
                "android.content.IIntentReceiver", IntentFilter.class, String.class, int.class, int.class
            )
        ) {
            registerReceiverWithFeature = findMethod(
                "com.android.server.am.ActivityManagerService",
                "registerReceiverWithFeature",
                "android.app.IApplicationThread" /* caller */, String.class /* callerPackage */,
                String.class /* callerFeatureId */, String.class /* receiverId */,
                "android.content.IIntentReceiver" /* receiver */, IntentFilter.class /* filter */,
                String.class /* permission */, int.class /* userId */, int.class /* flags */
            );
        } else if (
            existsMethod(
                "com.android.server.am.ActivityManagerService",
                "registerReceiverWithFeature",
                "android.app.IApplicationThread", String.class, String.class,
                "android.content.IIntentReceiver", IntentFilter.class, String.class, int.class, int.class
            )
        ) {
            registerReceiverWithFeature = findMethod(
                "com.android.server.am.ActivityManagerService",
                "registerReceiverWithFeature",
                "android.app.IApplicationThread" /* caller */, String.class /* callerPackage */,
                String.class /* callerFeatureId */, "android.content.IIntentReceiver" /* receiver */,
                IntentFilter.class /* filter */, String.class /* permission */, int.class /* userId */, int.class /* flags */
            );
        }

        if (registerReceiverWithFeature == null) {
            logE(TAG, "[ActivityManagerService#registerReceiverWithFeature()] Failed to init super lyric service!!");
            return;
        }

        hook(registerReceiverWithFeature,
            new IHook() {
                @Override
                public void after() {
                    if (mSuperLyricService == null) {
                        return;
                    }

                    Intent intent = (Intent) getResult();
                    if (intent != null) {
                        String callerPackage = (String) getArg(1);
                        if (!SuperLyricService.mExemptSet.contains(callerPackage) &&
                            !SuperLyricControllerService.mFinalExemptSet.contains(callerPackage)) {
                            return;
                        }

                        Bundle bundle = new Bundle();
                        bundle.putBinder(SuperLyricKey.SUPER_LYRIC_BINDER, mSuperLyricService);
                        bundle.putBinder(SuperLyricKey.SUPER_LYRIC_CONTROLLER, mSuperLyricControllerService.getBinder());
                        intent.removeExtra(SuperLyricKey.SUPER_LYRIC_INFO);
                        intent.putExtra(SuperLyricKey.SUPER_LYRIC_INFO, bundle);
                        setResult(intent);

                        logD(TAG, "Super lyric service: " + mSuperLyricService + ", extras: " + intent.getExtras() + ", caller: " + callerPackage);
                    }
                }
            }
        );

        Method broadcastIntentWithFeature = null;
        if (
            existsMethod(
                "com.android.server.am.ActivityManagerService",
                "broadcastIntentWithFeature",
                "android.app.IApplicationThread", String.class, Intent.class, String.class, "android.content.IIntentReceiver", int.class,
                String.class, Bundle.class, String[].class, String[].class, String[].class, int.class, Bundle.class, boolean.class, boolean.class, int.class
            )
        ) {
            broadcastIntentWithFeature = findMethod(
                "com.android.server.am.ActivityManagerService",
                "broadcastIntentWithFeature",
                "android.app.IApplicationThread" /* caller */, String.class /* callingFeatureId */, Intent.class /* intent */,
                String.class /* resolvedType */, "android.content.IIntentReceiver" /* resultTo */, int.class /* resultCode */,
                String.class /* resultData */, Bundle.class /* resultExtras */, String[].class /* requiredPermissions */,
                String[].class /* excludedPermissions */, String[].class /* excludedPackages */, int.class /* appOp */,
                Bundle.class /* bOptions */, boolean.class /* serialized */, boolean.class /* sticky */, int.class /* userId */
            );
        } else if (
            existsMethod(
                "com.android.server.am.ActivityManagerService",
                "broadcastIntentWithFeature",
                "android.app.IApplicationThread", String.class, Intent.class, String.class, "android.content.IIntentReceiver", int.class,
                String.class, Bundle.class, String[].class, String[].class, int.class, Bundle.class, boolean.class, boolean.class, int.class)
        ) {
            broadcastIntentWithFeature = findMethod(
                "com.android.server.am.ActivityManagerService",
                "broadcastIntentWithFeature",
                "android.app.IApplicationThread" /* caller */, String.class /* callingFeatureId */, Intent.class /* intent */,
                String.class /* resolvedType */, "android.content.IIntentReceiver" /* resultTo */, int.class /* resultCode */,
                String.class /* resultData */, Bundle.class /* resultExtras */, String[].class /* requiredPermissions */,
                String[].class /* excludedPermissions */, int.class /* appOp */, Bundle.class /* bOptions */,
                boolean.class /* serialized */, boolean.class /* sticky */, int.class /* userId */
            );
        } else if (
            existsMethod(
                "com.android.server.am.ActivityManagerService",
                "broadcastIntentWithFeature",
                "android.app.IApplicationThread", String.class, Intent.class, String.class, "android.content.IIntentReceiver", int.class,
                String.class, Bundle.class, String[].class, int.class, Bundle.class, boolean.class, boolean.class, int.class)
        ) {
            broadcastIntentWithFeature = findMethod(
                "com.android.server.am.ActivityManagerService",
                "broadcastIntentWithFeature",
                "android.app.IApplicationThread" /* caller */, String.class /* callingFeatureId */, Intent.class /* intent */,
                String.class /* resolvedType */, "android.content.IIntentReceiver" /* resultTo */, int.class /* resultCode */,
                String.class /* resultData */, Bundle.class /* resultExtras */, String[].class /* requiredPermissions */,
                int.class /* appOp */, Bundle.class /* bOptions */, boolean.class /* serialized */, boolean.class /* sticky */, int.class /* userId */
            );
        }

        if (broadcastIntentWithFeature == null) {
            logE(TAG, "[ActivityManagerService#broadcastIntentWithFeature()] Failed to init super lyric service!!");
            return;
        }

        hook(broadcastIntentWithFeature,
            new IHook() {
                @Override
                public void before() {
                    if (mSuperLyricService == null) return;
                    if (!(getArg(2) instanceof Intent intent)) return;
                    if (!Objects.equals(intent.getAction(), SUPER_LYRIC_OLD) && !Objects.equals(intent.getAction(), SUPER_LYRIC)) {
                        return;
                    }

                    try {
                        String caller = "unknown";
                        try {
                            // 获取调用者包名
                            caller = (String) getField(
                                getField(
                                    callThisMethod("getRecordForAppLOSP", getArg(0)),
                                    "info"
                                ),
                                "packageName"
                            );
                        } catch (Throwable ignore) {
                        }

                        // 添加豁免包名，只有豁免的包才会下发 super lyric 服务
                        String exemptPackage = getStringExtra(intent, SUPER_LYRIC_EXEMPT_PACKAGE, SUPER_LYRIC_EXEMPT_PACKAGE_OLD);
                        if (exemptPackage != null && !exemptPackage.isEmpty()) {
                            mSuperLyricService.addExemptPackage(exemptPackage);
                            logD(TAG, "Add exempt package: " + exemptPackage + ", caller: " + caller);
                            return;
                        }

                        // 停止支持手动 un-controller，请复用已经获得的控制器
                        // String unController = intent.getStringExtra(SuperLyricData.SUPER_LYRIC_UN_CONTROLLER);
                        // if (unController != null && !unController.isEmpty()) {
                        //     if (mSuperLyricControllerService != null) {
                        //         mSuperLyricControllerService.removeSuperLyricStubIfNeed(unController);
                        //         logD(TAG, "Un controller: " + unController + ", caller: " + callerPackage);
                        //     }
                        //     return;
                        // }

                        // 添加自控包，添加的包不再由系统控制播放和暂停状态，由自己控制
                        String selfControl = getStringExtra(intent, SUPER_LYRIC_SELF_CONTROL, SUPER_LYRIC_SELF_CONTROL_OLD);
                        if (selfControl != null && !selfControl.isEmpty()) {
                            mSuperLyricService.addSelfControlPackage(selfControl);
                            logD(TAG, "Add self control package: " + selfControl + ", caller: " + caller);
                            return;
                        }

                        // 清除自控包
                        String unSelfControl = getStringExtra(intent, SUPER_LYRIC_UN_SELF_CONTROL, SUPER_LYRIC_UN_SELF_CONTROL_OLD);
                        if (unSelfControl != null && !unSelfControl.isEmpty()) {
                            mSuperLyricService.removeSelfControlPackage(unSelfControl);
                            logD(TAG, "Remove self control package: " + unSelfControl + ", caller: " + caller);
                            return;
                        }

                        Bundle bundle = intent.getExtras();
                        if (bundle != null) {
                            // 添加歌词接收器
                            IBinder superLyricBinder = getBinder(bundle, SUPER_LYRIC_REGISTER, SUPER_LYRIC_REGISTER_OLD);
                            if (superLyricBinder != null) {
                                ISuperLyric iSuperLyric = ISuperLyric.Stub.asInterface(superLyricBinder);
                                mSuperLyricService.registerSuperLyricBinder(superLyricBinder, iSuperLyric);
                                logD(TAG, "Register binder: " + iSuperLyric + ", caller: " + caller);
                                return;
                            }

                            // 移除歌词接收器
                            superLyricBinder = getBinder(bundle, SUPER_LYRIC_UNREGISTER, SUPER_LYRIC_UNREGISTER_OLD);
                            if (superLyricBinder != null) {
                                ISuperLyric iSuperLyric = ISuperLyric.Stub.asInterface(superLyricBinder);
                                mSuperLyricService.unregisterSuperLyricBinder(superLyricBinder);
                                logD(TAG, "Unregister binder: " + iSuperLyric + ", caller: " + caller);
                            }
                        }
                    } catch (Throwable e) {
                        logE(TAG, e);
                    }
                }
            }
        );

        hookMethod("com.android.server.am.ActivityManagerService",
            "appDiedLocked",
            "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */,
            "android.app.IApplicationThread" /* thread */, boolean.class /* fromBinderDied */, String.class /* reason */,
            new IHook() {
                /** @noinspection SimplifiableConditionalExpression*/
                @Override
                public void after() {
                    if (mSuperLyricService == null) return;

                    Object app = getArg(0);
                    String packageName = (String) getField(getField(app, "info"), "packageName");
                    String processName = (String) getField(app, "processName");
                    if (TextUtils.equals(packageName, processName)) { // 主进程
                        boolean isKilled = existsMethod(app.getClass(), "isKilled") ?
                            (boolean) Optional.ofNullable(callMethod(app, "isKilled")).orElse(true) :
                            (
                                existsField(app.getClass(), "mKilled") ?
                                    (boolean) Optional.ofNullable(getField(app, "mKilled")).orElse(true) :
                                    true
                            );
                        if (isKilled) {
                            if (SuperLyricService.mExemptSet.contains(packageName) ||
                                SuperLyricControllerService.mFinalExemptSet.contains(packageName)) {
                                mSuperLyricService.onPackageDied(packageName);
                                logD(TAG, "App: " + packageName + " is died!!");
                            }
                        }
                    }
                }
            }
        );
    }
}
