/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.*;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** DNS-SD advertises only a listening room; discovery lives with its dialog. */
final class MGBALanDiscovery {
    private static final String TYPE = "_mgba-link._tcp.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static NsdManager manager;
    private static NsdManager.RegistrationListener registration;
    private static String hostCode, hostGame, registeredName;
    private static boolean foreground = true, registrationFailed;
    private static Browser browser;
    private static NsdManager manager(Context context) {
        if (manager == null) manager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        return manager;
    }
    static void host(Activity activity, String code) {
        manager(activity); stopAdvertising(); hostCode=code; registrationFailed=false;
        String game=MGBAMenu.nativeGetGameInfo().split("\n")[0];
        hostGame=game.length()>70?game.substring(0,70):game;
        MAIN.removeCallbacks(HOST_TICK); MAIN.post(HOST_TICK);
    }
    private static final Runnable HOST_TICK = new Runnable() {
        public void run() {
            if (hostCode == null || !foreground) return;
            if (!MGBAMenu.nativeNetBusy()) { hostCode=null; stopAdvertising(); return; }
            if (MGBAMenu.nativeNetHosting() && registration == null && !registrationFailed) advertise();
            else if (!MGBAMenu.nativeNetHosting()) stopAdvertising();
            MAIN.postDelayed(this,700);
        }
    };
    private static void advertise() {
        if (manager == null) { registrationFailed=true; return; }
        NsdServiceInfo info=new NsdServiceInfo();
        info.setServiceName("mGBA - "+Build.MODEL);
        info.setServiceType(TYPE); info.setPort(8765);
        info.setAttribute("v","3"); info.setAttribute("code",hostCode);
        info.setAttribute("game",hostGame);
        NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo service) { MAIN.post(()->{
                if (registration != this) { try { manager.unregisterService(this); } catch(Exception ignored) {} return; }
                registeredName=service.getServiceName(); Log.i("mGBALan","Room advertised: "+registeredName);
            }); }
            public void onRegistrationFailed(NsdServiceInfo s,int error) { MAIN.post(()->{
                if (registration == this) { registration=null; registrationFailed=true; }
                Log.w("mGBALan","Room advertisement failed: "+error);
            }); }
            public void onServiceUnregistered(NsdServiceInfo s) { Log.i("mGBALan","Room advertisement removed"); }
            public void onUnregistrationFailed(NsdServiceInfo s,int error) { Log.w("mGBALan","Unregister failed: "+error); }
        };
        registration=listener;
        try { manager.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener); }
        catch(Exception ex) { registration=null; registrationFailed=true; Log.w("mGBALan","Cannot advertise",ex); }
    }
    private static void stopAdvertising() {
        NsdManager.RegistrationListener old=registration; registration=null; registeredName=null;
        if (old != null && manager != null) try { manager.unregisterService(old); } catch(Exception ignored) {}
    }
    static void onPause() {
        foreground=false; MAIN.removeCallbacks(HOST_TICK); stopAdvertising();
        if (browser != null) browser.stop();
    }
    static void onResume(Activity activity) {
        manager(activity); foreground=true; MAIN.removeCallbacks(HOST_TICK);
        if (hostCode != null) MAIN.post(HOST_TICK);
        if (browser != null && browser.activity==activity) browser.start();
    }
    static void onDestroy(Activity activity) {
        if (browser != null && browser.activity==activity) browser.dialog.dismiss();
        hostCode=null; MAIN.removeCallbacks(HOST_TICK); stopAdvertising();
    }
    static void browse(Activity activity) {
        if (browser != null) browser.dialog.dismiss();
        browser=new Browser(activity); browser.show();
    }
    static final class Room {
        final String name,address,code,game;
        Room(String name,String address,String code,String game) { this.name=name;this.address=address;this.code=code;this.game=game; }
        public String toString() { return name+"\n"+game+" · "+address; }
    }
    private static String attribute(NsdServiceInfo info,String key) {
        byte[] b=info.getAttributes().get(key);
        return b == null || b.length>200 ? "" : new String(b,StandardCharsets.UTF_8);
    }
    private static final class Browser {
        final Activity activity;
        final NsdManager nsd;
        final ArrayList<Room> rooms=new ArrayList<>();
        final Set<String> present=new HashSet<>();
        final ArrayDeque<NsdServiceInfo> pending=new ArrayDeque<>();
        AlertDialog dialog;
        TextView notice;
        ArrayAdapter<Room> adapter;
        NsdManager.DiscoveryListener listener;
        boolean resolving;
        int generation;
        Browser(Activity activity) { this.activity=activity;nsd=manager(activity); }
        void show() {
            LinearLayout content=new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
            int pad=MGBAUi.dp(activity,20);content.setPadding(pad,0,pad,pad);
            notice=new TextView(activity);notice.setTextSize(14);notice.setTextColor(0xFFCCCCCC);content.addView(notice);
            ListView list=new ListView(MGBAUi.context(activity));
            adapter=new ArrayAdapter<Room>(MGBAUi.context(activity),android.R.layout.simple_list_item_1,rooms);
            list.setAdapter(adapter);content.addView(list,new LinearLayout.LayoutParams(-1,MGBAUi.dp(activity,210)));
            list.setOnItemClickListener((p,v,i,id)->{
                Room room=rooms.get(i);dialog.dismiss();
                new MGBAUi.Builder(activity).setTitle("加入 "+room.name+"？")
                    .setMessage(room.game+"\n\n会自动备份当前进度，并将游戏和存档副本发送给这台主机。主机运行两个同步实例；客机暂不播放声音。请使用可信的同一 Wi-Fi。")
                    .setPositiveButton("加入房间",(d,w)->MGBAMenu.joinDiscovered(activity,room.address,room.code))
                    .setNegativeButton("返回",(d,w)->browse(activity)).setOnCancelListener(d->browse(activity)).show();
            });
            dialog=new MGBAUi.Builder(activity).setTitle("附近的 mGBA 房间").setView(content)
                .setPositiveButton("重新搜索",(d,w)->browse(activity))
                .setNeutralButton("手动输入",(d,w)->MGBAMenu.showManualJoin(activity))
                .setNegativeButton("返回",(d,w)->MGBAMenu.showNetwork(activity))
                .setOnCancelListener(d->MGBAMenu.showNetwork(activity)).create();
            dialog.setOnDismissListener(d->{stop();if(browser==this)browser=null;});
            dialog.show();MGBAUi.wide(activity,dialog,false);start();
        }
        void update() {
            adapter.notifyDataSetChanged();
            notice.setText(rooms.isEmpty()?"正在查找同一 Wi-Fi 下等待加入的房间…\n让另一台手机创建房间并保持前台；没有结果可手动输入。":"发现 "+rooms.size()+" 个房间，点选即可加入，无需输入 IP 和房间码。");
        }
        void start() {
            if (listener!=null || dialog==null || !dialog.isShowing() || !foreground) return;
            rooms.clear();present.clear();pending.clear();resolving=false;update();
            final int ticket=++generation;
            if (nsd==null) {notice.setText("系统自动发现不可用，请使用手动输入。");return;}
            listener=new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) { Log.i("mGBALan","Discovery started"); }
                public void onServiceFound(NsdServiceInfo s) { MAIN.post(()->{
                    if(ticket!=generation || !TYPE.equals(s.getServiceType()) && !TYPE.substring(0,TYPE.length()-1).equals(s.getServiceType())) return;
                    if(s.getServiceName().equals(registeredName) || present.size()>=32 || !present.add(s.getServiceName())) return;
                    pending.add(s);resolveNext(ticket);
                }); }
                public void onServiceLost(NsdServiceInfo s) { MAIN.post(()->{
                    if(ticket!=generation)return;
                    present.remove(s.getServiceName());removeRoom(s.getServiceName());update();
                }); }
                public void onDiscoveryStopped(String t) { Log.i("mGBALan","Discovery stopped"); }
                public void onStartDiscoveryFailed(String t,int e) { MAIN.post(()->{
                    if(ticket==generation) {stop();notice.setText("自动搜索失败（"+e+"），可重新搜索或手动输入。");}
                }); }
                public void onStopDiscoveryFailed(String t,int e) { Log.w("mGBALan","Discovery stop failed: "+e); }
            };
            try { nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,listener); }
            catch(Exception ex) {stop();notice.setText("自动搜索不可用，请使用手动输入。");Log.w("mGBALan","Discover failed",ex);}
        }
        void stop() {
            ++generation;pending.clear();present.clear();resolving=false;
            NsdManager.DiscoveryListener old=listener;listener=null;
            if(old!=null && nsd!=null)try{nsd.stopServiceDiscovery(old);}catch(Exception ignored){}
        }
        void removeRoom(String name) {
            for(int i=rooms.size()-1;i>=0;--i)if(rooms.get(i).name.equals(name))rooms.remove(i);
        }
        void resolveNext(int ticket) {
            if(ticket!=generation || resolving || pending.isEmpty())return;
            NsdServiceInfo found=pending.removeFirst();
            if(!present.contains(found.getServiceName())){resolveNext(ticket);return;}
            resolving=true;
            try { nsd.resolveService(found,new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s,int e) { MAIN.post(()->{
                    if(ticket!=generation)return;resolving=false;resolveNext(ticket);
                }); }
                public void onServiceResolved(NsdServiceInfo s) { MAIN.post(()->{
                    if(ticket!=generation)return;resolving=false;
                    String code=attribute(s,"code");InetAddress address=null;
                    if(Build.VERSION.SDK_INT>=34) {
                        for(InetAddress ip:s.getHostAddresses())if(ip instanceof Inet4Address){address=ip;break;}
                    }
                    if(address==null && s.getHost() instanceof Inet4Address)address=s.getHost();
                    if(present.contains(s.getServiceName()) && s.getPort()==8765 && "3".equals(attribute(s,"v")) && code.matches("[0-9]{6}") && address!=null && !address.isLoopbackAddress()) {
                        removeRoom(s.getServiceName());
                        rooms.add(new Room(s.getServiceName(),address.getHostAddress(),code,attribute(s,"game")));
                        Collections.sort(rooms,(a,b)->a.name.compareToIgnoreCase(b.name));update();
                        Log.i("mGBALan","Resolved room: "+s.getServiceName()+" at "+address.getHostAddress());
                    }
                    resolveNext(ticket);
                }); }
            }); } catch(Exception ex) {resolving=false;resolveNext(ticket);Log.w("mGBALan","Resolve failed",ex);}
        }
    }
}
