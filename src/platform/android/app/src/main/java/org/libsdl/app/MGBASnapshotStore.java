/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/** A snapshot keeps one loadable PNG savestate and a separate display name. */
final class MGBASnapshotStore {
    static final int NAME_LIMIT = 80;
    private static final String STATE = "snapshot.ss";
    private static final String NAME = "name.txt";

    interface Capture {
        void write(File target) throws IOException;
    }

    static final class Entry {
        final File state;
        final String name;
        final long created;

        Entry(File state, String name) {
            this.state = state;
            this.name = name;
            this.created = state.lastModified();
        }
    }

    private final File folder;

    MGBASnapshotStore(File gameFolder) {
        folder = new File(gameFolder, "Snapshots");
    }

    private static File directChild(File parent, String name) throws IOException {
        File child = new File(parent.getCanonicalFile(), name);
        if (!child.equals(child.getCanonicalFile())) throw new IOException("快照路径不可用");
        return child;
    }

    private File directory() throws IOException {
        return directChild(folder.getParentFile(), folder.getName());
    }

    ArrayList<Entry> list() throws IOException {
        File root = directory();
        ArrayList<Entry> result = new ArrayList<>();
        if (!root.exists()) return result;
        File[] directories = root.listFiles();
        if (directories == null) throw new IOException("无法读取快照目录");
        for (File entry : directories) {
            String id = entry.getName();
            if (!entry.isDirectory() || !id.matches("[0-9a-f-]{36}")) continue;
            File state;
            try {
                state = directChild(directChild(root, id), STATE);
            } catch (IOException ignored) {
                continue;
            }
            if (state.isFile() && state.length() > 0) result.add(new Entry(state, readName(state.getParentFile())));
        }
        result.sort((a, b) -> {
            int time = Long.compare(b.created, a.created);
            return time != 0 ? time : a.state.compareTo(b.state);
        });
        return result;
    }

    private static String readName(File directory) {
        try {
            File file = directChild(directory, NAME);
            if (file.length() <= 0 || file.length() > NAME_LIMIT * 4) return "";
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = in.read(bytes, offset, bytes.length - offset);
                    if (count < 0) return "";
                    offset += count;
                }
                return new String(bytes, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeName(File directory, String name) throws IOException {
        String label = name.trim();
        if (label.isEmpty() || label.length() > NAME_LIMIT || label.indexOf('\n') >= 0 || label.indexOf('\r') >= 0)
            throw new IOException("请输入 1–80 个字符的快照名称");
        File target = directChild(directory, NAME);
        File temp = File.createTempFile(".name-", ".tmp", directory);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(label.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) throw new IOException("无法更新快照名称");
        } finally {
            temp.delete();
        }
    }

    void create(String name, Capture capture) throws IOException {
        File root = directory();
        if (!root.isDirectory() && !root.mkdir() && !root.isDirectory()) throw new IOException("无法创建快照目录");
        String id = UUID.randomUUID().toString();
        File pending = new File(root, ".pending-" + id);
        if (!pending.mkdir()) throw new IOException("无法创建快照");
        File state = new File(pending, STATE);
        try {
            writeName(pending, name);
            capture.write(state);
            if (!state.isFile() || state.length() == 0) throw new IOException("未得到有效快照");
            File result = new File(root, id);
            if (result.exists() || !pending.renameTo(result)) throw new IOException("无法保存快照");
        } finally {
            if (pending.exists()) {
                state.delete();
                new File(pending, NAME).delete();
                pending.delete();
            }
        }
    }

    void rename(Entry entry, String name) throws IOException {
        File parent = entry.state.getParentFile();
        File path = directChild(directChild(directory(), parent.getName()), STATE);
        if (!path.equals(entry.state) || !path.isFile()) throw new IOException("找不到此快照");
        writeName(parent, name);
    }
}
