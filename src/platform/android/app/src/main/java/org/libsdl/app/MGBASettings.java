/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import java.io.File;
import static org.libsdl.app.MGBAMenu.*;

final class MGBASettings {
    private static String value(String key, String fallback) {
        String v = nativeGetOption(key); return v == null || v.isEmpty() ? fallback : v;
    }
    private static int integer(String key, int fallback) {
        try { return Integer.parseInt(value(key, Integer.toString(fallback))); } catch (NumberFormatException e) { return fallback; }
    }
    private static void put(String key, int v) {
        nativeSetOptionInt(key, v);
        if (key.startsWith("android.touch")) {
            saveTouchPreference(key.substring("android.".length()), v); updateOverlayPreferences();
        }
    }
    private static void toggle(MGBASettingsPage p, String name, String detail, String key, int fallback) {
        p.toggle(name, detail, integer(key, fallback) != 0, on -> put(key, on ? 1 : 0));
    }
    private static void ints(MGBASettingsPage p, String name, String detail, String key, int fallback, int[] values, String[] labels) {
        int current = integer(key, fallback), selected = -1;
        String[] names = labels == null ? new String[values.length] : labels;
        for (int i=0;i<values.length;++i) { if(labels == null) names[i] = Integer.toString(values[i]); if(current == values[i]) selected=i; }
        p.choice(name, detail, selected < 0 ? Integer.toString(current) : names[selected], names, selected, index -> put(key, values[index]));
    }
    private static void floats(MGBASettingsPage p, String name, String key, float fallback, float[] values, String[] labels) {
        float current = fallback; try { current = Float.parseFloat(value(key, Float.toString(fallback))); } catch (NumberFormatException ignored) { }
        int selected = -1;
        for(int i=0;i<values.length;++i) if(Math.abs(current-values[i]) < .001f) selected=i;
        p.choice(name, "", selected < 0 ? Float.toString(current) : labels[selected], labels, selected, index -> nativeSetOptionFloat(key,values[index]));
    }
    private static String translated(String v) {
        if(v.equalsIgnoreCase("Auto") || v.equals("detect")) return "自动";
        if(v.equals("remove")) return "移除空闲循环";
        if(v.equals("ignore")) return "不优化";
        return v;
    }
    private static void strings(MGBASettingsPage p, String name, String key, String fallback, String[] values) {
        String current=value(key,fallback); int selected=-1; String[] labels=new String[values.length];
        for(int i=0;i<values.length;++i) { labels[i]=translated(values[i]); if(current.equalsIgnoreCase(values[i])) selected=i; }
        p.choice(name,"",selected<0?current:labels[selected],labels,selected,index -> nativeSetOptionString(key,values[index]));
    }
    static void root(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"设置",()->fileManagerBack(a));
        p.note("开关即时生效；带箭头的选项可展开选择。取消不会更改设置。");
        p.group("画面、声音与操作");
        p.action("音频与视频","缩放、滤镜、音量、缓冲区与同步",()->audio(a));
        p.action("触控与输入","按键大小、不透明度与输入映射",()->controls(a));
        p.action("倍速与快进","点按 FF 持续加速；查看倍速目标",()->speed(a));
        p.action("界面与屏幕提示","帧率、文件名与屏幕提示",()->interfacePage(a));
        p.group("游戏与文件");
        p.action("模拟器设置","快进、倒带、自动存档和后台行为",()->emulation(a));
        p.action("存储路径","保存位置与文件管理",()->paths(a));
        p.action("BIOS 设置","引导文件与启动选项",()->bios(a));
        if(nativeGetPlatform()==1) p.action("Game Boy 设置","机型与 SGB 边框",()->gb(a));
        else p.action("Game Boy Advance 设置","兼容性与空闲循环优化",()->gba(a));
        p.show();
    }
    static void audio(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"音频与视频",()->root(a));
        p.group("画面");
        toggle(p,"保持画面比例","避免拉伸游戏画面","lockAspectRatio",1);
        toggle(p,"整数倍缩放","像素边缘更清晰，可能保留黑边","lockIntegerScaling",0);
        toggle(p,"双线性滤镜","平滑放大后的像素","resampleVideo",0);
        toggle(p,"帧间混合","模拟部分掌机画面的残影","interframeBlending",0);
        ints(p,"跳帧","0 表示不跳帧","frameskip",0,new int[]{0,1,2,3,4,5,6,8,10},null);
        floats(p,"目标帧率","fpsTarget",0,new float[]{0,15,30,45,60,90,120,240},
            new String[]{"原生帧率","15 FPS","30 FPS","45 FPS","60 FPS","90 FPS","120 FPS","240 FPS"});
        p.group("声音");
        toggle(p,"音频静音","关闭游戏声音","mute",0);
        int volume=Math.max(0,Math.min(100,Math.round(integer("volume",256)*100f/256)));
        p.slider("音量","拖动后松手应用",volume,0,100,"%",v->nativeSetOptionInt("volume",Math.round(v*256f/100)));
        ints(p,"快进音量","仅在快进期间使用","fastForwardVolume",-1,new int[]{-1,0,64,128,192,256},
            new String[]{"跟随正常音量","0%","25%","50%","75%","100%"});
        toggle(p,"快进时静音","","fastForwardMute",0);
        ints(p,"音频缓冲区","4096 为稳定默认值；保留队列余量，并用较小块提交声音以减少停顿","audioBuffers",4096,
            new int[]{256,512,1024,2048,4096,8192},
            new String[]{"256 个采样帧","512 个采样帧","1024 个采样帧","2048 个采样帧","4096 个采样帧（默认）","8192 个采样帧"});
        ints(p,"输出采样率","选择后重新打开音频输出","sampleRate",44100,new int[]{22050,32000,44100,48000},
            new String[]{"22050 Hz","32000 Hz","44100 Hz","48000 Hz"});
        p.group("播放同步");
        toggle(p,"视频同步","保持稳定的画面节奏","videoSync",1);
        toggle(p,"音频同步","保持声音与模拟速度同步","audioSync",1);
        p.show();
    }
    static void emulation(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"模拟器设置",()->root(a));
        p.group("模拟速度");
        toggle(p,"启用倒带","保留最近一段游戏进度","rewindEnable",0);
        ints(p,"倒带缓冲容量","越大占用内存越多","rewindBufferCapacity",600,new int[]{60,120,300,600,900,1200,1800,2400,3600},null);
        ints(p,"倒带间隔","保存倒带快照的帧间隔","rewindBufferInterval",1,new int[]{1,2,3,4,5,10},null);
        float[] speeds={-1,2,3,4,5,6,8,10}; String[] labels={"不限速","2 倍","3 倍","4 倍","5 倍","6 倍","8 倍","10 倍"};
        floats(p,"快进倍速","fastForwardRatio",-1,speeds,labels);
        floats(p,"按住快进时的倍速","fastForwardHeldRatio",-1,speeds,labels);
        toggle(p,"游戏时禁止屏幕休眠","","suspendScreensaver",1);
        toggle(p,"允许同时按下相反方向","","allowOpposingDirections",0);
        toggle(p,"将 ROM 预加载到内存","较大的游戏需要更多内存","preload",1);
        p.group("自动保存与后台行为");
        String[] keys={"autoload","autosave","cheatAutoload","cheatAutosave","pauseOnFocusLost","pauseOnMinimize","muteOnFocusLost","muteOnMinimize"};
        String[] names={"启动时载入上次即时存档","退出时自动保存即时存档","自动载入金手指","自动保存金手指","应用失去焦点时暂停","应用最小化时暂停","应用失去焦点时静音","应用最小化时静音"};
        int[] defaults={1,0,1,1,0,1,0,0};
        for(int i=0;i<keys.length;++i) toggle(p,names[i],"",keys[i],defaults[i]);
        p.group("即时存档内容");
        String[] parts={"截图","游戏数据","金手指"};
        for(int load=0;load<2;++load) for(int i=0;i<3;++i) {
            String key=load==0?"saveStateExtdata":"loadStateExtdata"; int flag=1<<i, fallback=load==0?31:9;
            p.toggle((load==0?"即时存档包含":"读取即时存档时载入")+parts[i],"",(integer(key,fallback)&flag)!=0,on->{
                int current=integer(key,fallback); nativeSetOptionInt(key,on?current|flag:current&~flag);
            });
        }
        ints(p,"日志级别","","logLevel",7,new int[]{1,3,7,15,31},new String[]{"仅致命错误","错误","警告","信息","调试"});
        p.show();
    }
    static void controls(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"触控与输入",()->root(a));
        toggle(p,"显示触控按键","顶部齿轮始终可以打开菜单","android.touchControls",1);
        p.slider("触控按键大小","按屏幕比例缩放，松手应用",integer("android.touchSize",100),70,200,"%",v->put("android.touchSize",v));
        p.slider("触控按键不透明度","松手应用",integer("android.touchOpacity",48),10,100,"%",v->put("android.touchOpacity",v));
        toggle(p,"允许同时按下相反方向","","allowOpposingDirections",0);
        p.action("恢复默认键盘／手柄按键映射","需要确认；不改变触控大小与音量",()->
            new MGBAUi.Builder(a).setTitle("恢复默认按键映射？").setMessage("当前自定义的键盘和手柄映射将被替换。")
                .setPositiveButton("恢复默认",(d,w)->{nativeGameAction(8,0);controls(a);})
                .setNegativeButton("取消",(d,w)->controls(a)).setOnCancelListener(d->controls(a)).show());
        p.show();
    }
    static void globalTouch(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"触控按键设置",()->finishGlobalSettings(a));
        p.note("松手应用；游戏已有的单独设置会继续保留。");
        p.toggle("显示触控按键","顶部齿轮始终可用",preferenceInt("touchControls",1)!=0,on->{
            saveTouchPreference("touchControls",on?1:0);updateOverlayPreferences();
        });
        p.slider("按键大小","",preferenceInt("touchSize",100),70,200,"%",v->{saveTouchPreference("touchSize",v);updateOverlayPreferences();});
        p.slider("按键不透明度","",preferenceInt("touchOpacity",48),10,100,"%",v->{saveTouchPreference("touchOpacity",v);updateOverlayPreferences();});
        p.show();
    }
    static void speed(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"倍速与快进",()->fileManagerBack(a));
        p.note("点一下屏幕 FF 持续加速，再点恢复；键盘 Tab 按住加速。画面显示实际倍速与目标，最高速度取决于设备和游戏。加速期间暂停倒带记录，正常速度自动恢复。联机时两台由主机统一加速，任一设备可切换，最高目标 10 倍。");
        floats(p,"快进倍速","fastForwardRatio",-1,new float[]{2,3,4,6,8,10,-1},new String[]{"2 倍","3 倍","4 倍","6 倍","8 倍","10 倍","不限速"});
        floats(p,"按住快进时的倍速","fastForwardHeldRatio",-1,new float[]{2,3,4,6,8,10,-1},new String[]{"2 倍","3 倍","4 倍","6 倍","8 倍","10 倍","不限速"});
        toggle(p,"快进时静音","正常速度时恢复原声音设置","fastForwardMute",0);
        p.action(nativeSpeedActive()?"关闭快进并返回游戏":"开启快进并返回游戏","与屏幕 FF 按钮相同",()->{nativeGameAction(5,0);finishSpeedMenu();});
        p.show();
    }
    static void interfacePage(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"界面与屏幕提示",()->root(a));
        String[] keys={"showOSD","showFrameCounter","showFps","showFilename","showResetInfo"};
        String[] names={"显示屏幕提示","显示帧计数器","显示帧率（FPS）","显示 ROM 文件名","重启时显示模拟器信息"};
        int[] defaults={1,0,1,0,0};
        for(int i=0;i<keys.length;++i) toggle(p,names[i],"",keys[i],defaults[i]);
        p.show();
    }
    static void gb(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"Game Boy 设置",()->root(a));p.note("改变机型后建议重新启动游戏。");
        toggle(p,"显示 Super Game Boy 边框","","sgb.borders",1);
        strings(p,"GB 机型","gb.model","Auto",new String[]{"Auto","DMG","MGB","SGB","CGB","AGB"});
        strings(p,"Super Game Boy 机型","sgb.model","Auto",new String[]{"Auto","SGB","SGB2"});
        strings(p,"Game Boy Color 机型","cgb.model","Auto",new String[]{"Auto","CGB","AGB"});
        strings(p,"CGB 混合机型","cgb.hybridModel","Auto",new String[]{"Auto","CGB","AGB"});
        strings(p,"CGB／SGB 机型","cgb.sgbModel","Auto",new String[]{"Auto","SGB","CGB"});p.show();
    }
    static void gba(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"Game Boy Advance 设置",()->root(a));
        toggle(p,"启用 Game Boy Player 功能","","gba.forceGbp",0);
        toggle(p,"VBA 错误兼容模式","仅在游戏需要兼容时启用","vbaBugCompat",0);
        strings(p,"空闲循环优化","idleOptimization","detect",new String[]{"detect","remove","ignore"});p.show();
    }
    static void bios(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"BIOS 设置",()->root(a));
        toggle(p,"找到 BIOS 文件时使用","","useBios",1);toggle(p,"跳过 BIOS 开场动画","","skipBios",0);
        if(nativeGetPlatform()==0) p.action("选择 GBA BIOS 文件…",value("gba.bios","未选择"),()->pickSystemFiles(a,7101));
        else {
            p.action("选择 Game Boy BIOS 文件…",value("gb.bios","未选择"),()->pickSystemFiles(a,7102));
            p.action("选择 Game Boy Color BIOS 文件…",value("gbc.bios","未选择"),()->pickSystemFiles(a,7103));
            p.action("选择 Super Game Boy BIOS 文件…",value("sgb.bios","未选择"),()->pickSystemFiles(a,7104));
        } p.show();
    }
    static void saveGuide(Activity a,Runnable back) {
        MGBASettingsPage p=new MGBASettingsPage(a,"存档使用说明",back);
        p.group("游戏文件不是存档");
        p.note(".gba／.gb／.gbc 是游戏本体。进度另存为游戏存档或即时存档；保留同一版本的 ROM，加载进度才容易对应。");
        p.group("游戏存档 · .sav");
        p.note("在游戏自己的菜单里保存，例如宝可梦的“保存”。重新开机后从游戏里的“继续”进入。模拟器负责保存对应文件，日常不用手动找安卓内部目录。");
        p.group("即时存档 · .ss／.ss1／.state1 等");
        p.note("在游戏菜单的“存档快照”中新建快照，会保存当前进度和游戏画面。可查看截图、重命名或导出；每次新建独立保存，数量只受存储空间限制。原来的 1～9 号仍可在“旧版存档位”使用。");
        p.group("原文件与工作副本");
        p.note("游戏库按 ROM 内容区分游戏。同一个 ROM 改名也使用同一份进度。带名称和截图的快照位于每个游戏的 Snapshots 子目录；自定义即时存档目录继续用于旧版存档位和自动即时存档。Imports、Backups、Exports 分别保留导入、回退和导出副本。");
        p.group("备份与换手机");
        p.note("回到“存档管理”，选择“导出当前即时进度”保存最新快照，或先在游戏里保存再导出 .sav。把导出的文件放到 Download 或自己的备份文件夹；另一台打开对应 ROM 后导入即可。最近列表的“导出此文件的副本”只复制所选文件，不会更新旧文件里的进度。");
        p.group("退出和清理之前");
        p.note("日常以游戏自己的保存为主，再用即时存档补充。即时存档请主动保存，除非已开启自动保存。卸载或清除应用数据前先导出到自己选择的公共文件夹；应用专属目录内的文件会被清理。");
        p.show();
    }
    static void paths(Activity a) {
        MGBASettingsPage p=new MGBASettingsPage(a,"存储路径",()->root(a));
        p.note("默认在游戏库的每个游戏文件夹内保存；原有自定义目录继续沿用。修改路径只影响以后保存，不移动或覆盖现有存档。");
        String[] keys={"savegamePath","savestatePath","screenshotPath","patchPath","cheatsPath"};
        String[] names={"游戏存档目录","即时存档目录","截图目录","补丁目录","金手指目录"};
        String[] defaults={"saves","savestates","screenshots","patches","cheats"};
        for(int i=0;i<keys.length;++i) {
            final int index=i; String current=value(keys[i],"");
            p.action(names[i],current.isEmpty()?"与 ROM 位于同一目录":current,()->
                new MGBAUi.Builder(a).setTitle(names[index]).setItems(new String[]{"使用应用默认目录","与 ROM 位于同一目录","选择应用内文件夹…"},(d,w)->{
                    if(w==0) {nativeSetOptionString(keys[index],defaults[index]);paths(a);}
                    else if(w==1) {nativeClearOption(keys[index]);paths(a);}
                    else chooseDirectory(a,keys[index],names[index]);
                }).setNegativeButton("取消",(d,w)->paths(a)).setOnCancelListener(d->paths(a)).show());
        }
        p.action("文件管理","浏览、导入、导出存档与恢复备份",()->MGBABrowser.show(a,MGBABrowser.AUTO));p.show();
    }
}
