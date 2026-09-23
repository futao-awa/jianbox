package com.jianbox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-owned downloader with persistence, retry and HTTP Range resume. */
public class BrowserDownloadService extends Service {
    static final String EXTRA_ID="download_id"; private static final String CHANNEL="jianbox_downloads";
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    @Override public void onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"简盒下载",NotificationManager.IMPORTANCE_LOW);c.setDescription("应用内下载进度");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){String id=intent==null?null:intent.getStringExtra(EXTRA_ID);if(id==null)return START_NOT_STICKY;startForeground(notificationId(id),notification("正在准备下载",0,true));pool.execute(()->download(id));return START_NOT_STICKY;}
    private void download(String id){DownloadStore.Record r=DownloadStore.get(this,id);if(r==null){stopSelf();return;}File target=targetFile(r);r.path=target.getAbsolutePath();DownloadStore.put(this,r);Exception last=null;for(int attempt=0;attempt<3;attempt++){try{transfer(r,target);last=null;break;}catch(Exception e){last=e;r.retries=attempt+1;r.error=e.getMessage()==null?"网络连接中断":e.getMessage();r.status=DownloadStore.QUEUED;DownloadStore.put(this,r);try{Thread.sleep(800L*(attempt+1));}catch(InterruptedException ignored){break;}}}if(last!=null){r.status=DownloadStore.FAILED;r.error="下载失败："+r.error;r.updatedAt=System.currentTimeMillis();DownloadStore.put(this,r);notifyDone(r,false);}stopSelf();}
    private File targetFile(DownloadStore.Record r){JianData data=new JianData(this);File dir="内部私密目录".equals(data.downloadLocation())?new File(getFilesDir(),"downloads"):getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(dir==null)dir=new File(getFilesDir(),"downloads");dir.mkdirs();String safe=r.name.replaceAll("[\\\\/:*?\"<>|]","_").trim();if(safe.isEmpty())safe="download.bin";File f=new File(dir,safe);if(!f.exists()||f.getAbsolutePath().equals(r.path))return f;int dot=safe.lastIndexOf('.');String base=dot>0?safe.substring(0,dot):safe,ext=dot>0?safe.substring(dot):"";for(int i=1;i<1000;i++){f=new File(dir,base+" ("+i+")"+ext);if(!f.exists())return f;}return new File(dir,System.currentTimeMillis()+"_"+safe);}
    private void transfer(DownloadStore.Record r,File target)throws Exception{long existing=target.exists()?target.length():0;HttpURLConnection c=(HttpURLConnection)new URL(r.url).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(true);if(!r.userAgent.isEmpty())c.setRequestProperty("User-Agent",r.userAgent);if(existing>0)c.setRequestProperty("Range","bytes="+existing+"-");int code=c.getResponseCode();if(code<200||code>=400)throw new java.io.IOException("服务器返回 HTTP "+code);boolean append=existing>0&&code==206;if(!append)existing=0;long length=c.getContentLengthLong();r.total=length>0?existing+length:-1;r.done=existing;r.status=DownloadStore.RUNNING;r.error="";DownloadStore.put(this,r);long started=System.currentTimeMillis(),lastAt=started,lastBytes=existing;byte[] buffer=new byte[64*1024];try(BufferedInputStream in=new BufferedInputStream(c.getInputStream(),64*1024);BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(target,append),64*1024)){int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);r.done+=n;long now=System.currentTimeMillis();if(now-lastAt>=500){r.speed=(r.done-lastBytes)*1000/Math.max(1,now-lastAt);r.updatedAt=now;DownloadStore.put(this,r);updateNotification(r);lastAt=now;lastBytes=r.done;}}}finally{c.disconnect();}r.status=DownloadStore.COMPLETE;r.total=r.total>0?r.total:r.done;r.speed=0;r.updatedAt=System.currentTimeMillis();DownloadStore.put(this,r);notifyDone(r,true);}
    private int notificationId(String id){return 4100+Math.abs(id.hashCode()%2000);}
    private Notification notification(String text,int progress,boolean ongoing){Intent open=new Intent(this,DownloadActivity.class);PendingIntent pi=PendingIntent.getActivity(this,2,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("简盒下载").setContentText(text).setContentIntent(pi).setOngoing(ongoing);if(ongoing)b.setProgress(100,progress,progress<0);return b.build();}
    private void updateNotification(DownloadStore.Record r){int p=r.total>0?(int)Math.min(100,r.done*100/r.total):-1;getSystemService(NotificationManager.class).notify(notificationId(r.id),notification(r.name+" · "+(p<0?"下载中":p+"%"),p,true));}
    private void notifyDone(DownloadStore.Record r,boolean ok){getSystemService(NotificationManager.class).notify(notificationId(r.id),notification(ok?r.name+" · 下载完成":r.name+" · 下载失败",100,false));}
    @Override public void onDestroy(){pool.shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
