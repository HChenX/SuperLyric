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

 * Copyright (C) 2023-2025 HChenX
 */
package com.hchen.superlyric.binder;

import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_CONTROLLER_REGISTER;
import static com.hchen.superlyric.data.SuperLyricKey.SUPER_LYRIC_CONTROLLER_REGISTER_OLD;
import static com.hchen.superlyric.data.SuperLyricKey.getString;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.data.SuperLyricKey;
import com.hchen.superlyricapi.ISuperLyric;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 控制器注册服务
 * <p>
 * 注册控制器后将可向所有接收方发送歌词等数据
 *
 * @author 焕晨HChen
 */
public class SuperLyricControllerService {
    private static final String TAG = "SuperLyricControllerService";
    private static SuperLyricService mSuperLyricService;
    public static final CopyOnWriteArraySet<String> mFinalExemptSet = new CopyOnWriteArraySet<>() {
        {
            add("com.android.systemui");
        }
    };
    private static final Messenger mMessengerService = new Messenger(new ControllerHandler(Looper.getMainLooper()));
    private static final ConcurrentHashMap<String, ISuperLyric.Stub> mRegisteredControllerMap = new ConcurrentHashMap<>();

    public SuperLyricControllerService(SuperLyricService superLyricService) {
        mSuperLyricService = superLyricService;
    }

    private static class ControllerHandler extends Handler {
        public ControllerHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Bundle obj = msg.getData();
            Messenger client = msg.replyTo;
            if (obj == null || client == null) return;

            String packageName = getString(obj, SUPER_LYRIC_CONTROLLER_REGISTER, SUPER_LYRIC_CONTROLLER_REGISTER_OLD);
            if (packageName == null || packageName.isEmpty()) return;
            if (!mFinalExemptSet.contains(packageName)) return;

            ISuperLyric.Stub superLyric = null;
            if (mRegisteredControllerMap.containsKey(packageName)) {
                try {
                    superLyric = mRegisteredControllerMap.get(packageName);
                    if (superLyric == null)
                        mRegisteredControllerMap.remove(packageName);
                    else if (!superLyric.isBinderAlive()) {
                        mRegisteredControllerMap.remove(packageName);
                        superLyric = null;
                    }
                } catch (Throwable ignore) {
                    mRegisteredControllerMap.remove(packageName);
                    superLyric = null;
                }
            }

            try {
                if (superLyric == null) {
                    superLyric = createSuperLyricStub();
                    mRegisteredControllerMap.put(packageName, superLyric);
                }
                Message message = Message.obtain();
                Bundle bundle = new Bundle();
                bundle.putBinder(SuperLyricKey.REPLY, superLyric);
                message.setData(bundle);
                client.send(message);

                AndroidLog.logD(TAG, "Callback client: " + client + ", controller: " + superLyric + ", package: " + packageName);
            } catch (RemoteException e) {
                AndroidLog.logE(TAG, "Failed to handle message!!", e);
            }
        }
    }

    private static ISuperLyric.Stub createSuperLyricStub() {
        return new ISuperLyric.Stub() {
            @Override
            public void onSuperLyric(SuperLyricData superLyricData) throws RemoteException {
                if (!mRegisteredControllerMap.containsValue(this)) return;

                try {
                    mSuperLyricService.onSuperLyric(superLyricData);
                } catch (Throwable e) {
                    AndroidLog.logE(TAG, "[onSuperLyric]: Error!!", e);
                }
            }

            @Override
            public void onStop(SuperLyricData superLyricData) throws RemoteException {
                if (!mRegisteredControllerMap.containsValue(this)) return;

                try {
                    mSuperLyricService.onStop(superLyricData);
                } catch (Throwable e) {
                    AndroidLog.logE(TAG, "[onStop]: Error!!", e);
                }
            }
        };
    }

    @Deprecated
    public void removeSuperLyricStubIfNeed(@NonNull String packageName) {
        mRegisteredControllerMap.remove(packageName);
    }

    @NonNull
    public IBinder getBinder() {
        return mMessengerService.getBinder();
    }
}
