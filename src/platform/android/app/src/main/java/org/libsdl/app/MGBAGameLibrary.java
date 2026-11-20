/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Content-addressed library. Existing games/sources are never overwritten. */
final class MGBAGameLibrary {
    static final String MANIFEST="game.properties";
    static final class Game {
        final File folder,rom; final String name,hash; final long opened;
        Game(File folder,Properties p)throws IOException {
            this.folder=folder;name=p.getProperty("name","未命名游戏");hash=p.getProperty("sha256","");
            if(!hash.matches("[0-9a-f]{64}"))throw new IOException("游戏目录信息损坏");
            rom=child(folder,p.getProperty("rom",""));
            if(!rom.isFile())throw new IOException("游戏本体缺失");
            long when;try{when=Long.parseLong(p.getProperty("opened","0"));}catch(Exception e){when=0;}opened=when;
        }
    }
    static final class Legacy {
        final File rom; final ArrayList<File> files; final long modified;
        Legacy(File rom,ArrayList<File> files){this.rom=rom;this.files=files;long time=0;for(File f:files)time=Math.max(time,f.lastModified());modified=time;}
        String label(){String n=rom.getName().replaceFirst("^7107-","");return n+" · "+files.size()+" 个进度／附属文件"+(modified>0?"\n文件最近修改："+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new Date(modified)):"");}
    }
    static final class Plan {
        final File root,source; final String name,hash; final Game existing; final ArrayList<Legacy> legacy;
        final Legacy preferred;
        Plan(File r,File s,String n,String h,Game e,ArrayList<Legacy> l,Legacy p){root=r;source=s;name=n;hash=h;existing=e;legacy=l;preferred=p;}
        boolean needsChoice(){return existing==null && preferred==null && legacy.size()>1;}
    }
    static File child(File parent,String name)throws IOException {
        if(name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))throw new IOException("无效的文件名");
        File f=new File(parent,name);
        if(!f.getCanonicalPath().startsWith(parent.getCanonicalPath()+File.separator))throw new IOException("文件超出游戏目录");
        return f;
    }
    static String digest(File file)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static String clean(String name){
        String s=name.replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]","_").trim().replaceFirst("^\\.+","");if(s.isEmpty())s="game.gba";
        int dot=s.lastIndexOf('.');String ext=dot>0 && s.length()-dot<=10?s.substring(dot):"";String base=ext.isEmpty()?s:s.substring(0,dot);
        while((base+ext).getBytes(StandardCharsets.UTF_8).length>200 && !base.isEmpty())base=base.substring(0,base.offsetByCodePoints(base.length(),-1));return base+ext;
    }
    static Properties properties(File file)throws IOException {
        Properties p=new Properties();try(Reader in=new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)){p.load(in);}return p;
    }
    static ArrayList<Game> list(File root)throws IOException {
        ArrayList<Game> result=new ArrayList<>();File[] dirs=root.listFiles();
        if(dirs==null){if(root.exists())throw new IOException("无法读取游戏库目录");return result;}
        for(File f:dirs)if(f.isDirectory() && !f.getName().startsWith(".") && new File(f,MANIFEST).isFile()){
            try{child(root,f.getName());result.add(new Game(f,properties(new File(f,MANIFEST))));}catch(IOException ignored){}
        }
        Collections.sort(result,(a,b)->Long.compare(b.opened,a.opened));return result;
    }
    static ArrayList<File> companions(File rom)throws IOException {
        String name=rom.getName(),base=name.substring(0,name.lastIndexOf('.')>=0?name.lastIndexOf('.'):name.length());
        ArrayList<File> result=new ArrayList<>();File[] files=rom.getParentFile().listFiles();
        if(files!=null)for(File f:files)if(f.isFile() && f.getName().startsWith(base+".")){
            String suffix=f.getName().substring(base.length()).toLowerCase(Locale.ROOT);
            if(suffix.matches("\\.(sav|ss[0-9]*|state[0-9x]*|cheats|cht|ips|ups|bps|png)")){child(rom.getParentFile(),f.getName());result.add(f);}
        }
        Collections.sort(result,(a,b)->a.getName().compareTo(b.getName()));return result;
    }
    static Plan plan(File root,File source,String name,File legacyDir,File preferred)throws Exception {
        if(!root.isDirectory() && !root.mkdirs())throw new IOException("无法创建统一游戏目录");
        String hash=digest(source);
        for(Game g:list(root))if(g.hash.equals(hash)){
            if(!digest(g.rom).equals(hash))throw new IOException("库内 ROM 已被修改；保留现有目录，请先检查文件");
            return new Plan(root,source,name,hash,g,new ArrayList<>(),null);
        }
        LinkedHashSet<File> candidates=new LinkedHashSet<>();
        if(preferred!=null && preferred.isFile())candidates.add(preferred.getCanonicalFile());
        if(source.getCanonicalFile().getParentFile().equals(legacyDir.getCanonicalFile()) && !source.getName().matches("^7107-\\d{8,}-.*"))candidates.add(source.getCanonicalFile());
        File[] old=legacyDir.listFiles();if(old!=null)for(File f:old)if(f.isFile() && !f.getName().matches("^7107-\\d{8,}-.*") && f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(gba|gbc|gb)"))candidates.add(f.getCanonicalFile());
        ArrayList<Legacy> matches=new ArrayList<>();Legacy chosen=null;
        for(File f:candidates){ArrayList<File> files=companions(f);if(f.length()!=source.length() || !digest(f).equals(hash))continue;
            Legacy l=new Legacy(f,files);matches.add(l);
            if(preferred!=null && f.equals(preferred.getCanonicalFile()))chosen=l;
            else if(chosen==null && f.equals(source.getCanonicalFile()))chosen=l;
        }
        if(chosen==null && matches.size()==1)chosen=matches.get(0);
        return new Plan(root,source,name,hash,null,matches,chosen);
    }
    static void copy(File source,File target)throws IOException {
        try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();
        }
    }
    private static void writeProperties(File target,Properties p)throws IOException {
        try(FileOutputStream out=new FileOutputStream(target);Writer w=new OutputStreamWriter(out,StandardCharsets.UTF_8)){p.store(w,"mGBA game library");w.flush();out.getFD().sync();}
    }
    static synchronized Game commit(Plan p,Legacy selected,File liveBattery,File liveState)throws Exception {
        if(p.existing!=null){touch(p.existing);return p.existing;}
        // Recheck after an earlier selection/another import finished.
        for(Game g:list(p.root))if(g.hash.equals(p.hash)){if(!digest(g.rom).equals(p.hash))throw new IOException("库内 ROM 校验失败");touch(g);return g;}
        String title=clean(p.name.replaceFirst("\\.[^.]+$",""));if(title.length()>42)title=title.substring(0,42);
        File dest=child(p.root,title+"-"+p.hash.substring(0,16));
        if(dest.exists())dest=child(p.root,title+"-"+p.hash);
        if(dest.exists())throw new IOException("游戏目录冲突，原文件已保留");
        File pending=child(p.root,".pending-"+UUID.randomUUID());if(!pending.mkdir())throw new IOException("无法准备游戏目录");
        // A failed transaction is retained for recovery; it is never listed as a game.
        String romName=selected==null?clean(p.name):selected.rom.getName();File rom=child(pending,romName);copy(p.source,rom);
        if(!digest(rom).equals(p.hash))throw new IOException("ROM 复制校验失败");
        if(selected!=null)for(File f:selected.files)copy(f,child(pending,f.getName()));
        File history=child(pending,"LegacyBackups");
        for(int i=0;i<p.legacy.size();++i){Legacy l=p.legacy.get(i);File folder=child(history,Integer.toString(i+1)+"-"+clean(l.rom.getName()));if(!folder.mkdirs())throw new IOException("无法保留旧进度副本");
            for(File f:l.files)copy(f,child(folder,f.getName()));
            Properties origin=new Properties();origin.setProperty("original",l.rom.getAbsolutePath());writeProperties(child(folder,"source.properties"),origin);
        }
        String base=romName.substring(0,romName.lastIndexOf('.')>=0?romName.lastIndexOf('.'):romName.length());
        if(liveBattery!=null && liveBattery.isFile())copy(liveBattery,child(pending,base+".sav"));
        if(liveState!=null && liveState.isFile()){File backups=child(pending,"Backups");if(!backups.isDirectory() && !backups.mkdirs())throw new IOException("无法保留当前快照");copy(liveState,child(backups,"before-library.ss"));}
        Properties props=new Properties();props.setProperty("name",p.name);props.setProperty("sha256",p.hash);props.setProperty("rom",romName);props.setProperty("opened",Long.toString(System.currentTimeMillis()));
        writeProperties(child(pending,MANIFEST),props);
        if(!pending.renameTo(dest))throw new IOException("游戏目录提交失败，准备副本已保留");
        return new Game(dest,props);
    }
    static void touch(Game g)throws IOException {
        Properties p=properties(new File(g.folder,MANIFEST));p.setProperty("opened",Long.toString(System.currentTimeMillis()));
        File tmp=child(g.folder,".info-"+UUID.randomUUID());writeProperties(tmp,p);
        if(!tmp.renameTo(new File(g.folder,MANIFEST)))throw new IOException("无法更新游戏库信息");
    }
    static File archive(File rom,File source,String name,String kind)throws IOException {
        File parent=rom.getParentFile();if(!new File(parent,MANIFEST).isFile())return source;
        File dir=child(parent,kind);if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("无法创建游戏文件夹");
        File f=child(dir,UUID.randomUUID().toString().substring(0,8)+"-"+clean(name));copy(source,f);return f;
    }
}
