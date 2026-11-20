/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Filename hints choose an action; native validation still decides compatibility. */
final class MGBAFileTypes {
    static final int UNKNOWN = 0, ROM = 1, STATE = 2, SAVE = 3, CHEATS = 4;
    static int fromName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean compressed = lower.endsWith(".gz");
        if (lower.endsWith(".gz")) lower = lower.substring(0, lower.length() - 3);
        if (lower.matches(".*\\.state(?:[0-9]+|x)?$") || lower.matches(".*\\.ss[0-9]*$")) return STATE;
        if (compressed) return UNKNOWN;
        if (lower.endsWith(".sav") || lower.endsWith(".srm")) return SAVE;
        if (lower.endsWith(".cheats") || lower.endsWith(".cht")) return CHEATS;
        if (lower.matches(".*\\.(gba|agb|gb|gbc|sgb|bin)$")) return ROM;
        return UNKNOWN;
    }
    static boolean rawStateHeader(byte[] bytes, int size) {
        if (size < 4) return false;
        // GBA states use 0x01000000 + version; GB uses 0x00400000 + version.
        return (bytes[1] & 255) == 0 && ((bytes[2] == 0 && bytes[3] == 1)
            || ((bytes[2] & 255) == 0x40 && bytes[3] == 0));
    }
    static int detect(String name, InputStream source) throws IOException {
        PushbackInputStream input = new PushbackInputStream(source, 2);
        byte[] prefix = new byte[2];
        int count = 0, n;
        while (count < 2 && (n = input.read(prefix, count, 2 - count)) > 0) count += n;
        if (count > 0) input.unread(prefix, 0, count);
        InputStream decoded = count == 2 && (prefix[0] & 255) == 31 && (prefix[1] & 255) == 139
            ? new GZIPInputStream(input) : input;
        byte[] header = new byte[512];
        int length = 0;
        while (length < header.length && (n = decoded.read(header, length, header.length - length)) > 0) length += n;
        if (rawStateHeader(header, length)) return STATE;
        int hint = fromName(name);
        if (hint != UNKNOWN) return hint;
        // Unnamed GBA/GB ROMs from a content provider can still be recognized.
        if (length > 0xB2 && (header[0xB2] & 255) == 0x96 && (header[4] & 255) == 0x24
            && (header[5] & 255) == 0xFF) return ROM;
        if (length > 0x105 && (header[0x104] & 255) == 0xCE && (header[0x105] & 255) == 0xED) return ROM;
        return UNKNOWN;
    }
    static String label(int type) {
        switch (type) { case ROM: return "游戏 ROM"; case STATE: return "即时存档"; case SAVE: return "游戏存档"; case CHEATS: return "金手指文件"; default: return "其他文件"; }
    }
}
