package com.erpnext.gpstracker;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class LocationQueue extends SQLiteOpenHelper {
    static final class Item {
        final long id;
        final String payload, method;
        Item(long id, String payload, String method) { this.id=id; this.payload=payload; this.method=method; }
    }

    LocationQueue(Context context) { super(context,"bluecore_locations.db",null,2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL, method TEXT NOT NULL DEFAULT 'gps_tracker.api.location', created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)");
        db.execSQL("CREATE INDEX pending_locations_created ON pending_locations(created_at, id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { if(oldVersion<2)db.execSQL("ALTER TABLE pending_locations ADD COLUMN method TEXT NOT NULL DEFAULT 'gps_tracker.api.location'"); }

    synchronized long enqueue(String payload,long createdAt) {
        return enqueue(payload,createdAt,"gps_tracker.api.location");
    }
    synchronized long enqueue(String payload,long createdAt,String method) {
        android.content.ContentValues values=new android.content.ContentValues();
        values.put("payload",payload); values.put("created_at",createdAt); values.put("method",method);
        return getWritableDatabase().insertOrThrow("pending_locations",null,values);
    }
    synchronized Item oldest() {
        try(Cursor cursor=getReadableDatabase().query("pending_locations",new String[]{"id","payload","method"},null,null,null,null,"created_at ASC, id ASC","1")) {
            return cursor.moveToFirst()?new Item(cursor.getLong(0),cursor.getString(1),cursor.getString(2)):null;
        }
    }
    synchronized void remove(long id) { getWritableDatabase().delete("pending_locations","id=?",new String[]{String.valueOf(id)}); }
    synchronized void failed(long id,String error) {
        getWritableDatabase().execSQL("UPDATE pending_locations SET attempts=attempts+1,last_error=? WHERE id=?",new Object[]{error,id});
    }
    synchronized int count() {
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending_locations",null)) { return cursor.moveToFirst()?cursor.getInt(0):0; }
    }
}
