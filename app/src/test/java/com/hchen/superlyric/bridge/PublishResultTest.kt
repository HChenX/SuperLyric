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
package com.hchen.superlyric.bridge

import com.hchen.superlyricapi.BuildConfig
import com.hchen.superlyricapi.Publisher
import com.hchen.superlyricapi.Receiver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * v4 发布结果码的服务端与客户端对齐
 *
 * 服务端只返回整数；4-7 的细分拒绝码不得映射成笼统的 REJECTED，
 * 否则服务端做了细分，调用方只看到被拒绝。
 */
class PublishResultTest {

    @Test
    fun `every declared code round trips`() {
        for (result in Publisher.PublishResult.entries) {
            assertEquals(result, Publisher.PublishResult.fromCode(result.code))
        }
    }

    @Test
    fun `the new rejection codes are distinct`() {
        assertEquals(
            Publisher.PublishResult.REJECTED_TRACK_MISMATCH,
            Publisher.PublishResult.fromCode(4),
        )
        assertEquals(
            Publisher.PublishResult.REJECTED_MALFORMED,
            Publisher.PublishResult.fromCode(5),
        )
        assertEquals(
            Publisher.PublishResult.REJECTED_NOT_REGISTERED,
            Publisher.PublishResult.fromCode(6),
        )
        assertEquals(
            Publisher.PublishResult.REJECTED_QUOTA,
            Publisher.PublishResult.fromCode(7),
        )
    }

    @Test
    fun `an unknown code falls back to rejected`() {
        assertEquals(Publisher.PublishResult.REJECTED, Publisher.PublishResult.fromCode(99))
    }

    @Test
    fun `every register result code round trips`() {
        for (result in Receiver.RegisterResult.entries) {
            assertEquals(result, Receiver.RegisterResult.fromCode(result.code))
        }
    }

    @Test
    fun `register quota is distinct from generic rejection`() {
        assertEquals(Receiver.RegisterResult.REJECTED, Receiver.RegisterResult.fromCode(2))
        assertEquals(Receiver.RegisterResult.REJECTED_QUOTA, Receiver.RegisterResult.fromCode(3))
    }

    @Test
    fun `the service reports api version 40`() {
        // SuperLyricV4Service.getApiVersion() 直接返回这个常量
        assertEquals(40, BuildConfig.API_VERSION)
    }
}
