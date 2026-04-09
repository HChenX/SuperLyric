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
package com.hchen.superlyric.data;

import androidx.annotation.NonNull;

import com.hchen.hooktool.data.AppData;

import java.util.Objects;

public class ApiAppData extends AppData {
    public String apiVersionName;
    public String apiVersionCode;

    @NonNull
    @Override
    public String toString() {
        return "ApiAppData{" +
            "apiVersionName='" + apiVersionName + '\'' +
            ", apiVersionCode='" + apiVersionCode + '\'' +
            "} " + super.toString();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof ApiAppData that)) return false;
        if (!super.equals(object)) return false;

        return Objects.equals(apiVersionName, that.apiVersionName) &&
            Objects.equals(apiVersionCode, that.apiVersionCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), apiVersionName, apiVersionCode);
    }
}