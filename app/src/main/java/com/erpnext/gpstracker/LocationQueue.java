package com.erpnext.gpstracker;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

final class LocationQueue extends SQLiteOpenHelper {
    static final class Item {
        final long id;
        final String payload, method;
        Item(long id, String payload, String method) { this.id=id; this.payload=payload; this.method=method; }
    }

    LocationQueue(Context context) { super(context,"bluecore_locations.db",null,3); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL, method TEXT NOT NULL DEFAULT 'gps_tracker.api.location', created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)");
        db.execSQL("CREATE INDEX pending_locations_created ON pending_locations(created_at, id)");
        db.execSQL("CREATE TABLE cached_trips (trip_name TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2)db.execSQL("ALTER TABLE pending_locations ADD COLUMN method TEXT NOT NULL DEFAULT 'gps_tracker.api.location'");
        if(oldVersion<3)db.execSQL("CREATE TABLE cached_trips (trip_name TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    }

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
    synchronized void cacheTrip(String tripName,String payload) {
        android.content.ContentValues values=new android.content.ContentValues();values.put("trip_name",tripName);values.put("payload",payload);values.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("cached_trips",null,values,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized String cachedTrip(String tripName) {
        try(Cursor cursor=getReadableDatabase().query("cached_trips",new String[]{"payload"},"trip_name=?",new String[]{tripName},null,null,null,"1")) { return cursor.moveToFirst()?cursor.getString(0):null; }
    }
    synchronized Set<String> pendingCompletedStops(String tripName) {
        HashSet<String> stops=new HashSet<>();
        try(Cursor cursor=getReadableDatabase().query("pending_locations",new String[]{"payload"},"method=?",new String[]{"gps_tracker.api.delivery_event"},null,null,"created_at ASC")) {
            while(cursor.moveToNext())try{JSONObject event=new JSONObject(cursor.getString(0));if(tripName.equals(event.optString("delivery_trip"))&&"Delivery Completed".equals(event.optString("event_type")))stops.add(event.optString("delivery_stop"));}catch(Exception ignored){}
        }
        return stops;
    }
}
