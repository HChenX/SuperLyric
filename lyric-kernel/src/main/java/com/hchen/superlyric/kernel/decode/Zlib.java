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
package com.hchen.superlyric.kernel.decode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * RFC 1950 ZLib 解压工具。
 *
 * @author 焕晨HChen
 */
public final class Zlib {
    private static final int BUFFER_SIZE = 8192;

    private Zlib() {
    }

    /**
     * 将 ZLib 压缩流解压为 UTF-8 文本。
     *
     * @param compressed 压缩数据，允许尾部附带填充字节（PKCS5 padding 等），
     *                   解压到流末尾即停止
     * @return 解压后的文本
     * @throws DecodeException 数据不是合法的 ZLib 流
     */
    public static String inflateToString(byte[] compressed) throws DecodeException {
        if (compressed == null || compressed.length == 0) {
            throw new DecodeException("empty zlib payload");
        }

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 3));
            byte[] buffer = new byte[BUFFER_SIZE];

            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                    // 合法的 ZLib 流不会连续两次返回 0
                    throw new DecodeException("zlib stream stalled");
                }
                out.write(buffer, 0, count);
            }

            if (!inflater.finished()) {
                throw new DecodeException("zlib stream truncated");
            }
            if (out.size() == 0) {
                throw new DecodeException("zlib produced no output");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("zlib inflate failed: " + t.getMessage(), t);
        } finally {
            inflater.end();
        }
    }
}
