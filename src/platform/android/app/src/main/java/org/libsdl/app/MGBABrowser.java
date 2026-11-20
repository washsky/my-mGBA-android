/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only browsing. Imports/exports always copy files; originals stay intact. */
final class MGBABrowser {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final int AUTO = 7110, TREE = 7111, EXPORT = 7112;
    private static Uri exportSource;
    private static Runnable exportReturn;
    private static int returnRequest = AUTO;
    private final Activity activity;
    private final int request;
    private AlertDialog dialog;
    private TextView path, empty;
    private EditText search;
    private Spinner filter, sort;
    private ListView list;
    private final ArrayList<Entry> entries = new ArrayList<>(), shown = new ArrayList<>();
    private BaseAdapter adapter;
    private File localRoot, local;
    private Uri tree, directory;
    private boolean recent;
    private int generation;
    private static final class Entry {
        final Uri uri; final String name; final long size, time; final boolean folder;
        String cache; int request = AUTO;
        Entry(Uri uri, String name, long size, long time, boolean folder) {
            this.uri = uri; this.name = name == null || name.isEmpty() ? "未命名文件" : name;
            this.size = size; this.time = time; this.folder = folder;
        }
    }
    private MGBABrowser(Activity activity, int request) { this.activity = activity; this.request = request; }
    private static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("mgba_files", Activity.MODE_PRIVATE); }
    static File imports(Activity a) {
        File f = a.getExternalFilesDir("Imports");
        if (f == null) f = new File(a.getFilesDir(), "Imports");
        f.mkdirs(); return f;
    }
    private static String size(long bytes) {
        if (bytes < 0) return "大小未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
    private static String subtitle(Entry e) {
        if (e.folder) return "文件夹";
        String value = MGBAFileTypes.label(MGBAFileTypes.fromName(e.name)) + " · " + size(e.size);
        if (e.time > 0) value += " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(e.time));
        return value;
    }
    static void show(Activity activity, int request) {
        showSources(activity, request, false);
    }
    static void showLauncher(Activity activity) {
        showSources(activity, AUTO, true);
    }
    private static void showSources(Activity activity, int request, boolean launcher) {
        Runnable back = () -> showSources(activity, request, launcher);
        AlertDialog dialog = new MGBAUi.Builder(activity).setTitle(launcher ? "mGBA 安卓版" : "文件管理")
            .setItems(new String[]{"系统选择文件", "游戏库", "最近使用"}, (d, selected) -> {
                if (selected == 0) MGBAMenu.pickSystemFiles(activity, request, back);
                else if (selected == 1) MGBALibrary.show(activity, back);
                else new MGBABrowser(activity, request).openRecent(back);
            })
            .setNeutralButton("更多位置", (d, button) -> showMore(activity, request, launcher, back))
            .setNegativeButton(launcher ? "退出" : "返回", (d, button) -> {
                if (launcher) MGBAMenu.returnToGame(); else MGBAMenu.fileManagerBack(activity);
            })
            .setOnCancelListener(d -> {
                if (launcher) MGBAMenu.returnToGame(); else MGBAMenu.fileManagerBack(activity);
            }).show();
        MGBAUi.wide(activity, dialog, false);
    }
    private static void showMore(Activity activity, int request, boolean launcher, Runnable back) {
        ArrayList<String> names = new ArrayList<>(), descriptions = new ArrayList<>();
        names.add("应用导入目录"); descriptions.add("浏览复制到应用 Imports 的文件");
        names.add("自动备份"); descriptions.add("恢复加载外部存档前的进度");
        names.add("联机恢复文件"); descriptions.add("查看各次联机会话保留的进度");
        ArrayList<Uri> folders = new ArrayList<>();
        for (android.content.UriPermission permission : activity.getContentResolver().getPersistedUriPermissions())
            if (permission.isReadPermission() && DocumentsContract.isTreeUri(permission.getUri())) {
                Uri uri = permission.getUri(); folders.add(uri);
                names.add("授权目录：" + folderName(activity, uri)); descriptions.add(uri.toString());
            }
        names.add("授权文件夹…"); descriptions.add("选择一次，以后在这里直接浏览");
        names.add("管理文件夹授权…"); descriptions.add("移除已授权目录，不删除文件");
        names.add("旧版保留文件…");descriptions.add("查找迁移前的备份、联机记录与导入缓存");
        if (launcher) {
            names.add("触控按键设置"); descriptions.add("调整按键尺寸和透明度");
        }
        ListAdapter adapter = twoLines(activity, names, descriptions);
        AlertDialog d = new MGBAUi.Builder(activity).setTitle("更多位置")
            .setAdapter(adapter, (di, which) -> {
                if (which == 0) browseLocal(activity, request, imports(activity), imports(activity));
                else if (which == 1) {
                    File f = MGBALibrary.backups(activity); browseLocal(activity, 7108, f, f);
                } else if (which == 2) {
                    File current=MGBALibrary.currentFolder();File f = current==null?new File(activity.getFilesDir(), "mgba-network"):new File(current,"Network"); browseLocal(activity, 7108, f, f);
                } else if (which < 3 + folders.size()) {
                    MGBABrowser browser = new MGBABrowser(activity, request);
                    browser.tree = folders.get(which - 3);
                    browser.directory = DocumentsContract.buildDocumentUriUsingTree(browser.tree, DocumentsContract.getTreeDocumentId(browser.tree));
                    browser.open();
                } else if (which == 3 + folders.size()) {
                    returnRequest = request;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        String last = prefs(activity).getString("lastTree", null);
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, last == null
                            ? DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
                            : Uri.parse(last));
                    }
                    try { activity.startActivityForResult(intent, TREE); }
                    catch (Exception ex) { Toast.makeText(activity, "无法打开目录选择器：" + ex.getMessage(), Toast.LENGTH_LONG).show(); back.run(); }
                } else if (which == 4 + folders.size()) manageFolders(activity, folders, () -> showMore(activity, request, launcher, back));
                else if (which == 5 + folders.size()) new MGBAUi.Builder(activity).setTitle("旧版保留文件").setItems(new String[]{"旧版自动备份","旧版联机恢复","旧版导入缓存"},(dd,w)->{
                    String[] dirs={"mgba-backups","mgba-network","mgba-imports"};File f=new File(activity.getFilesDir(),dirs[w]);browseLocal(activity,AUTO,f,f);
                }).setNegativeButton("返回",(dd,w)->showMore(activity,request,launcher,back))
                    .setOnCancelListener(dd->showMore(activity,request,launcher,back)).show();
                else MGBAMenu.showLauncherTouchSettings(activity);
            }).setNegativeButton("返回", (di, w) -> back.run())
            .setOnCancelListener(di -> back.run()).show();
        MGBAUi.wide(activity, d, false);
    }
    private static String folderName(Activity activity, Uri tree) {
        try (Cursor c = activity.getContentResolver().query(
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return DocumentsContract.getTreeDocumentId(tree);
    }
    private static void manageFolders(Activity a, ArrayList<Uri> folders, Runnable back) {
        if (folders.isEmpty()) { Toast.makeText(a, "尚未授权文件夹", Toast.LENGTH_SHORT).show(); back.run(); return; }
        String[] names = new String[folders.size()];
        for (int i = 0; i < names.length; ++i) names[i] = folderName(a, folders.get(i));
        new MGBAUi.Builder(a).setTitle("移除目录授权").setItems(names, (d, i) ->
            new MGBAUi.Builder(a).setTitle("移除 " + names[i] + "？").setMessage("只取消访问授权，文件不会被删除。")
                .setPositiveButton("移除授权", (dd, w) -> {
                    try { a.getContentResolver().releasePersistableUriPermission(folders.get(i), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
                    back.run();
                }).setNegativeButton("取消", (dd, w) -> back.run()).setOnCancelListener(dd -> back.run()).show())
            .setNegativeButton("返回", (d, w) -> back.run()).setOnCancelListener(d -> back.run()).show();
    }
    static void browseLocal(Activity a, int request, File root, File current) {
        MGBABrowser browser = new MGBABrowser(a, request); browser.localRoot = root; browser.local = current; browser.open();
    }
    private void openRecent(Runnable back) { recent = true; open(back); }
    private void open() {
        open(() -> show(activity, request));
    }
    private void open(Runnable back) {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1,
            activity.getResources().getDisplayMetrics().heightPixels * 72 / 100));
        content.setPadding(dp(16), 0, dp(16), 0);
        path = new TextView(activity); path.setTextSize(12); path.setTextIsSelectable(true);
        if (!recent) content.addView(path);
        LinearLayout bar = new LinearLayout(activity);
        Button source = new Button(activity); source.setText("位置"); source.setOnClickListener(v -> { dialog.dismiss(); show(activity, request); });
        Button up = new Button(activity); up.setText("上一级"); up.setEnabled(!recent);
        up.setOnClickListener(v -> {
            if (local != null && !local.equals(localRoot)) { local = local.getParentFile(); load(); }
            else if (tree != null && !directory.equals(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))) {
                if (!parents.isEmpty()) { directory = parents.remove(parents.size() - 1); load(); }
            }
        });
        search = new EditText(MGBAUi.context(activity)); search.setSingleLine(true); search.setHint(recent ? "搜索最近打开的文件" : "搜索当前目录"); search.setTextSize(14);
        if (!recent) { bar.addView(source); bar.addView(up); }
        bar.addView(search, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(bar);
        if (!recent) {
        LinearLayout options = new LinearLayout(activity);
        filter = new Spinner(MGBAUi.context(activity));
        ArrayAdapter<String> types = new ArrayAdapter<>(MGBAUi.context(activity), android.R.layout.simple_spinner_item,
            new String[]{"全部文件", "游戏 ROM", "即时存档", "游戏存档", "金手指"});
        types.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); filter.setAdapter(types);
        filter.setSelection(request == 7108 ? MGBAFileTypes.STATE : request == 7109 ? MGBAFileTypes.SAVE : 0);
        sort = new Spinner(MGBAUi.context(activity));
        ArrayAdapter<String> orders = new ArrayAdapter<>(MGBAUi.context(activity), android.R.layout.simple_spinner_item,
            new String[]{"按名称", "最新优先", "按大小"});
        orders.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); sort.setAdapter(orders);
        sort.setSelection(prefs(activity).getInt("sort", 0));
        options.addView(filter, new LinearLayout.LayoutParams(0, -2, 1));
        options.addView(sort, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(options);
        }
        empty = new TextView(activity); empty.setText("正在读取…"); content.addView(empty);
        list = new ListView(activity);
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View reuse, ViewGroup parent) {
                Entry e = shown.get(i);
                LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(8), dp(8), dp(8), dp(8));
                TextView title = new TextView(activity); title.setText(e.name + (e.folder ? "/" : "")); title.setTextSize(16); title.setTextColor(0xFFFFFFFF);
                TextView detail = new TextView(activity); detail.setText(subtitle(e)); detail.setTextSize(12); detail.setTextColor(0xFFBBBBBB);
                row.addView(title); row.addView(detail); return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, i, id) -> {
            Entry e = shown.get(i);
            if (e.folder) {
                if (local != null) {
                    File next = new File(e.uri.getPath());
                    try { if (!next.getCanonicalPath().startsWith(localRoot.getCanonicalPath() + File.separator)) throw new SecurityException("目录超出范围"); }
                    catch (Exception ex) { Toast.makeText(activity, ex.getMessage(), Toast.LENGTH_SHORT).show(); return; }
                    local = next;
                } else { parents.add(directory); directory = e.uri; }
                search.setText(""); load();
            } else { dialog.dismiss(); openEntry(e); }
        });
        list.setOnItemLongClickListener((parent, view, i, id) -> { details(shown.get(i)); return true; });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            public void afterTextChanged(Editable e) { }
        });
        AdapterView.OnItemSelectedListener selected = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int i, long id) { refresh(); }
            public void onNothingSelected(AdapterView<?> p) { }
        };
        if (!recent) { filter.setOnItemSelectedListener(selected); sort.setOnItemSelectedListener(selected); }
        dialog = new MGBAUi.Builder(activity).setTitle(recent ? "最近使用" : "浏览文件")
            .setView(content).setNegativeButton("返回", (d, w) -> back.run())
            .setOnCancelListener(d -> back.run()).create();
        dialog.setOnDismissListener(d -> generation++);
        dialog.show(); MGBAUi.wide(activity, dialog, true);
        load();
    }
    private final ArrayList<Uri> parents = new ArrayList<>();
    private void openEntry(Entry e) {
        if (e.cache != null && !e.cache.isEmpty() && new File(e.cache).isFile())
            MGBAMenu.selectCachedFile(activity, e.uri, e.name, e.cache, e.request);
        else MGBAMenu.selectFile(activity, e.uri, request);
    }
    private int dp(int value) { return MGBAUi.dp(activity, value); }
    private void load() {
        final int ticket = ++generation;
        final File folder = local; final Uri doc = directory, root = tree;
        path.setText(recent ? "长按文件可查看详情、导出副本或移除记录" : folder != null ? folder.getAbsolutePath() : doc.toString());
        entries.clear(); shown.clear(); adapter.notifyDataSetChanged();
        empty.setText("正在读取…"); empty.setVisibility(View.VISIBLE);
        IO.execute(() -> {
            ArrayList<Entry> result = new ArrayList<>(); String error = null;
            try {
                if (recent) {
                    JSONArray array = new JSONArray(prefs(activity).getString("recent", "[]"));
                    for (int i = 0; i < array.length(); ++i) {
                        JSONObject o = array.getJSONObject(i);
                        Entry e = new Entry(Uri.parse(o.getString("uri")), o.getString("name"), o.optLong("size", -1), o.optLong("time", 0), false);
                        e.cache = o.optString("cache", ""); e.request = o.optInt("request", AUTO); result.add(e);
                    }
                } else if (folder != null) {
                    File[] files = folder.listFiles();
                    if (files == null && folder.exists()) throw new SecurityException("无法读取此目录，请通过系统选择器授权");
                    if (files != null) for (File f : files) {
                        if (result.size() >= 4000) break;
                        result.add(new Entry(Uri.fromFile(f), f.getName(), f.length(), f.lastModified(), f.isDirectory()));
                    }
                } else {
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(doc));
                    try (Cursor c = activity.getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                        if (c == null) throw new SecurityException("目录不可用，请重新授权");
                        while (c.moveToNext() && result.size() < 4000)
                            result.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(root, c.getString(0)), c.getString(1),
                                c.isNull(3) ? -1 : c.getLong(3), c.getLong(4), DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))));
                    }
                }
            } catch (Exception ex) { error = ex.getMessage(); }
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (ticket != generation || !dialog.isShowing() || activity.isFinishing()) return;
                entries.clear(); entries.addAll(result); refresh();
                if (failure != null) { empty.setText("读取失败：" + failure); empty.setVisibility(View.VISIBLE); }
            });
        });
    }
    private void refresh() {
        if (adapter == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int type = recent ? 0 : filter.getSelectedItemPosition(), order = recent ? 1 : sort.getSelectedItemPosition();
        shown.clear();
        for (Entry e : entries) if (e.name.toLowerCase(Locale.ROOT).contains(query)
            && (e.folder || type == 0 || MGBAFileTypes.fromName(e.name) == type)) shown.add(e);
        Collections.sort(shown, (a, b) -> {
            if (a.folder != b.folder) return a.folder ? -1 : 1;
            int c = order == 1 ? Long.compare(b.time, a.time) : order == 2 ? Long.compare(b.size, a.size) : 0;
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        if (!recent) prefs(activity).edit().putInt("sort", order).apply();
        adapter.notifyDataSetChanged();
        empty.setText(recent ? (entries.isEmpty() ? "还没有最近打开的文件" : "没有匹配文件")
            : entries.size() >= 4000 ? "最多显示 4000 项，请进入子目录查找" : "此处没有匹配文件；可切换类型或通过“位置”选择目录");
        empty.setVisibility(shown.isEmpty() || entries.size() >= 4000 ? View.VISIBLE : View.GONE);
    }
    private void details(Entry e) {
        if (e.folder) return;
        ArrayList<String> actions = new ArrayList<>(Arrays.asList("打开", "导出此文件的副本…", "查看详情"));
        if (recent) actions.add("从最近记录移除");
        new MGBAUi.Builder(activity).setTitle(e.name).setItems(actions.toArray(new String[0]), (d, i) -> {
            if (i == 0) { dialog.dismiss(); openEntry(e); }
            else if (i == 1) {
                Uri source = e.cache != null && new File(e.cache).isFile() ? Uri.fromFile(new File(e.cache)) : e.uri;
                returnRequest = request;
                exportCopy(activity,source,e.name,null);
            } else if (i == 2) new MGBAUi.Builder(activity).setTitle(e.name).setMessage(subtitle(e) + "\n\n" + e.uri
                + "\n\n这里导出的是所选文件本身。想备份正在玩的最新进度，请使用“存档管理 → 导出当前即时进度”。原始文件不会自动更新；即时存档需要匹配 ROM。").setPositiveButton("确定", null).show();
            else { forget(activity, e.uri); load(); }
        }).setNegativeButton("取消", null).show();
    }
    static void remember(Activity a, Uri uri, String name, long size, String cache, int request) {
        try {
            JSONArray old = new JSONArray(prefs(a).getString("recent", "[]")), next = new JSONArray();
            next.put(new JSONObject().put("uri", uri.toString()).put("name", name).put("size", size)
                .put("time", System.currentTimeMillis()).put("cache", cache).put("request", request));
            for (int i = 0; i < old.length() && next.length() < 20; ++i)
                if (!old.getJSONObject(i).getString("uri").equals(uri.toString())) next.put(old.getJSONObject(i));
            prefs(a).edit().putString("recent", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    private static void forget(Activity a, Uri uri) {
        try {
            JSONArray old = new JSONArray(prefs(a).getString("recent", "[]")), next = new JSONArray();
            for (int i = 0; i < old.length(); ++i) if (!old.getJSONObject(i).getString("uri").equals(uri.toString())) next.put(old.getJSONObject(i));
            prefs(a).edit().putString("recent", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    static void exportCopy(Activity activity,Uri source,String name,Runnable after) {
        exportSource=source;exportReturn=after;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,name);
        if(after!=null && android.os.Build.VERSION.SDK_INT>=26)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
        try{activity.startActivityForResult(intent,EXPORT);}
        catch(Exception ex){exportSource=null;exportReturn=null;Toast.makeText(activity,"无法打开导出位置："+ex.getMessage(),Toast.LENGTH_LONG).show();if(after!=null)after.run();}
    }
    static boolean onResult(Activity a, int code, int result, Intent data) {
        if (code != TREE && code != EXPORT) return false;
        if (code == TREE) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                try {
                    a.getContentResolver().takePersistableUriPermission(data.getData(), data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    prefs(a).edit().putString("lastTree", data.getData().toString()).apply();
                }
                catch (Exception ex) { Toast.makeText(a, "未能保留授权，请重新选择：" + ex.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            show(a, returnRequest);
        } else if (result == Activity.RESULT_OK && data != null && data.getData() != null && exportSource != null) {
            Uri source = exportSource, target = data.getData(); exportSource = null;
            Runnable after=exportReturn;exportReturn=null;
            if (source.equals(target)) { Toast.makeText(a, "请选择其他位置，不能覆盖原文件", Toast.LENGTH_LONG).show();if(after!=null)after.run();return true; }
            IO.execute(() -> {
                String message;
                try (java.io.InputStream in = a.getContentResolver().openInputStream(source);
                     java.io.OutputStream out = a.getContentResolver().openOutputStream(target, "w")) {
                    if (in == null || out == null) throw new java.io.IOException("无法打开文件");
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    message = "已导出副本";
                } catch (Exception ex) { message = "导出失败：" + ex.getMessage(); }
                final String notice = message;
                a.runOnUiThread(() -> { if (!a.isFinishing()){Toast.makeText(a, notice, Toast.LENGTH_LONG).show();if(after!=null)after.run();} });
            });
        } else {exportSource=null;Runnable after=exportReturn;exportReturn=null;if(after!=null)after.run();}
        return true;
    }
    private static ListAdapter twoLines(Activity a, ArrayList<String> names, ArrayList<String> descriptions) {
        ArrayList<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); ++i) {
            HashMap<String, String> row = new HashMap<>(); row.put("name", names.get(i)); row.put("detail", descriptions.get(i)); rows.add(row);
        }
        return new SimpleAdapter(a, rows, android.R.layout.simple_list_item_2,
            new String[]{"name", "detail"}, new int[]{android.R.id.text1, android.R.id.text2});
    }
}
