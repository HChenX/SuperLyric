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
package com.hchen.superlyric.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * QQ、酷狗、网易云共用的 JSON 取词客户端。
 *
 * callTimeout 给整次调用封顶：read 超时会在每次收到数据后重置，单靠它挡不住持续
 * 挤出少量数据的服务端。Spotify 需要更大的 protobuf 预算，单独配置。
 */
internal object HttpClients {
    val standard: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
}
