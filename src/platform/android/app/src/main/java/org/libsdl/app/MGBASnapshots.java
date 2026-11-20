/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MGBASnapshots {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final LruCache<File, Bitmap> THUMBNAILS = new LruCache<>(48);

    private final Activity activity;
    private final MGBASnapshotStore store;
    private final File rom;
    private final String gameName;
    private final Runnable back;

    private MGBASnapshots(Activity activity, File folder, File rom, String gameName, Runnable back) {
        this.activity = activity;
        this.store = new MGBASnapshotStore(folder);
        this.rom = rom;
        this.gameName = gameName;
        this.back = back;
    }

    static void showCurrent(Activity activity, Runnable back) {
        File folder = MGBALibrary.currentFolder();
        String path = MGBAMenu.nativeRomPath();
        if (folder == null || path == null) {
            new MGBAUi.Builder(activity).setTitle("存档快照").setMessage("请先从游戏库打开游戏")
                .setPositiveButton("旧版存档位", (d, w) -> MGBAMenu.showLegacySlots(activity, back))
                .setNegativeButton("返回", (d, w) -> back.run()).setOnCancelListener(d -> back.run()).show();
            return;
        }
        new MGBASnapshots(activity, folder, new File(path), null, back).show();
    }

    static void showGame(Activity activity, MGBAGameLibrary.Game game, Runnable back) {
        new MGBASnapshots(activity, game.folder, game.rom, game.name, back).show();
    }

    private boolean isCurrentGame() {
        String path = MGBAMenu.nativeRomPath();
        if (!MGBAMenu.nativeMenuAvailable() || path == null) return false;
        try {
            return rom.getCanonicalFile().equals(new File(path).getCanonicalFile());
        } catch (IOException e) {
            return false;
        }
    }

    private void show() {
        AlertDialog wait = new MGBAUi.Builder(activity).setTitle("存档快照")
            .setMessage("正在读取快照…").setCancelable(false).show();
        IO.execute(() -> {
            try {
                ArrayList<MGBASnapshotStore.Entry> entries = store.list();
                activity.runOnUiThread(() -> {
                    if (!wait.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    wait.dismiss();
                    showList(entries);
                });
            } catch (IOException e) {
                activity.runOnUiThread(() -> {
                    if (!wait.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    wait.dismiss();
                    error(e.getMessage(), back);
                });
            }
        });
    }

    private void showList(ArrayList<MGBASnapshotStore.Entry> entries) {
        boolean current = isCurrentGame();
        String title = gameName == null ? "存档快照" : "存档快照 · " + gameName;
        MGBAUi.Builder builder = new MGBAUi.Builder(activity);
        builder.setTitle(title).setNegativeButton("返回", (d, w) -> back.run()).setOnCancelListener(d -> back.run());
        if (current) {
            builder.setPositiveButton("新建快照", (d, w) -> editName(null))
                .setNeutralButton("旧版存档位", (d, w) -> MGBAMenu.showLegacySlots(activity, this::show));
        }
        if (entries.isEmpty()) {
            builder.setMessage(current ? "没有快照。新建快照会一并记录此刻的游戏画面。" : "这个游戏还没有快照。");
            builder.show();
            return;
        }

        Context context = MGBAUi.context(activity);
        LinearLayout content = column(context);
        ListView list = new ListView(context);
        ArrayList<MGBASnapshotStore.Entry> visible = new ArrayList<>(entries);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public MGBASnapshotStore.Entry getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reuse, ViewGroup parent) {
                SnapshotRow row = reuse instanceof SnapshotRow ? (SnapshotRow) reuse : new SnapshotRow(context);
                row.bind(getItem(position));
                return row;
            }
        };
        if (entries.size() >= 8) {
            EditText search = new EditText(context);
            search.setSingleLine(true);
            search.setHint("搜索快照名称");
            content.addView(search);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String query = s.toString().trim().toLowerCase(Locale.ROOT);
                    visible.clear();
                    for (MGBASnapshotStore.Entry entry : entries) {
                        if (label(entry).toLowerCase(Locale.ROOT).contains(query)) visible.add(entry);
                    }
                    adapter.notifyDataSetChanged();
                }
                @Override public void afterTextChanged(Editable e) { }
            });
        }
        TextView empty = new TextView(context);
        empty.setText("没有匹配的快照");
        content.addView(empty);
        list.setEmptyView(empty);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        content.setLayoutParams(new FrameLayout.LayoutParams(-1, height() * 66 / 100));
        AlertDialog dialog = builder.setView(content).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            preview(visible.get(position));
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        MGBAUi.wide(activity, dialog, true);
    }

    private void preview(MGBASnapshotStore.Entry entry) {
        Context context = MGBAUi.context(activity);
        LinearLayout content = column(context);
        TextView date = new TextView(context);
        date.setText(date(entry.created));
        content.addView(date);
        ImageView shot = new ImageView(context);
        shot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shot.setBackgroundColor(0xFF101820);
        shot.setContentDescription("保存时的游戏画面");
        content.addView(shot, new LinearLayout.LayoutParams(-1, Math.min(height() / 2, dp(280))));
        TextView status = new TextView(context);
        status.setText("正在读取截图…");
        content.addView(status);
        boolean current = isCurrentGame();
        AlertDialog dialog = new MGBAUi.Builder(activity).setTitle(label(entry)).setView(content)
            .setPositiveButton(current ? "读取快照" : "打开游戏并读取", (d, w) -> {
                if (isCurrentGame()) {
                    load(activity, entry.state, () -> preview(entry));
                } else {
                    MGBAMenu.openSnapshotGame(activity, rom, gameName, entry.state, () -> preview(entry));
                }
            })
            .setNeutralButton("更多", (d, w) -> more(entry))
            .setNegativeButton("返回", (d, w) -> show())
            .setOnCancelListener(d -> show()).show();
        MGBAUi.wide(activity, dialog, false);
        IO.execute(() -> {
            Bitmap bitmap = decode(entry.state, 1);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                shot.setImageBitmap(bitmap);
                status.setText("截图不可用");
                status.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void more(MGBASnapshotStore.Entry entry) {
        Runnable backToPreview = () -> preview(entry);
        new MGBAUi.Builder(activity).setTitle(label(entry))
            .setItems(new String[]{"重命名", "导出快照…"}, (d, which) -> {
                if (which == 0) editName(entry);
                else {
                    String filename = MGBAGameLibrary.clean(label(entry) + ".ss");
                    MGBABrowser.exportCopy(activity, Uri.fromFile(entry.state), filename, backToPreview);
                }
            }).setNegativeButton("返回", (d, w) -> backToPreview.run())
            .setOnCancelListener(d -> backToPreview.run()).show();
    }

    private void editName(MGBASnapshotStore.Entry entry) {
        boolean create = entry == null;
        Runnable cancel = create ? this::show : () -> preview(entry);
        LinearLayout content = column(MGBAUi.context(activity));
        EditText input = new EditText(content.getContext());
        input.setSingleLine(true);
        input.setHint("快照名称");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MGBASnapshotStore.NAME_LIMIT)});
        input.setText(create ? "快照 " + date(System.currentTimeMillis()) : label(entry));
        input.selectAll();
        content.addView(input);
        AlertDialog dialog = new MGBAUi.Builder(activity).setTitle(create ? "新建快照" : "重命名快照")
            .setView(content).setPositiveButton(create ? "保存" : "重命名", null)
            .setNegativeButton("取消", (d, w) -> cancel.run()).setOnCancelListener(d -> cancel.run()).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入快照名称"); return; }
            try {
                if (create) {
                    if (!isCurrentGame() || MGBAMenu.filesBusy()) throw new IOException("当前游戏不可保存，请稍后再试");
                    store.create(name, file -> runNative(file, true));
                } else {
                    store.rename(entry, name);
                }
                dialog.dismiss();
                Toast.makeText(activity, create ? "快照已保存" : "名称已更新", Toast.LENGTH_SHORT).show();
                show();
            } catch (IOException e) {
                input.setError(e.getMessage());
            }
        });
    }

    static void load(Activity activity, File file, Runnable retry) {
        try {
            if (MGBAMenu.filesBusy()) throw new IOException("正在处理文件，请稍后再试");
            runNative(file, false);
            Toast.makeText(activity, "快照已读取，原进度已备份", Toast.LENGTH_SHORT).show();
            MGBAMenu.returnToGame();
        } catch (IOException e) {
            new MGBAUi.Builder(activity).setTitle("无法读取快照").setMessage(e.getMessage())
                .setPositiveButton("返回", (d, w) -> retry.run()).setOnCancelListener(d -> retry.run()).show();
        }
    }

    private static void runNative(File file, boolean save) throws IOException {
        int mode = save ? ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE
            : ParcelFileDescriptor.MODE_READ_ONLY;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, mode)) {
            String error = nativeAction(fd.getFd(), save);
            if (error != null) throw new IOException(error);
        }
    }

    private static native String nativeAction(int fd, boolean save);

    private void error(String message, Runnable action) {
        new MGBAUi.Builder(activity).setTitle("无法读取快照目录").setMessage(message)
            .setPositiveButton("返回", (d, w) -> action.run()).setOnCancelListener(d -> action.run()).show();
    }

    private String label(MGBASnapshotStore.Entry entry) {
        return entry.name.isEmpty() ? "快照 " + date(entry.created) : entry.name;
    }
    private static String date(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(timestamp));
    }
    private int height() { return activity.getResources().getDisplayMetrics().heightPixels; }
    private int dp(int value) { return MGBAUi.dp(activity, value); }
    private LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), 0, dp(16), 0);
        return layout;
    }

    private static Bitmap decode(File file, int sampleSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth < 1 || options.outWidth > 512 || options.outHeight < 1 || options.outHeight > 512)
            return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private final class SnapshotRow extends LinearLayout {
        private final ImageView thumbnail;
        private final TextView title, detail;

        SnapshotRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(7), dp(4), dp(7));
            thumbnail = new ImageView(context);
            thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumbnail.setBackgroundColor(0xFF101820);
            addView(thumbnail, new LinearLayout.LayoutParams(dp(84), dp(56)));
            LinearLayout text = column(context);
            title = new TextView(context);
            title.setTextSize(16);
            title.setMaxLines(2);
            detail = new TextView(context);
            detail.setTextSize(12);
            text.addView(title);
            text.addView(detail);
            addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        }

        void bind(MGBASnapshotStore.Entry entry) {
            title.setText(label(entry));
            detail.setText(date(entry.created));
            File file = entry.state;
            thumbnail.setTag(file);
            Bitmap cached = THUMBNAILS.get(file);
            thumbnail.setImageBitmap(cached);
            if (cached != null) return;
            IO.execute(() -> {
                Bitmap bitmap = THUMBNAILS.get(file);
                if (bitmap == null) {
                    bitmap = decode(file, 2);
                    if (bitmap != null) THUMBNAILS.put(file, bitmap);
                }
                Bitmap result = bitmap;
                activity.runOnUiThread(() -> {
                    if (file.equals(thumbnail.getTag())) thumbnail.setImageBitmap(result);
                });
            });
        }
    }
}
