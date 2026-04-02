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

import java.util.Timer;
import java.util.TimerTask;

/**
 * 超时暂停歌词
 *
 * @author 焕晨HChen
 */
public final class TimeoutHelper {
    private final static Timer timer = new Timer();
    private static boolean isRunning = false;
    private final static TimerTask TIMER_TASK = new TimerTask() {
        @Override
        public void run() {
            if (mAudioManager != null && !mAudioManager.isMusicActive()) {
                sendStop();
                stop();
            }
        }
    };

    public static void start() {
        if (!isRunning) {
            timer.schedule(TIMER_TASK, 0, 1000);
            isRunning = true;
        }
    }

    private static void stop() {
        if (isRunning) {
            timer.cancel();
            isRunning = false;
        }
    }
}
