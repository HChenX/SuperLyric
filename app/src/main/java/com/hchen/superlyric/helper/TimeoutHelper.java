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
package com.hchen.superlyric.helper;

import static com.hchen.superlyric.hook.AbsPublisher.mAudioManager;
import static com.hchen.superlyric.hook.AbsPublisher.sendStop;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * 超时暂停歌词
 *
 * @author 焕晨HChen
 */
public final class TimeoutHelper {
    private static final HandlerThread thread = new HandlerThread("TimeoutHelper") {
        {
            start();
        }
    };
    private static final Handler handler = new Handler(thread.getLooper());
    private static boolean isRunning = false;
    private static final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) {
                return;
            }

            if (mAudioManager != null && !mAudioManager.isMusicActive()) {
                sendStop();
                stop();
            } else {
                handler.postDelayed(this, 1000);
            }
        }
    };

    public static synchronized void start() {
        if (isRunning) {
            return;
        }

        isRunning = true;
        handler.post(runnable);
    }

    public static synchronized void stop() {
        if (!isRunning) {
            return;
        }

        isRunning = false;
        handler.removeCallbacks(runnable);
    }
}
