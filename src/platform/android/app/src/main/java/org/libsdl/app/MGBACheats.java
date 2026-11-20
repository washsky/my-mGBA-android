/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

final class MGBACheats {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    static boolean isBusy(){return busy;}
    interface Result { void done(String error); }
    static native String nativeAction(int action,int index,int format,boolean enabled,String name,String text);
    static void show(Activity a){
        if(busy || MGBAMenu.filesBusy()){Toast.makeText(a,"正在处理文件，请稍候",Toast.LENGTH_SHORT).show();return;}
        String response=nativeAction(0,0,0,false,null,null);final JSONArray sets;
        try{sets=new JSONArray(response);}catch(Exception e){error(a,response,()->MGBAMenu.fileManagerBack(a));return;}
        LinearLayout view=new LinearLayout(a);view.setOrientation(LinearLayout.VERTICAL);int pad=MGBAUi.dp(a,16);view.setPadding(pad,0,pad,0);
        view.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1,a.getResources().getDisplayMetrics().heightPixels*72/100));
        TextView note=new TextView(a);note.setText("代码需匹配当前游戏版本。修改前会备份进度和金手指文件。关闭只停止继续修改，已有的效果可能需要重启或读取备份恢复。");note.setTextSize(12);view.addView(note);
        EditText search=new EditText(MGBAUi.context(a));search.setSingleLine(true);search.setHint("搜索名称或代码");view.addView(search);
        ListView list=new ListView(a);view.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        ArrayList<JSONObject> all=new ArrayList<>(),shown=new ArrayList<>();for(int i=0;i<sets.length();++i)all.add(sets.optJSONObject(i));shown.addAll(all);
        AlertDialog dialog=new MGBAUi.Builder(a).setTitle("金手指 · "+sets.length()+" 组").setView(view).setPositiveButton("添加",(d,w)->edit(a,null)).setNeutralButton("更多",(d,w)->more(a)).setNegativeButton("返回",(d,w)->MGBAMenu.fileManagerBack(a)).setOnCancelListener(d->MGBAMenu.fileManagerBack(a)).create();
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return shown.size();}public Object getItem(int i){return shown.get(i);}public long getItemId(int i){return shown.get(i).optInt("id");}
            public View getView(int i,View old,ViewGroup parent){JSONObject item=shown.get(i);LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,MGBAUi.dp(a,8),0,MGBAUi.dp(a,8));
                CheckBox toggle=new CheckBox(MGBAUi.context(a));toggle.setFocusable(false);toggle.setChecked(item.optBoolean("enabled"));toggle.setContentDescription("启用 "+item.optString("name"));row.addView(toggle);
                LinearLayout labels=new LinearLayout(a);labels.setOrientation(LinearLayout.VERTICAL);TextView title=new TextView(a);String name=item.optString("name");title.setText(name.isEmpty()?"未命名代码":name);title.setTextColor(0xFFFFFFFF);title.setTextSize(16);labels.addView(title);
                TextView code=new TextView(a);String text=item.optString("code");code.setText((item.optBoolean("enabled")?"已启用":"已关闭")+" · "+(text.isEmpty()?0:text.split("\n").length)+" 行 · "+text.split("\n",2)[0]);code.setTextColor(0xFFBBBBBB);code.setTextSize(12);code.setSingleLine(true);labels.addView(code);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                toggle.setOnClickListener(v->{dialog.dismiss();run(a,2,item.optInt("id"),0,toggle.isChecked(),null,null,err->finish(a,err));});return row;
            }
        };list.setAdapter(adapter);
        list.setOnItemClickListener((p,v,i,id)->{JSONObject item=shown.get(i);dialog.dismiss();edit(a,item);});
        list.setOnItemLongClickListener((p,v,i,id)->{JSONObject item=shown.get(i);dialog.dismiss();new MGBAUi.Builder(a).setTitle(item.optString("name")).setItems(new String[]{"编辑名称与代码","删除此组…"},(d,w)->{if(w==0)edit(a,item);else new MGBAUi.Builder(a).setTitle("删除这组金手指？").setMessage("会保留修改前的金手指文件和进度备份。").setPositiveButton("删除",(dd,ww)->run(a,3,item.optInt("id"),0,false,null,null,err->finish(a,err))).setNegativeButton("取消",(dd,ww)->show(a)).show();}).setNegativeButton("返回",(d,w)->show(a)).show();return true;});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){String q=s.toString().toLowerCase(Locale.ROOT);shown.clear();for(JSONObject item:all)if((item.optString("name")+" "+item.optString("code")).toLowerCase(Locale.ROOT).contains(q))shown.add(item);adapter.notifyDataSetChanged();}public void afterTextChanged(Editable e){}});
        if(all.isEmpty()){TextView empty=new TextView(a);empty.setText("尚未添加金手指。可粘贴代码或从“更多”导入文件。");view.addView(empty);}
        dialog.show();MGBAUi.wide(a,dialog,true);
    }
    private static void edit(Activity a,JSONObject item){
        boolean editing=item!=null;LinearLayout view=new LinearLayout(a);view.setOrientation(LinearLayout.VERTICAL);int p=MGBAUi.dp(a,16);view.setPadding(p,0,p,0);
        EditText name=new EditText(MGBAUi.context(a));name.setSingleLine(true);name.setHint("名称，例如某项效果");name.setText(editing?item.optString("name"):"");view.addView(name);
        Spinner formats=new Spinner(MGBAUi.context(a));ArrayList<String> labels=new ArrayList<>();ArrayList<Integer> values=new ArrayList<>();if(editing){labels.add("保留原代码格式");values.add(-1);}
        String[] types=MGBAMenu.nativeGetPlatform()==1?new String[]{"自动识别","GameShark","Game Genie","VBA 原始地址"}:new String[]{"自动识别","CodeBreaker","GameShark","Action Replay","VBA 原始地址"};for(int i=0;i<types.length;++i){labels.add(types[i]);values.add(i);}
        ArrayAdapter<String> choices=new ArrayAdapter<>(MGBAUi.context(a),android.R.layout.simple_spinner_item,labels);choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);formats.setAdapter(choices);view.addView(formats);
        EditText code=new EditText(MGBAUi.context(a));code.setTypeface(Typeface.MONOSPACE);code.setTextSize(14);code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setMinLines(5);code.setMaxLines(10);code.setGravity(Gravity.TOP);code.setHint("每行一条完整代码\n地址和值放在同一行，多行指令必须完整");code.setText(editing?item.optString("code"):"");view.addView(code);
        CheckBox enabled=new CheckBox(MGBAUi.context(a));enabled.setText("保存后启用");enabled.setChecked(editing && item.optBoolean("enabled"));view.addView(enabled);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.addView(view);AlertDialog dialog=new MGBAUi.Builder(a).setTitle(editing?"编辑金手指":"添加金手指").setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",(d,w)->show(a)).setOnCancelListener(d->show(a)).create();dialog.show();MGBAUi.wide(a,dialog,true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String text=code.getText().toString().replace('\u3000',' ').replace("\r\n","\n");run(a,1,editing?item.optInt("id"):-1,values.get(formats.getSelectedItemPosition()),enabled.isChecked(),name.getText().toString().trim(),text,err->{if(err!=null)error(a,err,()->{});else{dialog.dismiss();show(a);}});});
    }
    private static void more(Activity a){new MGBAUi.Builder(a).setTitle("金手指工具").setItems(new String[]{"导入文件（追加／替换）…","导出当前金手指…","全部关闭","清空列表…","触发金手指按键一次并返回游戏"},(d,w)->{
        if(w==0)MGBAMenu.pickSystemFiles(a,7106);
        else if(w==1)run(a,6,0,0,false,null,null,result->{if(result==null || !result.startsWith("/"))error(a,result,()->show(a));else MGBABrowser.exportCopy(a,Uri.fromFile(new File(result)),"mgba-cheats.cheats",()->show(a));});
        else if(w==2)run(a,4,0,0,false,null,null,err->finish(a,err));else if(w==3)confirmClear(a);
        else run(a,8,0,0,false,null,null,err->{if(err!=null)error(a,err,()->show(a));else MGBAMenu.returnToGame();});
    }).setNegativeButton("返回",(d,w)->show(a)).setOnCancelListener(d->show(a)).show();}
    static void confirmClear(Activity a){new MGBAUi.Builder(a).setTitle("清空金手指列表？").setMessage("保存修改前的金手指和进度备份后清空。不会删除原始导入文件。").setPositiveButton("清空",(d,w)->run(a,7,0,0,false,null,null,err->finish(a,err))).setNegativeButton("取消",(d,w)->show(a)).show();}
    static void importFile(Activity a,String path){new MGBAUi.Builder(a).setTitle("导入金手指文件").setMessage("支持 mGBA .cheats、Libretro .cht 和逐行代码文本；导入后沿用文件内开关。无效或不完整代码不会覆盖现有列表。").setPositiveButton("追加，保留现有",(d,w)->importNow(a,path,false))
        .setNeutralButton("替换现有",(d,w)->new MGBAUi.Builder(a).setTitle("替换当前金手指？").setMessage("会保留修改前备份；原始文件不变。").setPositiveButton("备份并替换",(dd,ww)->importNow(a,path,true)).setNegativeButton("取消",(dd,ww)->show(a)).show()).setNegativeButton("取消",(d,w)->show(a)).setOnCancelListener(d->show(a)).show();}
    private static void importNow(Activity a,String path,boolean replace){run(a,5,replace?1:0,0,false,null,path,err->finish(a,err));}
    private static void finish(Activity a,String error){if(error!=null)error(a,error,()->show(a));else show(a);}
    private static void run(Activity a,int action,int index,int type,boolean enabled,String name,String text,Result done){
        if(busy || MGBAMenu.filesBusy()){Toast.makeText(a,"正在处理文件，请稍候",Toast.LENGTH_SHORT).show();return;}busy=true;AlertDialog wait=new MGBAUi.Builder(a).setTitle("正在处理金手指").setMessage(action==6?"生成导出副本…":"检查代码并保留回退副本…").setCancelable(false).show();
        IO.execute(()->{String result=nativeAction(action,index,type,enabled,name,text);a.runOnUiThread(()->{busy=false;wait.dismiss();if(!a.isFinishing() && !a.isDestroyed())done.done(result);});});
    }
    private static void error(Activity a,String message,Runnable back){new MGBAUi.Builder(a).setTitle("金手指操作未完成").setMessage(message==null?"核心未返回有效结果":message).setPositiveButton("返回",(d,w)->back.run()).setOnCancelListener(d->back.run()).show();}
}
