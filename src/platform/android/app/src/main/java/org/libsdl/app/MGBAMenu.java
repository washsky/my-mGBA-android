/* Copyright (c) 2026 Gitsnup
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.EditText;
import android.text.InputType;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.security.SecureRandom;
import java.security.MessageDigest;

/** Android-native menus and settings for the SDL mGBA frontend. */
public final class MGBAMenu {
    private static final int REQUEST_BIOS = 7101;
    private static final int REQUEST_GB_BIOS = 7102;
    private static final int REQUEST_GBC_BIOS = 7103;
    private static final int REQUEST_SGB_BIOS = 7104;
    private static final int REQUEST_PATCH = 7105;
    private static final int REQUEST_CHEATS = 7106;
    private static final int REQUEST_ROM = 7107;
    private static final int REQUEST_STATE = 7108;
    private static final int REQUEST_SAVE = 7109;

    private static boolean menuOpen;
    private static boolean filePickerPending;
    private static Runnable filePickerReturn;
    private static boolean launcherSubmenuPending;
    private static SharedPreferences preferences;
    private static volatile boolean importBusy;
    private static Uri pendingOpen;
    private static PreparedFile pendingSave;
    private static PreparedFile pendingSnapshot;
    private static String currentRomName;
    private static final java.util.concurrent.ExecutorService FILE_IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final class PreparedFile {
        Uri source; String name, path; int request;
        boolean temporary;
        File snapshot;
        PreparedFile(Uri source, String name, String path, int request) {
            this.source=source; this.name=name; this.path=path; this.request=request;
        }
    }

    private MGBAMenu() {
    }

    public static boolean isMenuOpen() {
        return menuOpen;
    }
    static boolean filesBusy(){return importBusy || MGBACheats.isBusy();}
    static void returnToGame(){closeMenu();}

    public static boolean nativeMenuAvailable() {
        return nativeIsGameLoaded();
    }

    public static void initialize(Activity activity) {
        preferences = activity.getSharedPreferences("mgba_android", Activity.MODE_PRIVATE);
        updateOverlayPreferences();
    }

    public static void onHostPause(boolean minimized) {
        Log.i("MGBAMenu", "onHostPause begin minimized=" + minimized + " menu=" + menuOpen);
        if (nativeIsGameLoaded()) nativeHostPause(minimized, menuOpen);
        Log.i("MGBAMenu", "onHostPause end minimized=" + minimized);
    }

    public static void onHostResume(boolean minimized) {
        Log.i("MGBAMenu", "onHostResume begin minimized=" + minimized + " menu=" + menuOpen);
        if (nativeIsGameLoaded()) nativeHostResume(minimized, menuOpen);
        Log.i("MGBAMenu", "onHostResume end minimized=" + minimized);
    }

    public static void showMainMenu(Activity activity) {
        if (activity == null || activity.isFinishing() || menuOpen) {
            return;
        }
        menuOpen = true;
        if (!nativeIsGameLoaded()) {
            showLauncherMenu(activity);
            return;
        }
        nativePauseGame();
        showMainMenuDialog(activity);
    }

    /** Called from the native startup thread; Android dialogs must be shown on the UI thread. */
    public static void showLauncherMenuFromNative() {
        Context context = SDLActivity.getContext();
        if (!(context instanceof Activity)) {
            nativeCancelRomPicker();
            return;
        }
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) {
                nativeCancelRomPicker();
            } else if (pendingOpen != null) {
                Uri uri = pendingOpen; pendingOpen = null; menuOpen = true;
                selectFile(activity, uri, MGBABrowser.AUTO);
            } else {
                showMainMenu(activity);
            }
        });
    }

    private static void showLauncherMenu(Activity activity) {
        MGBABrowser.showLauncher(activity);
    }

    static void showLauncherTouchSettings(Activity activity) {
        launcherSubmenuPending = true;
        MGBASettings.globalTouch(activity);
    }

    static int preferenceInt(String key, int fallback) {
        return preferences == null ? fallback : preferences.getInt(key, fallback);
    }

    private static int cyclePreference(int current, int[] values) {
        for (int i = 0; i < values.length; ++i) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    static void saveTouchPreference(String key, int value) {
        if (preferences != null) preferences.edit().putInt(key, value).apply();
    }

    static void updateOverlayPreferences() {
        nativeSetOverlayOptions(preferenceInt("touchControls", 1), preferenceInt("touchSize", 100), preferenceInt("touchOpacity", 48));
    }

    private static void closeMenu() {
        // Starting ACTION_OPEN_DOCUMENT dismisses the launcher dialog while
        // the SDL startup thread is still waiting for the selected ROM.
        // Do not interpret that transition as the user cancelling the app.
        if (filePickerPending || launcherSubmenuPending) {
            return;
        }
        if (menuOpen) {
            menuOpen = false;
            if (nativeIsGameLoaded()) {
                nativeResumeGame();
            } else {
                nativeCancelRomPicker();
                Context context = SDLActivity.getContext();
                if (context instanceof Activity) {
                    ((Activity) context).finish();
                }
            }
        }
    }

    private static void showMainMenuDialog(Activity activity) {
        if (nativeNetBusy()) { showNetwork(activity); return; }
        String[] items = {
            "继续游戏",
            "倍速与快进…",
            "Wi-Fi 联机（实验）",
            "文件管理（游戏／存档）",
            "存档快照…",
            "存档管理",
            "金手指…",
            "设置",
            "重启游戏…",
            "倒带（切换）",
            "截取屏幕",
            "工具",
            "ROM 信息",
            "关于 mGBA",
            "退出"
        };
        int[] actions = {0,6,12,2,3,14,15,1,5,7,8,9,10,11,13};
        AlertDialog dialog = new MGBAUi.Builder(activity)
            .setTitle("mGBA — 游戏菜单")
            .setItems(items, (d, which) -> {
                switch (actions[which]) {
                    case 15: MGBACheats.show(activity); break;
                    case 14:
                        showSaveManager(activity);
                        break;
                    case 0:
                        closeMenu();
                        break;
                    case 1:
                        showSettingsCategories(activity);
                        break;
                    case 2:
                        MGBABrowser.show(activity, MGBABrowser.AUTO);
                        break;
                    case 3:
                        MGBASnapshots.showCurrent(activity, () -> showMainMenuDialog(activity));
                        break;
                    case 5:
                        new MGBAUi.Builder(activity)
                            .setTitle("重启游戏")
                            .setMessage("确定重启模拟器吗？尚未保存的游戏进度可能会丢失。")
                            .setNegativeButton("取消", (dd, w) -> showMainMenuDialog(activity))
                            .setPositiveButton("重启", (dd, w) -> {
                                nativeGameAction(1, 0);
                                toast(activity, "游戏已重启");
                                showMainMenuDialog(activity);
                            }).setOnCancelListener(dd -> showMainMenuDialog(activity)).show();
                        break;
                    case 6:
                        MGBASettings.speed(activity);
                        break;
                    case 7:
                        nativeGameAction(6, 0);
                        toast(activity, "倒带状态已切换");
                        closeMenu();
                        break;
                    case 8:
                        nativeGameAction(4, 0);
                        toast(activity, "截图已保存");
                        showMainMenuDialog(activity);
                        break;
                    case 9:
                        showToolsMenu(activity);
                        break;
                    case 10:
                        showInfo(activity, "ROM 信息", nativeGetGameInfo());
                        break;
                    case 11:
                        showInfo(activity, "关于 mGBA", "mGBA 安卓版\n\n专为触屏设备设计的 mGBA 模拟器前端。\n\n模拟核心由 mGBA 提供。");
                        break;
                    case 12:
                        showNetwork(activity);
                        break;
                    case 13:
                        new MGBAUi.Builder(activity)
                            .setTitle("退出 mGBA")
                            .setMessage("确定关闭当前游戏并退出模拟器吗？")
                            .setNegativeButton("取消", (dd, w) -> showMainMenuDialog(activity))
                            .setPositiveButton("退出", (dd, w) -> {
                                menuOpen = false;
                                nativeGameAction(7, 0);
                                activity.finish();
                            }).setOnCancelListener(dd -> showMainMenuDialog(activity)).show();
                        break;
                }
            })
            .setOnCancelListener(d -> closeMenu())
            .create();
        dialog.show();
    }

    static void showSaveManager(Activity activity) {
        if(!nativeIsGameLoaded()) {MGBASettings.saveGuide(activity,()->MGBABrowser.show(activity,MGBABrowser.AUTO));return;}
        new MGBAUi.Builder(activity).setTitle("存档管理")
            .setItems(new String[]{"导出当前即时进度（.ss，推荐备份）…","导出游戏存档（.sav）…","查看自动备份…","存档使用说明","返回"},(d,i)->{
                if(i<2)exportCurrent(activity,i==1);
                else if(i==2){File f=MGBALibrary.backups(activity);MGBABrowser.browseLocal(activity,REQUEST_STATE,f,f);}
                else if(i==3)MGBASettings.saveGuide(activity,()->showSaveManager(activity));
                else fileManagerBack(activity);
            }).setNegativeButton("返回",(d,w)->fileManagerBack(activity)).setOnCancelListener(d->fileManagerBack(activity)).show();
    }
    private static void exportCurrent(Activity activity,boolean battery) {
        if(filesBusy()){toast(activity,"正在处理文件，请稍候");showSaveManager(activity);return;}
        if(nativeNetBusy()){showFileError(activity,"请先正常断开联机，再导出自己的最新进度",()->showNetwork(activity));return;}
        Runnable prepare=()->{
            importBusy=true;
            AlertDialog progress=new MGBAUi.Builder(activity).setTitle("正在生成导出副本").setMessage("保留原文件和存档位…").setCancelable(false).show();
            FILE_IO.execute(()->{
                String result=nativeExportCurrent(battery);
                activity.runOnUiThread(()->{
                    importBusy=false;progress.dismiss();
                    if(activity.isFinishing() || activity.isDestroyed())return;
                    if(result==null || !result.startsWith("/") || !new File(result).isFile()){
                        showFileError(activity,result,()->showSaveManager(activity));return;
                    }
                    String base=currentRomName==null?"mgba-game":currentRomName.replaceFirst("\\.[^.]+$","");
                    String name=battery?base+".sav":base+"-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new java.util.Date())+".ss";
                    Uri source=Uri.fromFile(new File(result));
                    MGBABrowser.remember(activity,source,name,new File(result).length(),result,battery?REQUEST_SAVE:REQUEST_STATE);
                    MGBABrowser.exportCopy(activity,source,name,()->showSaveManager(activity));
                });
            });
        };
        new MGBAUi.Builder(activity).setTitle(battery?"导出游戏存档（.sav）":"导出当前即时进度（.ss）")
            .setMessage(battery?"先在游戏自己的菜单里保存。导出的 .sav 反映游戏保存的数据，不包含尚未保存的走动、对话画面。":"把此刻的模拟状态和游戏保存数据一起保存为新文件，适合备份或在另一台 mGBA 恢复。需要相同 ROM。")
            .setPositiveButton("选择保存位置",(d,w)->prepare.run()).setNegativeButton("返回",(d,w)->showSaveManager(activity))
            .setOnCancelListener(d->showSaveManager(activity)).show();
    }

    static void showLegacySlots(Activity activity, Runnable back) {
        new MGBAUi.Builder(activity).setTitle("旧版存档位")
            .setItems(new String[]{"读取旧版存档位", "保存到旧版存档位"}, (d, which) ->
                showStateSlots(activity, which == 1, () -> showLegacySlots(activity, back)))
            .setNegativeButton("返回", (d, w) -> back.run()).setOnCancelListener(d -> back.run()).show();
    }

    private static void showStateSlots(Activity activity, boolean save, Runnable back) {
        String[] labels = new String[9]; boolean[] occupied = new boolean[9];
        for (int i=0;i<9;++i) {
            occupied[i]=nativeSlotOccupied(i+1);
            labels[i]="存档位 "+(i+1)+(occupied[i]?"  · 已有存档":"  · 空");
        }
        new MGBAUi.Builder(activity).setTitle(save?"保存即时存档":"读取即时存档")
            .setItems(labels,(d,index)->{
                if (!save && !occupied[index]) {
                    toast(activity,"此存档位为空"); showStateSlots(activity,false,back); return;
                }
                Runnable action=()->{
                    String error=nativeSlotAction(index+1,save);
                    if(error==null) { toast(activity,save?"已保存到存档位 "+(index+1):"即时存档已加载"); closeMenu(); }
                    else showFileError(activity,error,()->showStateSlots(activity,save,back));
                };
                if(save && occupied[index]) new MGBAUi.Builder(activity).setTitle("覆盖存档位 "+(index+1)+"？")
                    .setMessage("现有即时存档会被替换。").setPositiveButton("保存",(dd,w)->action.run())
                    .setNegativeButton("取消",(dd,w)->showStateSlots(activity,true,back))
                    .setOnCancelListener(dd->showStateSlots(activity,true,back)).show();
                else action.run();
            }).setNegativeButton("返回",(d,w)->back.run())
            .setOnCancelListener(d->back.run()).show();
    }

    private static void showSettingsCategories(Activity activity) { MGBASettings.root(activity); }
    static void finishSpeedMenu() { closeMenu(); }

    private static void showAudioVideo(Activity activity) { MGBASettings.audio(activity); }

    private static void showEmulation(Activity activity) { MGBASettings.emulation(activity); }

    private static void showBios(Activity activity) { MGBASettings.bios(activity); }

    private static void showPaths(Activity activity) { MGBASettings.paths(activity); }

    private static void showControls(Activity activity) { MGBASettings.controls(activity); }

    private static void showInterface(Activity activity) { MGBASettings.interfacePage(activity); }

    private static void showGameBoy(Activity activity) { MGBASettings.gb(activity); }

    private static void showGameBoyAdvance(Activity activity) { MGBASettings.gba(activity); }

    private static void showToolsMenu(Activity activity) {
        String[] items = {"加载补丁文件…", "导入金手指文件…", "清除金手指", "旧版存档位…", "ROM 信息", "加载外部即时存档…", "导入游戏存档（.sav）…", "应用导入目录：即时存档…", "应用导入目录：游戏存档…", "恢复自动备份…", "读取联机恢复副本…", "返回"};
        new MGBAUi.Builder(activity).setTitle("工具")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: openFilePicker(activity, REQUEST_PATCH, "选择 IPS／UPS／BPS 补丁文件"); break;
                    case 1: MGBACheats.show(activity); break;
                    case 2:
                        MGBACheats.confirmClear(activity);
                        break;
                    case 3: showLegacySlots(activity, () -> showToolsMenu(activity)); break;
                    case 4: showInfo(activity, "ROM 信息", nativeGetGameInfo()); break;
                    case 5: confirmSaveImport(activity, true); break;
                    case 6: confirmSaveImport(activity, false); break;
                    case 7: showLocalFiles(activity, REQUEST_STATE, importDirectory(activity), importDirectory(activity)); break;
                    case 8: showLocalFiles(activity, REQUEST_SAVE, importDirectory(activity), importDirectory(activity)); break;
                    case 9: {
                        File root = MGBALibrary.backups(activity);
                        showLocalFiles(activity, REQUEST_STATE, root, root); break;
                    }
                    case 10: {
                        File current=MGBALibrary.currentFolder();File root = current==null?new File(activity.getFilesDir(), "mgba-network"):new File(current,"Network");
                        showLocalFiles(activity, REQUEST_STATE, root, root); break;
                    }
                    default: showMainMenuDialog(activity); break;
                }
            })
            .setNegativeButton("返回", (d, w) -> showMainMenuDialog(activity))
            .setOnCancelListener(d -> showMainMenuDialog(activity)).show();
    }

    private static String localAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.getName().startsWith("wlan")) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses()))
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "请查看手机 Wi-Fi 设置";
    }

    static void showNetwork(Activity activity) {
        if (nativeNetBusy()) {
            LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
            int pad=MGBAUi.dp(activity,20);content.setPadding(pad,0,pad,0);
            TextView status=new TextView(activity);status.setText(nativeNetStatus().split("\n会话文件：")[0]);status.setTextColor(0xFFDDDDDD);status.setTextSize(14);content.addView(status);
            LinearLayout actions=new LinearLayout(activity);
            android.widget.Button speed=new android.widget.Button(MGBAUi.context(activity));speed.setText("倍速与快进");
            android.widget.Button touch=new android.widget.Button(MGBAUi.context(activity));touch.setText("触控按键大小");
            actions.addView(speed,new LinearLayout.LayoutParams(0,-2,1));actions.addView(touch,new LinearLayout.LayoutParams(0,-2,1));content.addView(actions);
            AlertDialog session=new MGBAUi.Builder(activity).setTitle("Wi-Fi 联机会话")
                .setView(content)
                .setPositiveButton("返回游戏", (d, w) -> closeMenu())
                .setNegativeButton("断开并取回进度", (d, w) -> { nativeNetStop(); closeMenu(); })
                .setNeutralButton("刷新状态", (d, w) -> showNetwork(activity))
                .setOnCancelListener(d -> closeMenu()).create();
            speed.setOnClickListener(v->{session.dismiss();MGBASettings.speed(activity);});
            touch.setOnClickListener(v->{session.dismiss();MGBASettings.controls(activity);});
            session.show();MGBAUi.wide(activity,session,false);
            return;
        }
        new MGBAUi.Builder(activity).setTitle("Wi-Fi 联机（实验）")
            .setItems(new String[]{"创建房间（玩家 1）", "查找附近房间（玩家 2）", "手动输入连接…", "查看上次连接状态", "返回"}, (d, which) -> {
                if (which == 0) {
                    String code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
                    new MGBAUi.Builder(activity).setTitle("创建局域网房间")
                        .setMessage("创建后会自动出现在另一台手机的附近房间列表，请保持 mGBA 在前台。\n\n备用 IP：" + localAddress() + "\n备用房间码：" + code + "\n\n请两台手机都安装同一新版，并各自加载 GBA ROM 和进度。主机运行两个同步实例；客机画面目标最高 60 FPS，拥堵时自动降帧以减少延迟，客机暂不播放声音。请使用可信的同一 Wi-Fi；正常断开时会把玩家 2 进度取回客机。")
                        .setNegativeButton("取消", (dd, w) -> showNetwork(activity))
                        .setPositiveButton("开始等待", (dd, w) -> {
                            String error = nativeNetStart(true, "", code);
                            if (error == null) { MGBALanDiscovery.host(activity,code); closeMenu(); toast(activity, "房间已创建，另一台手机可自动查找"); }
                            else showNetworkError(activity, error);
                        }).setOnCancelListener(dd -> showNetwork(activity)).show();
                } else if (which == 1) MGBALanDiscovery.browse(activity);
                else if (which == 2) showManualJoin(activity);
                else if (which == 3) {
                    new MGBAUi.Builder(activity).setTitle("上次连接状态").setMessage(nativeNetStatus())
                        .setPositiveButton("返回", (dd, w) -> showNetwork(activity)).show();
                } else showMainMenuDialog(activity);
            }).setOnCancelListener(d -> showMainMenuDialog(activity)).show();
    }

    static void joinDiscovered(Activity activity,String address,String code) {
        String error=nativeNetStart(false,address,code);
        if(error==null)closeMenu();else showNetworkError(activity,error);
    }
    static void showManualJoin(Activity activity) {
        LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);int pad=MGBAUi.dp(activity,20);layout.setPadding(pad,8,pad,8);
        EditText address=new EditText(MGBAUi.context(activity));address.setHint("主机 IPv4 地址");address.setSingleLine(true);address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText code=new EditText(MGBAUi.context(activity));code.setHint("六位房间码");code.setSingleLine(true);code.setInputType(InputType.TYPE_CLASS_NUMBER);
        TextView explanation=new TextView(activity);explanation.setText("自动发现被路由器屏蔽时可使用备用 IP 和房间码。加入后自动备份，并发送当前游戏与存档副本；客机暂不播放声音。结束时先离开游戏内联机房间，再正常断开取回进度。");
        layout.addView(address);layout.addView(code);layout.addView(explanation);
        new MGBAUi.Builder(activity).setTitle("手动加入房间").setView(layout)
            .setPositiveButton("连接",(d,w)->joinDiscovered(activity,address.getText().toString().trim(),code.getText().toString().trim()))
            .setNegativeButton("返回",(d,w)->showNetwork(activity)).setOnCancelListener(d->showNetwork(activity)).show();
    }
    private static void showNetworkError(Activity activity, String error) {
        new MGBAUi.Builder(activity).setTitle("联机无法启动").setMessage(error)
            .setPositiveButton("返回", (d, w) -> showNetwork(activity)).show();
    }

    private static File importDirectory(Activity activity) { return MGBABrowser.imports(activity); }

    private static void showLocalFiles(Activity activity, int request, File root, File current) {
        MGBABrowser.browseLocal(activity, request, root, current);
    }

    private static void showVolume(Activity activity) {
        int current = intOption("volume", 256);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 12, 24, 8);
        TextView value = new TextView(activity);
        value.setText("音量：" + Math.round(current * 100.0 / 256.0) + "%");
        value.setTextSize(16);
        SeekBar seek = new SeekBar(activity);
        seek.setMax(256);
        seek.setProgress(Math.max(0, Math.min(256, current)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText("音量：" + Math.round(progress * 100.0 / 256.0) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        layout.addView(value);
        layout.addView(seek);
        new MGBAUi.Builder(activity).setTitle("音量")
            .setView(layout)
            .setNegativeButton("取消", (d, w) -> showAudioVideo(activity))
            .setPositiveButton("应用", (d, w) -> {
                nativeSetOptionInt("volume", seek.getProgress());
                showAudioVideo(activity);
            }).setOnCancelListener(d -> showAudioVideo(activity)).show();
    }

    static void chooseDirectory(Activity activity, String key, String title) {
        File root = activity.getExternalFilesDir(null);
        if (root == null) root = activity.getFilesDir();
        showDirectory(activity, key, title, root, root);
    }

    private static void toggleSameDirectory(Activity activity, String pathKey) {
        boolean enabled = option(pathKey, "").length() == 0;
        String defaultPath;
        switch (pathKey) {
            case "savegamePath": defaultPath = "saves"; break;
            case "savestatePath": defaultPath = "savestates"; break;
            case "screenshotPath": defaultPath = "screenshots"; break;
            case "patchPath": defaultPath = "patches"; break;
            default: defaultPath = "cheats"; break;
        }
        if (enabled) nativeSetOptionString(pathKey, defaultPath);
        else nativeClearOption(pathKey);
        showPaths(activity);
    }

    private static void showDirectory(Activity activity, String key, String title, File root, File current) {
        File[] children = current.listFiles(File::isDirectory);
        ArrayList<File> dirs = new ArrayList<>();
        if (children != null) {
            for (File child : children) dirs.add(child);
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        ArrayList<String> labels = new ArrayList<>();
        labels.add("使用此文件夹：" + current.getName());
        if (!current.equals(root) && current.getParentFile() != null) labels.add("../（上级文件夹）");
        for (File dir : dirs) labels.add(dir.getName() + "/");
        String[] entries = labels.toArray(new String[0]);
        new MGBAUi.Builder(activity).setTitle(title + "\n" + current.getAbsolutePath())
            .setItems(entries, (d, which) -> {
                if (which == 0) {
                    nativeSetOptionString(key, current.getAbsolutePath());
                    toast(activity, "存储路径已更新");
                    showPaths(activity);
                } else if (!current.equals(root) && current.getParentFile() != null && which == 1) {
                    showDirectory(activity, key, title, root, current.getParentFile());
                } else {
                    int index = which - 1;
                    if (!current.equals(root)) --index;
                    if (index >= 0 && index < dirs.size()) showDirectory(activity, key, title, root, dirs.get(index));
                }
            })
            .setNegativeButton("取消", (d, w) -> showPaths(activity))
            .setOnCancelListener(d -> showPaths(activity)).show();
    }

    static void pickSystemFiles(Activity activity, int request) {
        pickSystemFiles(activity, request, null);
    }
    static void pickSystemFiles(Activity activity, int request, Runnable back) {
        openFilePicker(activity, request, request >= REQUEST_BIOS && request <= REQUEST_SGB_BIOS ? "选择 BIOS 文件" : "选择游戏或存档文件", back);
    }
    static void fileManagerBack(Activity activity) {
        if (pendingSave != null) { pendingSave = null; toast(activity, "已取消待加载的存档"); }
        if (nativeIsGameLoaded()) showMainMenuDialog(activity); else showLauncherMenu(activity);
    }
    static void finishGlobalSettings(Activity activity) {
        launcherSubmenuPending = false; showLauncherMenu(activity);
    }

    private static void openFilePicker(Activity activity, int requestCode, String title) {
        openFilePicker(activity, requestCode, title, null);
    }
    private static void openFilePicker(Activity activity, int requestCode, String title, Runnable back) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, title);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        filePickerReturn = back;
        filePickerPending = true;
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            filePickerPending = false;
            filePickerReturn = null;
            toast(activity, "当前没有可用的文件选择器");
            if (back != null) back.run();
            else if (menuOpen) {
                if (nativeIsGameLoaded()) showMainMenuDialog(activity);
                else showLauncherMenu(activity);
            }
        }
    }

    private static void confirmSaveImport(Activity activity, boolean state) {
        MGBABrowser.show(activity, state ? REQUEST_STATE : REQUEST_SAVE);
    }

    public static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (MGBABrowser.onResult(activity, requestCode, resultCode, data)) return true;
        if (requestCode < REQUEST_BIOS || requestCode > MGBABrowser.AUTO) return false;
        filePickerPending = false;
        Runnable back = filePickerReturn;
        filePickerReturn = null;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
                    activity.getContentResolver().takePersistableUriPermission(data.getData(), data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            selectFile(activity, data.getData(), requestCode);
        } else {
            if (back != null) back.run();
            else if (requestCode <= REQUEST_SGB_BIOS) showBios(activity);
            else MGBABrowser.show(activity, requestCode);
        }
        return true;
    }

    private static String copySelectedFile(Activity activity, Uri uri, int requestCode) throws Exception {
        String displayName = "import.bin";
        boolean localFile = "file".equals(uri.getScheme());
        if (localFile) displayName = new File(uri.getPath()).getName();
        Cursor cursor = localFile ? null : activity.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst() && column >= 0) displayName = cursor.getString(column);
            } finally {
                cursor.close();
            }
        }
        displayName = MGBAGameLibrary.clean(displayName);
        File dir = new File(activity.getFilesDir(), "mgba-imports");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create import folder");
        File target = new File(dir, requestCode + "-" + (System.nanoTime() + "-") + displayName);
        try {
        try (InputStream source = activity.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (source == null) throw new IllegalStateException("Cannot open selected file");
            PushbackInputStream peek = new PushbackInputStream(source, 2);
            byte[] magic = new byte[2];
            int count = 0, part;
            while (count < 2 && (part = peek.read(magic, count, 2 - count)) > 0) count += part;
            if (count > 0) peek.unread(magic, 0, count);
            InputStream in = requestCode == REQUEST_STATE && count == 2 && (magic[0] & 255) == 31 && (magic[1] & 255) == 139 ? new GZIPInputStream(peek) : peek;
            byte[] buffer = new byte[65536];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                long limit = requestCode == REQUEST_STATE ? 16L * 1024 * 1024 : requestCode == REQUEST_SAVE ? 1024L * 1024 : requestCode==REQUEST_CHEATS?256L*1024:64L * 1024 * 1024;
                if (total > limit) throw new IllegalStateException("文件超过大小限制");
                out.write(buffer, 0, read);
            }
            in.close();
        }
        return target.getAbsolutePath();
        } catch (Exception error) {
            target.delete(); // Only this newly created temporary import.
            throw error;
        }
    }

    private static String fileDigest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void showInfo(Activity activity, String title, String message) {
        new MGBAUi.Builder(activity).setTitle(title).setMessage(message == null ? "暂无信息" : message)
            .setPositiveButton("返回", (d, w) -> showMainMenuDialog(activity))
            .setOnCancelListener(d -> showMainMenuDialog(activity)).show();
    }

    private static void toggleOption(String key) {
        int next = intOption(key, 0) == 0 ? 1 : 0;
        nativeSetOptionInt(key, next);
        if (key.equals("android.touchControls")) {
            saveTouchPreference("touchControls", next);
            updateOverlayPreferences();
        }
    }

    private static void cycleInt(String key, int[] values) {
        int current = intOption(key, values[0]);
        int next = values[0];
        for (int i = 0; i < values.length; ++i) {
            if (values[i] == current) {
                next = values[(i + 1) % values.length];
                break;
            }
        }
        nativeSetOptionInt(key, next);
        if (key.equals("android.touchSize")) saveTouchPreference("touchSize", next);
        else if (key.equals("android.touchOpacity")) saveTouchPreference("touchOpacity", next);
        if (key.equals("android.touchSize") || key.equals("android.touchOpacity")) updateOverlayPreferences();
    }

    private static void cycleFloat(String key, float[] values) {
        float current = floatOption(key, values[0]);
        float next = values[0];
        for (int i = 0; i < values.length; ++i) {
            if (Math.abs(values[i] - current) < 0.01f) {
                next = values[(i + 1) % values.length];
                break;
            }
        }
        nativeSetOptionFloat(key, next);
    }

    private static void cycleString(String key, String[] values) {
        String current = option(key, values[0]);
        int index = 0;
        for (int i = 0; i < values.length; ++i) {
            if (values[i].equalsIgnoreCase(current)) {
                index = (i + 1) % values.length;
                break;
            }
        }
        nativeSetOptionString(key, values[index]);
    }

    private static String toggleLabel(String label, String key) {
        return label + (intOption(key, 0) != 0 ? "  [开启]" : "  [关闭]");
    }

    private static String stateFlagLabel(String label, String key, int flag) {
        return label + ((intOption(key, 0) & flag) != 0 ? "  [开启]" : "  [关闭]");
    }

    private static void toggleStateFlag(String key, int flag) {
        int current = intOption(key, key.equals("saveStateExtdata") ? 31 : 9);
        nativeSetOptionInt(key, (current & flag) != 0 ? current & ~flag : current | flag);
    }

    private static String fastForwardVolumeLabel() {
        int value = intOption("fastForwardVolume", -1);
        if (value < 0) return "跟随正常音量";
        return Math.round(value * 100.0f / 256.0f) + "%";
    }

    private static String displayOption(String key, String fallback) {
        String value = option(key, fallback);
        switch (value.toLowerCase(Locale.ROOT)) {
            case "auto": return "自动";
            case "native": return "原生帧率";
            case "warning": return "警告";
            case "detect": return "检测";
            case "remove": return "移除";
            case "ignore": return "忽略";
            default: return value;
        }
    }

    private static String option(String key, String fallback) {
        String value = nativeGetOption(key);
        return value == null || value.length() == 0 ? fallback : value;
    }

    private static int intOption(String key, int fallback) {
        try { return Integer.parseInt(option(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static float floatOption(String key, float fallback) {
        try { return Float.parseFloat(option(key, Float.toString(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static int volumePercent() {
        return Math.max(0, Math.min(100, Math.round(intOption("volume", 256) * 100.0f / 256.0f)));
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }


    static void selectCachedFile(Activity activity, Uri source, String name, String path, int request) {
        if (filesBusy()) { toast(activity, "正在读取文件，请稍候"); return; }
        if (nativeNetBusy()) { showFileError(activity, "请先正常断开联机", () -> showNetwork(activity)); return; }
        if (nativeIsGameLoaded()) nativePauseGame();
        menuOpen = true;
        usePreparedFile(activity, new PreparedFile(source, name, path, request));
    }
    static void openSnapshotGame(Activity activity, File rom, String name, File snapshot, Runnable back) {
        if (filesBusy() || nativeNetBusy() || !snapshot.isFile()) {
            showFileError(activity, "快照不可用，请确认文件存在且没有进行联机", back);
            return;
        }
        PreparedFile file = new PreparedFile(Uri.fromFile(rom), name == null ? rom.getName() : name,
            rom.getAbsolutePath(), REQUEST_ROM);
        file.snapshot = snapshot;
        if (nativeIsGameLoaded()) {
            new MGBAUi.Builder(activity).setTitle("切换游戏并读取快照？")
                .setMessage("切换前会备份当前游戏进度。")
                .setPositiveButton("备份并打开", (d, w) -> openPreparedRom(activity, file, back))
                .setNegativeButton("取消", (d, w) -> back.run()).setOnCancelListener(d -> back.run()).show();
        } else {
            openPreparedRom(activity, file, back);
        }
    }
    static void selectFile(Activity activity, Uri uri, int requested) {
        if (filesBusy()) { toast(activity, "正在读取文件，请稍候"); return; }
        if (nativeNetBusy()) { showFileError(activity, "请先正常断开联机，再打开文件", () -> showNetwork(activity)); return; }
        if (nativeIsGameLoaded()) nativePauseGame();
        menuOpen = true; importBusy = true;
        AlertDialog progress = new MGBAUi.Builder(activity).setTitle("正在读取文件")
            .setMessage("正在识别文件并准备副本…").setCancelable(false).show();
        FILE_IO.execute(() -> {
            PreparedFile result = null; String error = null;
            try {
                String name = selectedName(activity, uri);
                int resolved = requested;
                if (requested >= REQUEST_ROM) {
                    int type;
                    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("无法打开所选文件");
                        type = MGBAFileTypes.detect(name, in);
                    }
                    if (type == MGBAFileTypes.ROM) resolved = REQUEST_ROM;
                    else if (type == MGBAFileTypes.STATE) resolved = REQUEST_STATE;
                    else if (type == MGBAFileTypes.SAVE) resolved = REQUEST_SAVE;
                    else if (type == MGBAFileTypes.CHEATS) resolved = REQUEST_CHEATS;
                    else if (requested == MGBABrowser.AUTO) resolved = 0;
                }
                String path = resolved == 0 ? null : copySelectedFile(activity, uri, resolved);
                result = new PreparedFile(uri, name, path, resolved);
                result.temporary=resolved==REQUEST_ROM;
                if (resolved == REQUEST_ROM && !nativeIsRomFile(path)) throw new java.io.IOException("无法识别为受支持的 GBA／GB／GBC ROM，请检查文件内容");
            } catch (Exception ex) {
                error = ex.getMessage();
                if (ex instanceof SecurityException || (error != null && (error.contains("EACCES") || error.contains("Permission denied"))))
                    error = "文件访问权限不足。可将文件复制到 Download，再通过“系统选择文件”导入。\n\n" + error;
            }
            PreparedFile file = result; String failure = error;
            activity.runOnUiThread(() -> {
                importBusy = false; progress.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) showFileError(activity, failure, () -> MGBABrowser.show(activity, requested));
                else usePreparedFile(activity, file);
            });
        });
    }
    private static String selectedName(Activity activity, Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "import.bin";
    }
    private static void usePreparedFile(Activity activity, PreparedFile file) {
        if(file.request==REQUEST_CHEATS){
            if(!nativeIsGameLoaded()){pendingSave=file;new MGBAUi.Builder(activity).setTitle("先打开对应游戏，再导入金手指").setMessage(file.name).setPositiveButton("选择游戏",(d,w)->MGBALibrary.show(activity)).setNegativeButton("取消",(d,w)->{pendingSave=null;fileManagerBack(activity);}).show();}
            else{
                try{file.path=MGBALibrary.archive(new File(file.path),file.name).getAbsolutePath();MGBACheats.importFile(activity,file.path);}catch(Exception e){showFileError(activity,e.getMessage(),()->fileManagerBack(activity));}
            }
            return;
        }
        if (file.request == 0) {
            new MGBAUi.Builder(activity).setTitle("选择文件类型").setMessage(file.name + "\n此文件没有可识别的类型，请选择打开方式。")
                .setItems(new String[]{"作为游戏 ROM 打开","作为即时存档加载","作为游戏存档导入"},(d,i)->
                    selectFile(activity,file.source,i==0?REQUEST_ROM:i==1?REQUEST_STATE:REQUEST_SAVE))
                .setNegativeButton("取消",(d,w)->MGBABrowser.show(activity,MGBABrowser.AUTO))
                .setOnCancelListener(d->MGBABrowser.show(activity,MGBABrowser.AUTO)).show();
            return;
        }
        if (file.request == REQUEST_STATE || file.request == REQUEST_SAVE) {
            if (!nativeIsGameLoaded()) {
                pendingSave=file;
                new MGBAUi.Builder(activity).setTitle("先选择对应的游戏").setMessage("已识别存档：" + file.name + "\n\n先打开对应 ROM，随后自动加载这个存档。")
                    .setPositiveButton("选择 ROM",(d,w)->MGBABrowser.show(activity,REQUEST_ROM))
                    .setNegativeButton("取消",(d,w)->{pendingSave=null;showLauncherMenu(activity);})
                    .setOnCancelListener(d->{pendingSave=null;showLauncherMenu(activity);}).show();
            } else if (file.request == REQUEST_SAVE) {
                new MGBAUi.Builder(activity).setTitle("导入游戏存档？").setMessage(file.name + "\n\n将替换当前游戏的保存数据并重新启动游戏。替换前会自动备份当前进度。")
                    .setPositiveButton("备份并导入",(d,w)->applySave(activity,file))
                    .setNegativeButton("取消",(d,w)->MGBABrowser.show(activity,REQUEST_SAVE))
                    .setOnCancelListener(d->MGBABrowser.show(activity,REQUEST_SAVE)).show();
            } else applySave(activity,file);
            return;
        }
        if (file.request == REQUEST_ROM) {
            Runnable open=()->openPreparedRom(activity,file);
            if (nativeIsGameLoaded()) new MGBAUi.Builder(activity).setTitle("切换游戏？")
                .setMessage(file.name + "\n\n切换前会自动备份当前游戏进度。").setPositiveButton("备份并打开",(d,w)->open.run())
                .setNegativeButton("取消",(d,w)->fileManagerBack(activity)).setOnCancelListener(d->fileManagerBack(activity)).show();
            else open.run();
            return;
        }
        nativeFileSelected(file.request - 7100, file.path);
        toast(activity,"已选择：" + file.name);
        if(file.request <= REQUEST_SGB_BIOS) showBios(activity); else showToolsMenu(activity);
    }
    private static void openPreparedRom(Activity activity, PreparedFile file) {
        openPreparedRom(activity, file, () -> fileManagerBack(activity));
    }
    private static void openPreparedRom(Activity activity, PreparedFile file, Runnable back) {
        importBusy=true;
        AlertDialog progress=new MGBAUi.Builder(activity).setTitle("正在打开游戏").setMessage("正在保留当前进度…").setCancelable(false).show();
        FILE_IO.execute(()->{
            String error=nativeIsGameLoaded()?nativeBackupCurrent():null;
            MGBAGameLibrary.Plan prepared=null;File battery=null,state=null;
            try{
                if(error==null){
                    if(!nativeIsRomFile(file.path))throw new java.io.IOException("无法识别此游戏本体，原文件已保留");
                    File source=new File(file.path),preferred=file.temporary?null:source;
                    String active=nativeRomPath();boolean same=active!=null && new File(active).isFile() && MGBAGameLibrary.digest(new File(active)).equals(MGBAGameLibrary.digest(source));
                    if(same)preferred=new File(active);
                    prepared=MGBAGameLibrary.plan(MGBALibrary.root(activity),source,file.name,new File(activity.getFilesDir(),"mgba-imports"),preferred);
                    if(prepared.existing==null && same){
                        String s=nativeExportCurrent(false);if(s!=null && s.startsWith("/"))state=new File(s);
                        if(state==null)throw new java.io.IOException("保留当前进度失败，未迁移游戏");
                        String b=nativeExportCurrent(true);if(b!=null && b.startsWith("/"))battery=new File(b);
                    }
                }
            }catch(Exception ex){error=ex.getMessage();}
            final String failure=error;final MGBAGameLibrary.Plan plan=prepared;final File liveBattery=battery,liveState=state;
            activity.runOnUiThread(()->{
                importBusy=false;progress.dismiss();
                if(activity.isFinishing() || activity.isDestroyed()) return;
                if(failure!=null) {showFileError(activity,failure,back);return;}
                if(plan.needsChoice()){
                    String[] choices=new String[plan.legacy.size()+1];
                    for(int i=0;i<plan.legacy.size();++i)choices[i]=plan.legacy.get(i).label();choices[choices.length-1]="从游戏开始；仍保留全部旧进度副本";
                    new MGBAUi.Builder(activity).setTitle("同一游戏发现多份旧进度，请选择沿用的一份")
                        .setItems(choices,(d,w)->finishLibraryOpen(activity,file,plan,w<plan.legacy.size()?plan.legacy.get(w):null,liveBattery,liveState,back))
                        .setNegativeButton("取消",(d,w)->back.run()).setOnCancelListener(d->back.run()).show();
                }else finishLibraryOpen(activity,file,plan,plan.preferred,liveBattery,liveState,back);
            });
        });
    }
    private static void finishLibraryOpen(Activity activity,PreparedFile file,MGBAGameLibrary.Plan plan,MGBAGameLibrary.Legacy selected,File battery,File state,Runnable back){
        importBusy=true;AlertDialog wait=new MGBAUi.Builder(activity).setTitle("正在整理游戏库").setMessage("校验本体并保留旧存档副本…").setCancelable(false).show();
        FILE_IO.execute(()->{
            MGBAGameLibrary.Game result=null;String error=null;
            try{result=MGBAGameLibrary.commit(plan,selected,battery,state);}catch(Exception ex){error=ex.getMessage();}
            final MGBAGameLibrary.Game game=result;final String failure=error;
            activity.runOnUiThread(()->{importBusy=false;wait.dismiss();if(activity.isFinishing() || activity.isDestroyed())return;
                if(failure!=null){showFileError(activity,failure,back);return;}
                if (file.snapshot != null) {
                    if (!file.path.equals(game.rom.getAbsolutePath())) {
                        showFileError(activity, "游戏库的位置已更改，请重新选择快照", back);
                        return;
                    }
                    pendingSave = null;
                    pendingSnapshot = file;
                }
                if(file.temporary)new File(file.path).delete(); // Only this newly staged ROM; old caches and originals remain.
                file.path=game.rom.getAbsolutePath();file.name=game.name;
                MGBABrowser.remember(activity,file.source,file.name,game.rom.length(),file.path,file.request);
                currentRomName=file.name;menuOpen=false;nativeFileSelected(7,file.path);
            });
        });
    }
    private static void applySave(Activity activity, PreparedFile file) {
        importBusy=true;
        AlertDialog progress=new MGBAUi.Builder(activity).setTitle("正在加载存档").setMessage("正在自动备份当前进度…").setCancelable(false).show();
        FILE_IO.execute(()->{
            String problem=null;
            try{file.path=MGBALibrary.archive(new File(file.path),file.name).getAbsolutePath();}catch(Exception ex){problem=ex.getMessage();}
            final String error=problem==null?nativeImportSave(file.request==REQUEST_STATE?8:9,file.path):problem;
            activity.runOnUiThread(()->{
                importBusy=false;progress.dismiss();
                if(activity.isFinishing() || activity.isDestroyed()) return;
                if(error==null) {
                    MGBABrowser.remember(activity,file.source,file.name,new File(file.path).length(),file.path,file.request);
                    toast(activity,"存档已加载，原进度已自动备份");closeMenu();
                } else new MGBAUi.Builder(activity).setTitle("存档加载失败").setMessage(error)
                    .setPositiveButton("返回文件管理",(d,w)->MGBABrowser.show(activity,MGBABrowser.AUTO))
                    .setNeutralButton("选择对应游戏",(d,w)->{pendingSave=file;MGBABrowser.show(activity,REQUEST_ROM);})
                    .setNegativeButton("继续原游戏",(d,w)->closeMenu()).setOnCancelListener(d->closeMenu()).show();
            });
        });
    }
    private static void showFileError(Activity activity, String message, Runnable back) {
        new MGBAUi.Builder(activity).setTitle("无法打开文件").setMessage(message==null?"文件不可用，请重新选择或授权":message)
            .setPositiveButton("返回",(d,w)->back.run()).setOnCancelListener(d->back.run()).show();
    }
    /** Called after native core startup, never by a polling timer. */
    public static void onGameReady() {
        Context context=SDLActivity.getContext();
        if(!(context instanceof Activity)) return;
        Activity activity=(Activity)context;
        activity.runOnUiThread(()->{
            if(activity.isFinishing() || activity.isDestroyed()) return;
            // An external open can arrive while the previous core is shutting down.
            // The ROM picker will not run again when switching directly to a ROM.
            if(pendingOpen!=null) {
                Uri uri=pendingOpen;pendingOpen=null;
                MGBAUi.dismissAll();filePickerPending=false;launcherSubmenuPending=false;
                selectFile(activity,uri,MGBABrowser.AUTO);return;
            }
            if (pendingSnapshot != null) {
                PreparedFile file = pendingSnapshot;
                pendingSnapshot = null;
                nativePauseGame();
                menuOpen = true;
                Runnable back = () -> MGBASnapshots.showCurrent(activity, () -> showMainMenuDialog(activity));
                if (file.path.equals(nativeRomPath())) MGBASnapshots.load(activity, file.snapshot, back);
                else showFileError(activity, "当前游戏与快照不匹配", back);
                return;
            }
            if(pendingSave==null) return;
            PreparedFile file=pendingSave;pendingSave=null;
            nativePauseGame();menuOpen=true;usePreparedFile(activity,file);
        });
    }
    public static boolean handleOpenIntent(Activity activity, Intent intent) {
        if(intent==null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData()==null) return false;
        Uri uri=intent.getData();
        if(!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) return false;
        if("file".equals(uri.getScheme())) {
            try {
                String path=new File(uri.getPath()).getCanonicalPath();
                if(!path.startsWith("/storage/") && !path.startsWith("/sdcard/")) {
                    toast(activity,"请通过文件选择器打开此文件");return true;
                }
            } catch(Exception ex) {toast(activity,"文件路径不可用");return true;}
        }
        if(filesBusy()) {toast(activity,"正在处理上一个文件，请稍后重新打开");return true;}
        if(nativeNetBusy()) {toast(activity,"请先正常断开联机，再打开文件");return true;}
        pendingSave=null;
        pendingSnapshot=null;
        if(nativeIsGameLoaded() || menuOpen) {
            MGBAUi.dismissAll();filePickerPending=false;filePickerReturn=null;launcherSubmenuPending=false;
            selectFile(activity,uri,MGBABrowser.AUTO);
        } else pendingOpen=uri;
        return true;
    }

    static native String nativeSlotAction(int slot, boolean save);
    static native boolean nativeSlotOccupied(int slot);
    static native String nativeBackupCurrent();
    static native String nativeExportCurrent(boolean battery);
    static native boolean nativeIsRomFile(String path);
    static native String nativeRomPath();
    static native String nativeGameFolder();
    static native boolean nativeIsGameLoaded();
    static native int nativeGetPlatform();
    static native String nativeGetOption(String key);
    static native String nativeGetGameInfo();
    static native void nativePauseGame();
    static native void nativeResumeGame();
    static native void nativeHostPause(boolean minimized, boolean menuOpen);
    static native void nativeHostResume(boolean minimized, boolean menuOpen);
    static native void nativeSetOptionInt(String key, int value);
    static native void nativeSetOverlayOptions(int visible, int size, int opacity);
    static native void nativeSetOptionFloat(String key, float value);
    static native void nativeSetOptionString(String key, String value);
    static native void nativeClearOption(String key);
    static native void nativeGameAction(int action, int value);
    static native void nativeFileSelected(int kind, String path);
    static native String nativeImportSave(int kind, String path);
    static native boolean nativeNetBusy();
    static native boolean nativeNetHosting();
    static native boolean nativeSpeedActive();
    static native String nativeNetStatus();
    static native String nativeNetStart(boolean host, String address, String code);
    static native void nativeNetStop();
    static native void nativeCancelRomPicker();
}
