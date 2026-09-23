package com.jianbox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Built-in preview for common downloaded files; avoids browser file-service handoff. */
public class InternalFileViewerActivity extends Activity {
    static final String EXTRA_PATH="path",EXTRA_MIME="mime",EXTRA_NAME="name";
    @Override public void onCreate(Bundle state){super.onCreate(state);JianData data=new JianData(this);Ui.applyTheme(data);if(UpdateGuard.enforce(this))return;String path=getIntent().getStringExtra(EXTRA_PATH),mime=getIntent().getStringExtra(EXTRA_MIME),name=getIntent().getStringExtra(EXTRA_NAME);File file=path==null?null:new File(path);LinearLayout root=Ui.vertical(this);root.setBackground(Ui.bg(Ui.GREEN_PALE,0,this));LinearLayout head=Ui.horizontal(this);head.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,8));TextView back=Ui.text(this,"‹",28,Ui.TEXT,false);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,Ui.lp(Ui.dp(this,38),Ui.dp(this,42)));LinearLayout copy=Ui.vertical(this);TextView title=Ui.text(this,name==null?"文件预览":name,16,Ui.TEXT,true);title.setSingleLine(true);copy.addView(title);copy.addView(Ui.text(this,mime==null?"未知类型":mime,9,Ui.MUTED,false));head.addView(copy,Ui.weight(1));root.addView(head);if(file==null||!file.exists()){TextView missing=Ui.text(this,"文件不存在或已被清理",13,Ui.MUTED,false);missing.setGravity(Gravity.CENTER);root.addView(missing,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);return;}boolean apk=(mime!=null&&mime.contains("android.package-archive"))||path.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");if((mime!=null&&mime.startsWith("image/"))||path.matches("(?i).*\\.(png|jpg|jpeg|gif|webp|bmp)$")){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setAdjustViewBounds(true);image.setImageBitmap(BitmapFactory.decodeFile(path));root.addView(image,new LinearLayout.LayoutParams(-1,0,1));}else{ScrollView scroll=new ScrollView(this);TextView text=Ui.text(this,preview(file,mime),11,Ui.TEXT,false);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,24));scroll.addView(text,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}if(apk){Button install=Ui.button(this,"打开系统安装确认",true);install.setOnClickListener(v->{try{Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(LocalFileProvider.uri(this,file),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}catch(Exception e){new android.app.AlertDialog.Builder(this).setTitle("无法安装").setMessage("请在系统设置中允许简盒安装未知应用后重试。").setPositiveButton("知道了",null).show();}});root.addView(install,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));Ui.margin(install,12,4,12,0,this);}TextView pathView=Ui.text(this,"本地位置："+file.getAbsolutePath()+" · "+format(file.length()),9,Ui.MUTED,false);pathView.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,10));root.addView(pathView);setContentView(root);}
    @Override protected void onResume(){super.onResume();UpdateGuard.resume(this);}
    private String preview(File f,String mime){if((mime!=null&&(mime.startsWith("text/")||mime.contains("json")||mime.contains("xml")||mime.contains("javascript")))||f.getName().matches("(?i).*\\.(txt|md|html?|css|js|json|xml|csv|log)$")){try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(f));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1&&total<2*1024*1024){out.write(b,0,n);total+=n;}return new String(out.toByteArray(),StandardCharsets.UTF_8)+(f.length()>2*1024*1024?"\n\n[仅预览前 2 MB]":"");}catch(Exception e){return "无法读取文件："+e.getMessage();}}return "简盒已保存此文件，但当前版本暂不支持直接预览该格式。\n\n文件名："+f.getName()+"\n大小："+format(f.length())+"\n\n文件仍保存在简盒目录中，可在下载管理里查看完整路径。";}
    private static String format(long v){if(v<1024)return v+" B";if(v<1024*1024)return String.format(java.util.Locale.ROOT,"%.1f KB",v/1024f);return String.format(java.util.Locale.ROOT,"%.1f MB",v/1024f/1024f);}
}
