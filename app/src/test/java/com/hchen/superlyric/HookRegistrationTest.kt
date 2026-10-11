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
package com.hchen.superlyric

import com.hchen.processor.HookMaps
import com.hchen.superlyric.data.SupportApps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 注册一致性：`@HookThis` 与 `scope.list` 必须指向同一组包名
 *
 * ## 为什么值得一条测试
 * 新增一个应用要写三处注册，其中 `scope.list` 漏掉了**不报错、不崩溃、也没有日志**——
 * 表现只是"这个应用什么都没发生"。这是本项目第一号静默失效陷阱，此前只靠文档提醒。
 *
 * 注解处理器生成的 [HookMaps] 与 `scope.list` 都在测试的 classpath / 工作目录里，
 * 因此可以把这条纪律变成**违反即构建失败**。
 *
 * @author 焕晨HChen
 */
class HookRegistrationTest {

    @Test
    fun `every hook target is declared in scope list`() {
        val missing = hookTargets() - readScopeList()

        assertTrue(
            missing.isEmpty(),
            "以下包名出现在 @HookThis 里，却不在 scope.list 中——模块会静默不加载进宿主：$missing",
        )
    }

    @Test
    fun `every scope list entry is used by a hook target`() {
        // 反向检查属卫生项：scope.list 决定模块被注入哪些进程，多写一个应用却没有对应的
        // Hook 只会白白扩大作用域。若确实需要，应当在提交信息里说明理由
        val unused = readScopeList() - hookTargets()

        assertTrue(
            unused.isEmpty(),
            "scope.list 里声明了却没有对应 @HookThis 的包名（白占作用域）：$unused",
        )
    }

    @Test
    fun `every scoped app is listed as a supported app`() {
        // 第三处注册。它此前**没有任何测试守着**：漏掉不报错、不崩溃、日志里也没痕迹，
        // 只是这个应用在界面上凭空消失——与前两处是同一个静默失效陷阱，只是后果轻些
        //
        // `system` 是 system_server 那条 Hook，不是音乐应用，因此从 scope.list 里排掉
        val scoped = readScopeList() - SYSTEM_PACKAGE

        assertEquals(
            scoped,
            SupportApps.sMediaAppPackages,
            "scope.list 与 SupportApps 不一致——应用会被加载进宿主，却在界面上找不到",
        )
    }

    @Test
    fun `every supported app has a display name`() {
        // 两张表必须同键：包名在名称表里缺失时，界面取不到应用名
        assertEquals(
            SupportApps.sMediaAppPackages,
            SupportApps.sPackageLabelRes.keys,
            "SupportApps 的包名表与名称资源表不一致",
        )
    }

    private fun hookTargets(): Set<String> =
        HookMaps.ON_PACKAGE_LOADED.keys + HookMaps.ON_SYSTEM_STARTING.keys

    private fun readScopeList(): Set<String> {
        val file = File("src/main/resources/META-INF/xposed/scope.list")
        assertTrue(
            file.isFile,
            "找不到 scope.list：${file.absolutePath}（单元测试的工作目录应为模块根目录）",
        )
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private companion object {
        /** 非音乐应用的 scope 项：它对应 `@HookThis(onSystemStarting = true)` */
        const val SYSTEM_PACKAGE = "system"
    }
}
