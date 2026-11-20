/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

final class MGBALibrary {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static File root(Activity a)throws IOException {
        File f=a.getExternalFilesDir("Games");if(f==null)throw new IOException("游戏库存储暂不可用，请检查设备存储");return f;
    }
    static File currentFolder(){String p=MGBAMenu.nativeGameFolder();return p==null || p.isEmpty()?null:new File(p);}
    static File backups(Activity a){File f=currentFolder();return f==null?new File(a.getFilesDir(),"mgba-backups"):new File(f,"Backups");}
    static File archive(File source,String name)throws IOException {
        String path=MGBAMenu.nativeRomPath();return path==null || path.isEmpty()?source:MGBAGameLibrary.archive(new File(path),source,name,"Imports");
    }
    static void show(Activity a){
        show(a, () -> MGBAMenu.fileManagerBack(a));
    }
    static void show(Activity a, Runnable back){
        AlertDialog wait=new MGBAUi.Builder(a).setTitle("游戏库").setMessage("正在读取统一目录…").setCancelable(false).show();
        IO.execute(()->{
            ArrayList<MGBAGameLibrary.Game> list=null;String failure=null;File directory=null;
            try{directory=root(a);list=MGBAGameLibrary.list(directory);}catch(Exception e){failure=e.getMessage();}
            final ArrayList<MGBAGameLibrary.Game> games=list;final String error=failure;final File folder=directory;
            a.runOnUiThread(()->{
                wait.dismiss();if(a.isFinishing() || a.isDestroyed())return;
                if(error!=null){new MGBAUi.Builder(a).setTitle("无法读取游戏库").setMessage(error).setPositiveButton("返回",(d,w)->back.run()).setOnCancelListener(d->back.run()).show();return;}
                LinearLayout view=new LinearLayout(a);view.setOrientation(LinearLayout.VERTICAL);int pad=MGBAUi.dp(a,16);view.setPadding(pad,0,pad,0);
                view.setLayoutParams(new FrameLayout.LayoutParams(-1,a.getResources().getDisplayMetrics().heightPixels*72/100));
                if (!games.isEmpty()) {
                    TextView note = new TextView(a);
                    note.setText("点按打开游戏，长按查看存档快照");
                    view.addView(note);
                }
                ListView rows=new ListView(a);ArrayList<Map<String,String>> data=new ArrayList<>();
                for(MGBAGameLibrary.Game g:games){Map<String,String> row=new HashMap<>();row.put("title",g.name);data.add(row);}
                rows.setAdapter(new SimpleAdapter(a,data,android.R.layout.simple_list_item_1,new String[]{"title"},new int[]{android.R.id.text1}));
                TextView empty=new TextView(a);empty.setText("游戏库为空。选择“打开其他游戏”导入；已有旧游戏在下次打开时归档。");view.addView(empty);rows.setEmptyView(empty);
                view.addView(rows,new LinearLayout.LayoutParams(-1,0,1));
                AlertDialog dialog=new MGBAUi.Builder(a).setTitle("游戏库 · "+games.size()+" 个游戏").setView(view)
                    .setPositiveButton("打开其他游戏",(d,w)->MGBAMenu.pickSystemFiles(a,7107,()->show(a,back)))
                    .setNegativeButton("返回",(d,w)->back.run())
                    .setNeutralButton("浏览统一目录",(d,w)->MGBABrowser.browseLocal(a,MGBABrowser.AUTO,folder,folder))
                    .setOnCancelListener(d->back.run()).create();
                rows.setOnItemClickListener((p,v,i,id)->{dialog.dismiss();open(a,games.get(i));});
                rows.setOnItemLongClickListener((p,v,i,id)->{MGBAGameLibrary.Game g=games.get(i);dialog.dismiss();
                    new MGBAUi.Builder(a).setTitle(g.name).setItems(new String[]{"存档快照","打开游戏","浏览游戏文件","查看位置与校验值"},(d,w)->{
                        if(w==0) MGBASnapshots.showGame(a,g,()->show(a,back));
                        else if(w==1)open(a,g);
                        else if(w==2)MGBABrowser.browseLocal(a,MGBABrowser.AUTO,g.folder,g.folder);
                        else new MGBAUi.Builder(a).setTitle("游戏文件").setMessage(g.folder.getAbsolutePath()+"\n\nSHA-256："+g.hash)
                            .setPositiveButton("返回",(dd,ww)->show(a,back)).setOnCancelListener(dd->show(a,back)).show();
                    }).setNegativeButton("返回",(d,w)->show(a,back)).setOnCancelListener(d->show(a,back)).show();return true;});
                dialog.show();MGBAUi.wide(a,dialog,true);
            });
        });
    }
    private static void open(Activity a,MGBAGameLibrary.Game g){MGBAMenu.selectCachedFile(a,Uri.fromFile(g.rom),g.name,g.rom.getAbsolutePath(),7107);}
}
