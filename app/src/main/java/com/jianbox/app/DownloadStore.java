package com.jianbox.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Persistent app-owned download queue inspired by Fetch's task model. */
final class DownloadStore {
    static final int QUEUED=0, RUNNING=1, PAUSED=2, COMPLETE=3, FAILED=4, CANCELED=5;
    private static final String PREF="jianbox_downloads_v2", KEY="records";
    static final class Record {
        String id=UUID.randomUUID().toString(), url="", name="", mime="application/octet-stream", path="", userAgent="", error="";
        int status=QUEUED, retries; long done,total=-1,createdAt=System.currentTimeMillis(),updatedAt=createdAt,speed;
        JSONObject json(){try{return new JSONObject().put("id",id).put("url",url).put("name",name).put("mime",mime).put("path",path).put("userAgent",userAgent).put("error",error).put("status",status).put("retries",retries).put("done",done).put("total",total).put("createdAt",createdAt).put("updatedAt",updatedAt).put("speed",speed);}catch(Exception e){return new JSONObject();}}
        static Record from(JSONObject o){Record r=new Record();r.id=o.optString("id",r.id);r.url=o.optString("url");r.name=o.optString("name");r.mime=o.optString("mime",r.mime);r.path=o.optString("path");r.userAgent=o.optString("userAgent");r.error=o.optString("error");r.status=o.optInt("status");r.retries=o.optInt("retries");r.done=o.optLong("done");r.total=o.optLong("total",-1);r.createdAt=o.optLong("createdAt",r.createdAt);r.updatedAt=o.optLong("updatedAt",r.updatedAt);r.speed=o.optLong("speed");return r;}
    }
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    static synchronized List<Record> all(Context c){List<Record> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs(c).getString(KEY,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)out.add(Record.from(o));}}catch(Exception ignored){}Collections.sort(out,(a,b)->Long.compare(b.createdAt,a.createdAt));return out;}
    static synchronized Record get(Context c,String id){for(Record r:all(c))if(r.id.equals(id))return r;return null;}
    static synchronized void put(Context c,Record record){List<Record> list=all(c);boolean found=false;for(int i=0;i<list.size();i++)if(list.get(i).id.equals(record.id)){list.set(i,record);found=true;break;}if(!found)list.add(0,record);save(c,list);}
    static synchronized void remove(Context c,String id,boolean deleteFile){List<Record> list=all(c);for(Record r:new ArrayList<>(list))if(r.id.equals(id)){if(deleteFile&&!r.path.isEmpty())try{new File(r.path).delete();}catch(Exception ignored){}list.remove(r);}save(c,list);}
    static synchronized void clearTerminal(Context c){List<Record> list=all(c);for(Record r:new ArrayList<>(list))if(r.status==COMPLETE||r.status==FAILED||r.status==CANCELED){if(!r.path.isEmpty())try{new File(r.path).delete();}catch(Exception ignored){}list.remove(r);}save(c,list);}
    private static void save(Context c,List<Record> list){JSONArray a=new JSONArray();for(Record r:list)a.put(r.json());prefs(c).edit().putString(KEY,a.toString()).commit();}
    private DownloadStore(){}
}
