package com.jianbox.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider restricted to files owned by Jianbox. */
public class LocalFileProvider extends ContentProvider {
    static Uri uri(Context context,File file){return new Uri.Builder().scheme("content").authority(context.getPackageName()+".files").appendPath("download").appendPath(file.getAbsolutePath()).build();}
    private File resolve(Uri uri)throws FileNotFoundException{if(getContext()==null||uri.getPathSegments().size()<2)throw new FileNotFoundException();try{File file=new File(uri.getLastPathSegment()).getCanonicalFile();File internal=getContext().getFilesDir().getCanonicalFile(),external=getContext().getExternalFilesDir(null);boolean allowed=file.getPath().startsWith(internal.getPath()+File.separator)||(external!=null&&file.getPath().startsWith(external.getCanonicalPath()+File.separator));if(!allowed||!file.isFile())throw new FileNotFoundException();return file;}catch(Exception e){throw new FileNotFoundException("文件不可用");}}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){try{String name=resolve(uri).getName(),ext=MimeTypeMap.getFileExtensionFromUrl(name);String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext==null?"":ext.toLowerCase());return mime==null?"application/octet-stream":mime;}catch(Exception e){return "application/octet-stream";}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){try{File f=resolve(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(Exception e){return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("只允许读取");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
}
