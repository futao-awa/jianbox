package com.jianbox.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ToolboxActivity extends Activity {
    private EditText editor;
    private JianData data;
    @Override public void onCreate(Bundle state){super.onCreate(state);data=new JianData(this);Ui.applyTheme(data);if(UpdateGuard.enforce(this))return;getWindow().setStatusBarColor(Ui.GREEN_PALE);build();}
    @Override protected void onResume(){super.onResume();UpdateGuard.resume(this);}
    private void build(){
        ScrollView scroll=new ScrollView(this);if(Ui.GLASS)scroll.setBackground(ImageStore.background(this,data.appBackgroundPath(),0x44000000,0,0x33FFFFFF,100,0,data.appBackgroundPosition()));else scroll.setBackgroundColor(Ui.GREEN_PALE);LinearLayout root=Ui.vertical(this);root.setPadding(Ui.dp(this,13),Ui.dp(this,10),Ui.dp(this,13),Ui.dp(this,20));int contentWidth=Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,820));scroll.addView(root,new ScrollView.LayoutParams(contentWidth,-2,Gravity.CENTER_HORIZONTAL));
        LinearLayout head=Ui.horizontal(this);TextView back=Ui.text(this,"‹",26,Ui.TEXT,false);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,Ui.lp(Ui.dp(this,38),Ui.dp(this,42)));LinearLayout titles=Ui.vertical(this);titles.addView(Ui.label(this,"JIAN TOOLS · LOCAL FIRST"));titles.addView(Ui.text(this,"便捷工具",19,Ui.TEXT,true));head.addView(titles,Ui.weight(1));root.addView(head,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        TextView note=Ui.text(this,"文本工具完全在本机运行；文档与音频转换会在内置浏览器中打开所选服务，由你确认后再上传文件。",11,Ui.MUTED,false);note.setPadding(0,0,0,Ui.dp(this,12));root.addView(note);
        LinearLayout textCard=Ui.card(this);textCard.addView(Ui.text(this,"文本格式化",14,Ui.TEXT,true));editor=new EditText(this);editor.setGravity(Gravity.TOP);editor.setTextSize(12);editor.setTextColor(Ui.TEXT);editor.setHint("粘贴 JSON、URL 或普通文本…");editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);editor.setBackground(Ui.bordered(0xFFF7FAF7,Ui.BORDER,10,this));editor.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,8));textCard.addView(editor,new LinearLayout.LayoutParams(-1,Ui.dp(this,178)));
        String[] actions={"JSON 美化","JSON 压缩","清理空行","按行排序","URL 编码","URL 解码"};LinearLayout grid=Ui.vertical(this);
        grid.setPadding(0,Ui.dp(this,5),0,0);for(int i=0;i<actions.length;i+=2){LinearLayout row=Ui.horizontal(this);row.setPadding(0,Ui.dp(this,3),0,Ui.dp(this,3));for(int j=i;j<Math.min(i+2,actions.length);j++){String action=actions[j];Button b=Ui.button(this,action,j==0);b.setTextSize(11);b.setOnClickListener(v->format(action));row.addView(b,new LinearLayout.LayoutParams(0,Ui.dp(this,38),1));if(j==i)Ui.margin(b,0,0,6,0,this);}grid.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));}textCard.addView(grid);root.addView(textCard,new LinearLayout.LayoutParams(-1,-2));Ui.margin(textCard,0,0,0,9,this);
        LinearLayout convert=Ui.card(this);convert.addView(Ui.text(this,"格式转换",15,Ui.TEXT,true));convert.addView(converter("文档转换","PDF / Word / Markdown / TXT","https://www.pdf2docx.cn/","文"));convert.addView(converter("通用文件转换","文档、电子书、压缩包等","https://convertio.co/zh/","转"));convert.addView(converter("音频转换","MP3 / WAV / FLAC / M4A 等","https://audio-convert.com/cn/","音"));convert.addView(converter("图片处理","格式、压缩、拼接、裁剪","https://imagestool.com/zh_CN/","图"));root.addView(convert,new LinearLayout.LayoutParams(-1,-2));setContentView(scroll);
    }
    private View converter(String title,String sub,String url,String glyph){LinearLayout row=Ui.horizontal(this);row.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,6));TextView icon=Ui.icon(this,glyph,Ui.GREEN);row.addView(icon,Ui.lp(Ui.dp(this,36),Ui.dp(this,36)));LinearLayout copy=Ui.vertical(this);copy.setPadding(Ui.dp(this,9),0,0,0);copy.addView(Ui.text(this,title,12,Ui.TEXT,true));copy.addView(Ui.text(this,sub,9,Ui.MUTED,false));row.addView(copy,Ui.weight(1));TextView go=Ui.text(this,"›",19,Ui.GREEN,false);row.addView(go,Ui.lp(Ui.dp(this,24),-1));row.setOnClickListener(v->BrowserActivity.open(this,url));return row;}
    private void format(String action){String value=editor.getText().toString();try{if("JSON 美化".equals(action))editor.setText(value.trim().startsWith("[")?new JSONArray(value).toString(2):new JSONObject(value).toString(2));else if("JSON 压缩".equals(action))editor.setText(value.trim().startsWith("[")?new JSONArray(value).toString():new JSONObject(value).toString());else if("清理空行".equals(action))editor.setText(value.replaceAll("(?m)^[ \\t]*\\r?\\n",""));else if("按行排序".equals(action)){String[] lines=value.split("\\R");Arrays.sort(lines,String.CASE_INSENSITIVE_ORDER);editor.setText(String.join("\n",lines));}else if("URL 编码".equals(action))editor.setText(URLEncoder.encode(value,StandardCharsets.UTF_8.name()));else editor.setText(URLDecoder.decode(value,StandardCharsets.UTF_8.name()));}catch(Exception e){Toast.makeText(this,"内容格式不正确："+e.getMessage(),Toast.LENGTH_SHORT).show();}}
}
