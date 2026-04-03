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

@SuppressWarnings("ClassCanBeRecord")
public class ApiAppData {
    private final AppData appData;
    private final String apiVersionName;
    private final String apiVersionCode;

    public ApiAppData(AppData appData, String apiVersionName, String apiVersionCode) {
        this.appData = appData;
        this.apiVersionName = apiVersionName;
        this.apiVersionCode = apiVersionCode;
    }

    public AppData getAppData() {
        return appData;
    }

    public String getApiVersionName() {
        return apiVersionName;
    }

    public String getApiVersionCode() {
        return apiVersionCode;
    }

    @NonNull
    @Override
    public String toString() {
        return "ApiAppData{" +
            "appData=" + appData +
            ", apiVersionName='" + apiVersionName + '\'' +
            ", apiVersionCode=" + apiVersionCode +
            '}';
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof ApiAppData that)) return false;
        return Objects.equals(appData, that.appData) &&
            Objects.equals(apiVersionName, that.apiVersionName) &&
            Objects.equals(apiVersionCode, that.apiVersionCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(appData, apiVersionName, apiVersionCode);
    }
}