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
package com.hchen.superlyric.provider.spotify;

import java.io.IOException;

final class OversizeException extends IOException {
    private static final long serialVersionUID = 1L;

    OversizeException(int maxBytes) {
        super("Spotify lyric response exceeds " + maxBytes + " bytes");
    }
}

final class HttpStatusException extends IOException {
    private static final long serialVersionUID = 1L;

    HttpStatusException(int code) {
        super("Spotify HTTP " + code);
    }
}

final class AuthenticationException extends IOException {
    private static final long serialVersionUID = 1L;

    AuthenticationException(int code) {
        super("Spotify authentication failed: HTTP " + code);
    }
}

final class LyricNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LyricNotFoundException(String message) {
        super(message);
    }
}
