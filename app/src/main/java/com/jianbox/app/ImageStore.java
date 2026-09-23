package com.jianbox.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class ImageStore {
    static String save(Context context, Uri uri, String key, int maxSide, boolean transparent) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
        Bitmap source; try (InputStream in = context.getContentResolver().openInputStream(uri)) { source = BitmapFactory.decodeStream(in, null, options); }
        if (source == null) throw new IllegalArgumentException("无法读取图片");
        float scale = Math.min(1f, maxSide / (float)Math.max(source.getWidth(), source.getHeight()));
        Bitmap output = scale < 1f ? Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth()*scale)), Math.max(1, Math.round(source.getHeight()*scale)), true) : source;
        File dir = new File(context.getFilesDir(), "custom_images"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建图片目录");
        File file = new File(dir, key + (transparent ? ".png" : ".jpg"));
        try (FileOutputStream out = new FileOutputStream(file)) { output.compress(transparent ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, transparent ? 100 : 88, out); }
        if (output != source) output.recycle(); source.recycle(); return file.getAbsolutePath();
    }

    static Bitmap load(String path) {
        if (path == null || path.isEmpty()) return null;
        try { return BitmapFactory.decodeFile(path); } catch (Exception e) { return null; }
    }

    static Drawable background(Context context, String path, int overlayColor, float radiusDp, int borderColor) {
        return background(context, path, overlayColor, radiusDp, borderColor, 100, 0, 50);
    }

    static Drawable background(Context context, String path, int overlayColor, float radiusDp, int borderColor,
                               int imageAlpha, int blurDp, int position) {
        Bitmap bitmap=load(path);
        if(bitmap==null&&Ui.GLASS){try{bitmap=BitmapFactory.decodeResource(context.getResources(),R.drawable.default_glass_background);}catch(Exception ignored){}}
        return new PhotoDrawable(bitmap, overlayColor, Ui.dp(context, radiusDp), borderColor, Ui.dp(context, 1),
                imageAlpha, blurDp, position);
    }

    static void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF target, Paint paint) {
        drawCenterCrop(canvas, bitmap, target, paint, 50);
    }

    static void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF target, Paint paint, int position) {
        if (bitmap == null || bitmap.isRecycled()) return;
        float sourceRatio = bitmap.getWidth() / (float)bitmap.getHeight(), targetRatio = target.width() / target.height();
        Rect src = new Rect();
        if (sourceRatio > targetRatio) { int width = Math.round(bitmap.getHeight() * targetRatio); int left = (bitmap.getWidth()-width)/2; src.set(left,0,left+width,bitmap.getHeight()); }
        else { int height = Math.round(bitmap.getWidth()/targetRatio); int available = Math.max(0, bitmap.getHeight() - height); int top = Math.round(available * Math.max(0, Math.min(100, position)) / 100f); src.set(0,top,bitmap.getWidth(),top+height); }
        canvas.drawBitmap(bitmap, src, target, paint);
    }

    private static final class PhotoDrawable extends Drawable {
        private final Bitmap bitmap; private final int overlay; private final float radius; private final int border; private final float stroke;
        private final int imageAlpha; private final int position;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private int alpha = 255;
        PhotoDrawable(Bitmap b,int o,float r,int border,float stroke,int imageAlpha,int blurDp,int position){bitmap=soften(b,blurDp);if(b!=null&&bitmap!=b&&!b.isRecycled())b.recycle();overlay=o;radius=r;this.border=border;this.stroke=stroke;this.imageAlpha=Math.max(0,Math.min(100,imageAlpha));this.position=position;}
        @Override public void draw(Canvas c) {
            RectF target = new RectF(getBounds()); Path path = new Path(); path.addRoundRect(target,radius,radius,Path.Direction.CW); c.save(); c.clipPath(path);
            if(bitmap!=null) { paint.setAlpha(Math.round(alpha * imageAlpha / 100f)); ImageStore.drawCenterCrop(c,bitmap,target,paint,position); }
            paint.setColor(overlay); paint.setAlpha(Math.round(ColorAlpha(overlay)*alpha/255f)); c.drawRoundRect(target,radius,radius,paint); c.restore();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(border); paint.setAlpha(alpha); c.drawRoundRect(new RectF(target.left+stroke/2,target.top+stroke/2,target.right-stroke/2,target.bottom-stroke/2),radius,radius,paint); paint.setStyle(Paint.Style.FILL);
        }
        private int ColorAlpha(int color){return (color>>>24)&255;}
        private static Bitmap soften(Bitmap source,int amount){if(source==null||amount<=0)return source;int factor=Math.max(2,Math.min(12,2+amount/2));int w=Math.max(1,source.getWidth()/factor),h=Math.max(1,source.getHeight()/factor);Bitmap small=Bitmap.createScaledBitmap(source,w,h,true);Bitmap result=Bitmap.createScaledBitmap(small,source.getWidth(),source.getHeight(),true);if(small!=result&&!small.isRecycled())small.recycle();return result;}
        @Override public void setAlpha(int a){alpha=a;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private ImageStore() {}
}
