/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.identity

import com.hchen.superlyric.shared.HostIdentitySource
import com.hchen.superlyric.shared.HostTrackFacts
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 只会保存最新一次捕获的身份源
 */
abstract class BaseIdentitySource : HostIdentitySource {
    private val facts = AtomicReference<HostTrackFacts?>()
    private val listeners = CopyOnWriteArrayList<(HostTrackFacts) -> Unit>()

    final override fun currentFacts(): HostTrackFacts? = facts.get()

    protected fun update(value: HostTrackFacts) {
        facts.set(value)
        for (listener in listeners) {
            runCatching { listener(value) }
        }
    }

    final override fun subscribe(listener: (HostTrackFacts) -> Unit): AutoCloseable {
        listeners += listener
        currentFacts()?.let { listener(it) }
        return AutoCloseable { listeners.remove(listener) }
    }
}
