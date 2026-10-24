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
package com.hchen.superlyric.core;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.publisher.PlaybackTracker;
import com.hchen.superlyric.kernel.orchestrate.StopEvidence;
import com.hchen.superlyric.kernel.orchestrate.StopGate;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 系统播放时钟、监听器与停止判定状态的持有者。 */
final class SystemPlaybackCoordinator {

    final PlaybackTracker tracker = new PlaybackTracker("SuperLyric-SysTracker");
    final ConcurrentHashMap<String, StopGate> stopGates = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, StopEvidence> stopEvidence = new ConcurrentHashMap<>();

    @Nullable
    volatile SystemPlayStateListener listener;

    StopGate gate(@NonNull String packageName,
                  @NonNull StopGate.Scheduler scheduler,
                  @NonNull Consumer<String> confirmStop) {
        return stopGates.computeIfAbsent(packageName, pkg -> new StopGate(
            scheduler, () -> confirmStop.accept(pkg), StopGate.DEFAULT_CONFIRM_WINDOW_MS));
    }

    StopEvidence evidence(@NonNull String packageName) {
        return stopEvidence.computeIfAbsent(packageName, ignored -> new StopEvidence());
    }

    boolean cancelPending(@NonNull String packageName) {
        StopGate gate = stopGates.get(packageName);
        return gate != null && gate.onEvidence() == StopGate.Decision.CANCELLED;
    }

    void confirmStop(@NonNull String packageName,
                     @NonNull PublisherRegistry registry,
                     @NonNull Consumer<String> notifyStop) {
        boolean notify = false;
        synchronized (registry.lock) {
            PublisherRegistry.Session session = registry.sessions.get(packageName);
            if (session == null) return;
            if (!evidence(packageName).isStillValid()) {
                XposedLog.logD("SuperLyricService", "Stop for " + packageName
                    + " not confirmed: new evidence arrived inside the window");
                return;
            }
            session.clear();
            if (TextUtils.equals(registry.activePublisher, packageName)) {
                registry.activePublisher = null;
                tracker.stop();
                tracker.setLyrics(null);
                notify = true;
            }
        }
        if (notify) {
            XposedLog.logD("SuperLyricService", "Playback stopped (confirmed) for " + packageName);
            notifyStop.accept(packageName);
        }
    }
}
