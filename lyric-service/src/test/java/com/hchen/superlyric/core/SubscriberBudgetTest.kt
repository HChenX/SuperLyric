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
package com.hchen.superlyric.core

import android.os.Binder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * 两代广播器共用的配额、超时与驱逐账本
 *
 * 行为门禁固定在这里，两代广播器不再各写一份。
 */
class SubscriberBudgetTest {

    @Test
    fun `global quota admits sixty-four subscribers and rejects the sixty-fifth`() {
        val budget = SubscriberBudget()

        repeat(SubscriberBudget.MAX_SUBSCRIBERS) { index ->
            assertEquals(SubscriberBudget.Admission.OK, budget.admit(Binder(), index + 1))
        }

        assertEquals(
            SubscriberBudget.Admission.AT_GLOBAL_LIMIT,
            budget.admit(Binder(), 100),
        )
    }

    @Test
    fun `per-uid quota rejects the seventeenth subscriber of one uid`() {
        val budget = SubscriberBudget()

        repeat(SubscriberBudget.MAX_PER_UID) { index ->
            assertEquals(SubscriberBudget.Admission.OK, budget.admit(Binder(), 7))
        }

        assertEquals(SubscriberBudget.Admission.AT_UID_LIMIT, budget.admit(Binder(), 7))
        assertEquals(SubscriberBudget.Admission.OK, budget.admit(Binder(), 8))
    }

    @Test
    fun `a duplicate admission keeps the first slot and quota accounting`() {
        val budget = SubscriberBudget()
        val binder = Binder()
        assertEquals(SubscriberBudget.Admission.OK, budget.admit(binder, 3))

        assertEquals(SubscriberBudget.Admission.DUPLICATE, budget.admit(binder, 3))
        assertEquals(SubscriberBudget.Admission.DUPLICATE, budget.admit(binder, 99))

        repeat(SubscriberBudget.MAX_PER_UID - 1) { index ->
            assertEquals(SubscriberBudget.Admission.OK, budget.admit(Binder(), 3))
        }
        assertEquals(SubscriberBudget.Admission.AT_UID_LIMIT, budget.admit(Binder(), 3))

        assertTrue(budget.release(binder))
        assertFalse(budget.release(binder))
        assertEquals(SubscriberBudget.Admission.OK, budget.admit(Binder(), 3))
    }

    @Test
    fun `release of an unknown subscriber does not underflow the uid count`() {
        val budget = SubscriberBudget()
        val binder = Binder()
        assertEquals(SubscriberBudget.Admission.OK, budget.admit(binder, 5))

        assertFalse(budget.release(Binder()))
        assertTrue(budget.release(binder))

        repeat(SubscriberBudget.MAX_PER_UID) { index ->
            assertEquals(SubscriberBudget.Admission.OK, budget.admit(Binder(), 5))
        }
    }

    @Test
    fun `consecutive timeouts accumulate until the eviction threshold and success resets them`() {
        val budget = SubscriberBudget()
        val binder = Binder()
        assertEquals(SubscriberBudget.Admission.OK, budget.admit(binder, 1))

        repeat(SubscriberBudget.MAX_CONSECUTIVE_TIMEOUTS) { index ->
            assertEquals(index + 1, budget.recordTimeout(binder))
        }

        budget.recordSuccess(binder)
        assertEquals(1, budget.recordTimeout(binder))

        assertTrue(budget.release(binder))
        assertEquals(1, budget.recordTimeout(binder))
    }

    @Test
    fun `timeouts of an unregistered subscriber are not tracked`() {
        val budget = SubscriberBudget()
        val binder = Binder()

        repeat(SubscriberBudget.MAX_CONSECUTIVE_TIMEOUTS * 2) {
            assertEquals(1, budget.recordTimeout(binder))
        }
    }

    @Test
    fun `concurrent admissions never exceed the global quota`() {
        val budget = SubscriberBudget()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val results = (0 until 96).map { index ->
                pool.submit<SubscriberBudget.Admission> { budget.admit(Binder(), index + 1) }
            }.map { it.get() }

            assertEquals(
                SubscriberBudget.MAX_SUBSCRIBERS,
                results.count { it == SubscriberBudget.Admission.OK },
            )
            assertFalse(results.contains(SubscriberBudget.Admission.DUPLICATE))
        } finally {
            pool.shutdownNow()
        }
    }
}
