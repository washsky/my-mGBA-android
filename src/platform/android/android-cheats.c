/* SPDX-License-Identifier: MPL-2.0 */
#include "android-cheats.h"
#include "android-save.h"
#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/directories.h>
#include <mgba/internal/gba/cheats.h>
#include <mgba/internal/gb/cheats.h>
#include <mgba-util/vfs.h>
#include <mgba-util/string.h>
#include <SDL.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define MAX_GROUPS 128
#define MAX_TEXT (256 * 1024)

static struct mCheatDevice* create(struct mCore* core) {
    struct mCheatDevice* d=core->platform(core) == mPLATFORM_GBA ? GBACheatDeviceCreate() : GBCheatDeviceCreate();
    if(d)d->p=NULL;return d;
}
static void jsonString(struct VFile* vf, const char* text) {
    vf->write(vf,"\"",1);
    for (const unsigned char* p=(const unsigned char*)(text?text:""); *p; ++p) {
        if (*p=='"' || *p=='\\') { char b[2]={'\\',*p}; vf->write(vf,b,2); }
        else if (*p<32) { char b[7]; snprintf(b,sizeof(b),"\\u%04x",*p); vf->write(vf,b,6); }
        else vf->write(vf,p,1);
    }
    vf->write(vf,"\"",1);
}
char* mAndroidCheatList(struct mCore* core) {
    struct mCheatDevice* d=core->cheatDevice(core); struct VFile* vf=VFileMemChunk(NULL,0);
    if (!d || !vf) { if(vf)vf->close(vf); return NULL; }
    vf->write(vf,"[",1);
    for (size_t i=0;i<mCheatSetsSize(&d->cheats);++i) {
        struct mCheatSet* s=*mCheatSetsGetPointer(&d->cheats,i);
        if(i)vf->write(vf,",",1);
        char b[128];int n=snprintf(b,sizeof(b),"{\"id\":%zu,\"enabled\":%s,\"name\":",i,s->enabled?"true":"false");vf->write(vf,b,n);jsonString(vf,s->name);
        vf->write(vf,",\"code\":",8);
        struct VFile* lines=VFileMemChunk(NULL,0);
        for(size_t j=0;j<StringListSize(&s->lines);++j){const char* l=*StringListGetPointer(&s->lines,j);if(j)lines->write(lines,"\n",1);lines->write(lines,l,strlen(l));}
        size_t size=lines->size(lines);char* text=calloc(1,size+1);if(text){lines->seek(lines,0,SEEK_SET);lines->read(lines,text,size);jsonString(vf,text);free(text);}else jsonString(vf,"");lines->close(lines);vf->write(vf,"}",1);
    }
    vf->write(vf,"]",1);size_t size=vf->size(vf);char* result=calloc(1,size+1);
    if(result){vf->seek(vf,0,SEEK_SET);vf->read(vf,result,size);}vf->close(vf);return result;
}
static struct mCheatDevice* clone(struct mCore* core,struct mCheatDevice* source) {
    struct mCheatDevice* d=create(core);struct VFile* vf=VFileMemChunk(NULL,0);
    if(!d || !vf){if(d)mCheatDeviceDestroy(d);if(vf)vf->close(vf);return NULL;}
    bool ok=mCheatSaveFile(source,vf);if(ok && vf->size(vf)>0){vf->seek(vf,0,SEEK_SET);ok=mCheatParseFile(d,vf);}vf->close(vf);
    if(!ok){mCheatDeviceDestroy(d);return NULL;}return d;
}
static bool complete(struct mCore* core,struct mCheatSet* s) {
    if(!StringListSize(&s->lines))return false;
    if(core->platform(core)==mPLATFORM_GBA){struct GBACheatSet* g=(struct GBACheatSet*)s;return g->incompleteCheat==COMPLETE && !g->incompletePatch && !g->remainingAddresses;}
    return true;
}
static bool syntax(const char* text) {
    for(const char* p=text;*p;++p)if(!isxdigit((unsigned char)*p) && *p!=' ' && *p!='\t' && *p!='-' && *p!=':')return false;
    return true;
}
static bool parseManual(struct mCore* core,struct mCheatDevice* d,int index,int type,bool enabled,const char* name,const char* code,char* error,size_t cap) {
    if(!code || !*code || strlen(code)>32768 || !name || !*name || strlen(name)>240 || strpbrk(name,"\r\n")){snprintf(error,cap,"请输入名称和代码（名称不能换行，代码最多 32 KiB）");return false;}
    if(type< -1 || type>(core->platform(core)==mPLATFORM_GBA?4:3)){snprintf(error,cap,"代码格式无效");return false;}
    struct mCheatSet* s=d->createSet(d,name);
    if(index>0)s->copyProperties(s,*mCheatSetsGetPointer(&d->cheats,index-1));
    if(type==-1 && index>=0){struct StringList directives;StringListInit(&directives,4);(*mCheatSetsGetPointer(&d->cheats,index))->dumpDirectives(*mCheatSetsGetPointer(&d->cheats,index),&directives);s->parseDirectives(s,&directives);for(size_t i=0;i<StringListSize(&directives);++i)free(*StringListGetPointer(&directives,i));StringListDeinit(&directives);}
    char* input=strdup(code);char* save=NULL;unsigned line=0,valid=0;bool ok=true;
    for(char* p=strtok_r(input,"\n",&save);p;p=strtok_r(NULL,"\n",&save)){
        ++line;while(isspace((unsigned char)*p))++p;rtrim(p);if(!*p || *p=='#' || *p==';')continue;
        if(strlen(p)>500 || !syntax(p) || !mCheatAddLine(s,p,type<0?0:type)){snprintf(error,cap,"第 %u 行无法识别，请核对代码格式和完整内容",line);ok=false;break;}
        if(++valid>512){snprintf(error,cap,"单组代码最多 512 行");ok=false;break;}
    }
    free(input);
    if(ok && !complete(core,s)){snprintf(error,cap,"代码为空或多行指令不完整，请补齐后再保存");ok=false;}
    if(!ok){mCheatSetDeinit(s);return false;}
    s->enabled=enabled;
    if(index<0)mCheatAddSet(d,s);else{struct mCheatSet* old=*mCheatSetsGetPointer(&d->cheats,index);*mCheatSetsGetPointer(&d->cheats,index)=s;mCheatSetDeinit(old);}
    return true;
}
static bool importFile(struct mCore* core,struct mCheatDevice* d,const char* path,bool replace,char* error,size_t cap) {
    struct VFile* vf=VFileOpen(path,O_RDONLY);ssize_t size=vf?vf->size(vf):0;
    if(!vf || size<=0 || size>MAX_TEXT){if(vf)vf->close(vf);snprintf(error,cap,"文件不可读、为空或超过 256 KiB");return false;}
    char* text=calloc(1,size+1);bool ok=text && vf->read(vf,text,size)==size;vf->close(vf);
    if(!ok){free(text);snprintf(error,cap,"读取金手指文件失败");return false;}
    if(memchr(text,0,size)){free(text);snprintf(error,cap,"金手指文件必须是文本，不能包含二进制数据");return false;}
    char* start=text;if(size>=3 && !memcmp(text,"\xEF\xBB\xBF",3))start+=3;
    char* scan=strdup(start);char* state=NULL;unsigned expected=0,meaningful=0;bool libretro=false,resetOnly=true;
    struct VFile* normalized=VFileMemChunk(NULL,0);
    if(!scan || !normalized){free(scan);free(text);if(normalized)normalized->close(normalized);snprintf(error,cap,"内存不足");return false;}
    for(char* line=strtok_r(scan,"\n",&state);line;line=strtok_r(NULL,"\n",&state)){
        while(isspace((unsigned char)*line))++line;rtrim(line);if(!*line)continue;
        ++meaningful;if(strcasecmp(line,"!reset"))resetOnly=false;
        normalized->write(normalized,line,strlen(line));normalized->write(normalized,"\n",1);
        if(strlen(line)>500){ok=false;break;}
        if(!strncmp(line,"cheats = ",9))libretro=true;
        if(libretro){if(strstr(line,"_code") && strchr(line,'=')){char* q=strchr(line,'=')+1;while(isspace((unsigned char)*q))++q;if(*q=='"')++q;if(*q && *q!='"'){++expected;for(;*q && *q!='"';++q)if(*q=='+')++expected;}}continue;}
        if(*line=='#')continue;
        if(*line=='!'){const char* p=line+1;while(isspace((unsigned char)*p))++p;if(strcasecmp(p,"disabled") && strcasecmp(p,"reset") && strcmp(p,"GSAv1") && strcmp(p,"GSAv1 raw") && strcmp(p,"PARv3") && strcmp(p,"PARv3 raw"))ok=false;continue;}
        if(!syntax(line)){ok=false;break;}++expected;
    }
    free(scan);
    struct mCheatDevice* imported=create(core);vf=normalized;vf->seek(vf,0,SEEK_SET);
    if(ok)ok=imported && vf && mCheatParseFile(imported,vf);
    if(vf)vf->close(vf);free(text);
    unsigned actual=0;if(imported)for(size_t i=0;i<mCheatSetsSize(&imported->cheats);++i){struct mCheatSet* s=*mCheatSetsGetPointer(&imported->cheats,i);actual+=StringListSize(&s->lines);if(!complete(core,s) || StringListSize(&s->lines)>512)ok=false;}
    bool empty=resetOnly && meaningful>0;
    if(!ok || (!expected && !empty) || actual!=expected || !imported || (!mCheatSetsSize(&imported->cheats) && !empty) || mCheatSetsSize(&imported->cheats)+(replace?0:mCheatSetsSize(&d->cheats))>MAX_GROUPS){
        if(imported)mCheatDeviceDestroy(imported);snprintf(error,cap,"文件含无效／不完整代码或不支持的格式，原金手指未改变；支持 mGBA .cheats、Libretro .cht 和逐行代码文本");return false;
    }
    if(replace)mCheatDeviceClear(d);
    while(mCheatSetsSize(&imported->cheats)){struct mCheatSet* s=*mCheatSetsGetPointer(&imported->cheats,0);mCheatSetsShift(&imported->cheats,0,1);mCheatAddSet(d,s);}mCheatDeviceDestroy(imported);return true;
}
static bool serialize(struct mCheatDevice* d,char** output,size_t* size) {
    struct VFile* vf=VFileMemChunk(NULL,0);if(!vf)return false;
    bool ok=mCheatSaveFile(d,vf);if(vf->size(vf)==0)vf->write(vf,"!reset\n",7);
    *size=vf->size(vf);*output=malloc(*size);if(*output){vf->seek(vf,0,SEEK_SET);ok=ok && vf->read(vf,*output,*size)==(ssize_t)*size;}else ok=false;vf->close(vf);return ok;
}
static bool writeExclusive(const char* path,const void* bytes,size_t size) {
    int fd=open(path,O_WRONLY|O_CREAT|O_EXCL,0600);if(fd<0)return false;size_t offset=0;bool ok=true;
    while(offset<size){ssize_t n=write(fd,(const char*)bytes+offset,size-offset);if(n<0 && errno==EINTR)continue;if(n<=0){ok=false;break;}offset+=n;}
    if(ok)ok=fsync(fd)==0;if(close(fd))ok=false;if(!ok)unlink(path);return ok;
}
static bool saveCandidate(struct mCore* core,struct mCheatDevice* old,struct mCheatDevice* next,char* error,size_t cap) {
    char dir[4096]={0},path[4096]={0},temporary[4096]={0},backup[4096]={0};
    if(core->opts.cheatsPath && *core->opts.cheatsPath){char config[4096];mCoreConfigDirectory(config,sizeof(config));makeAbsolute(core->opts.cheatsPath,config,dir);}
    else{snprintf(dir,sizeof(dir),"%s",mAndroidGamePath());char* slash=strrchr(dir,'/');if(!slash)return false;*slash=0;}
    if(snprintf(path,sizeof(path),"%s/%s.cheats",dir,core->dirs.baseName)>=(int)sizeof(path))return false;
    char* oldBytes=NULL,*nextBytes=NULL;size_t oldSize=0,nextSize=0;bool ok=serialize(old,&oldBytes,&oldSize) && serialize(next,&nextBytes,&nextSize);
    if(nextSize>MAX_TEXT){free(oldBytes);free(nextBytes);snprintf(error,cap,"金手指总大小超过 256 KiB，未保存");return false;}
    static unsigned sequence;unsigned seq=++sequence;Uint64 stamp=SDL_GetTicks64();
    if(ok)ok=mAndroidGameDirectory(core,"Backups","mgba-backups",dir,sizeof(dir));
    snprintf(backup,sizeof(backup),"%s/cheats-before-%lld-%d-%llu-%u.cheats",dir,(long long)time(NULL),getpid(),(unsigned long long)stamp,seq);
    if(ok)ok=writeExclusive(backup,oldBytes,oldSize);
    // Also preserve game progress before any new code can affect memory.
    if(ok)ok=mAndroidBackupState(core,"before-cheats",backup,sizeof(backup));
    snprintf(temporary,sizeof(temporary),"%s.pending-%d-%llu-%u",path,getpid(),(unsigned long long)stamp,seq);
    if(ok)ok=writeExclusive(temporary,nextBytes,nextSize);
    if(ok && rename(temporary,path)){unlink(temporary);ok=false;}
    free(oldBytes);free(nextBytes);if(!ok)snprintf(error,cap,"保存或回退备份失败，金手指未改变；请检查保存路径和空间");return ok;
}
bool mAndroidCheatChange(struct mCore* core,int action,int index,int type,bool enabled,const char* name,const char* text,char* error,size_t cap) {
    struct mCheatDevice* live=core->cheatDevice(core);if(!live){snprintf(error,cap,"当前核心不支持金手指");return false;}
    size_t count=mCheatSetsSize(&live->cheats);
    if((action==1 && (index< -1 || index>=(int)count)) || ((action==2 || action==3) && (index<0 || index>=(int)count))){snprintf(error,cap,"列表已变化，请重新打开金手指页面");return false;}
    if(action==1 && index<0 && count>=MAX_GROUPS){snprintf(error,cap,"最多保存 128 组金手指");return false;}
    struct mCheatDevice* d=clone(core,live);if(!d){snprintf(error,cap,"无法准备金手指副本");return false;}bool ok=true;
    if(action==1)ok=parseManual(core,d,index,type,enabled,name,text,error,cap);
    else if(action==2)(*mCheatSetsGetPointer(&d->cheats,index))->enabled=enabled;
    else if(action==3){struct mCheatSet* s=*mCheatSetsGetPointer(&d->cheats,index);mCheatSetsShift(&d->cheats,index,1);mCheatSetDeinit(s);}
    else if(action==4)for(size_t i=0;i<mCheatSetsSize(&d->cheats);++i)(*mCheatSetsGetPointer(&d->cheats,i))->enabled=enabled;
    else if(action==5)ok=importFile(core,d,text,index==1,error,cap);
    else if(action==7)mCheatDeviceClear(d);
    else {snprintf(error,cap,"未知的金手指操作");ok=false;}
    if(ok)ok=saveCandidate(core,live,d,error,cap);
    if(ok){
        while(mCheatSetsSize(&live->cheats)){struct mCheatSet* s=*mCheatSetsGetPointer(&live->cheats,0);s->enabled=false;mCheatRefresh(live,s);mCheatRemoveSet(live,s);mCheatSetDeinit(s);}
        while(mCheatSetsSize(&d->cheats)){struct mCheatSet* s=*mCheatSetsGetPointer(&d->cheats,0);mCheatSetsShift(&d->cheats,0,1);mCheatAddSet(live,s);}
    }
    mCheatDeviceDestroy(d);return ok;
}
bool mAndroidCheatExport(struct mCore* core,char* output,size_t cap) {
    char dir[4096];if(!mAndroidGameDirectory(core,"Exports","mgba-exports",dir,sizeof(dir)))return false;
    static unsigned sequence;snprintf(output,cap,"%s/cheats-%lld-%d-%llu-%u.cheats",dir,(long long)time(NULL),getpid(),(unsigned long long)SDL_GetTicks64(),++sequence);
    char* bytes=NULL;size_t size=0;bool ok=serialize(core->cheatDevice(core),&bytes,&size) && writeExclusive(output,bytes,size);free(bytes);return ok;
}
