package com.example.clipkeys

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Group(val id: Long, val name: String)
data class Clip(val id: Long, val text: String, val groupId: Long, val pinned: Boolean)

/** Group id 0 is the built-in "Recent" bucket where every new copy lands. */
class ClipStore(ctx: Context) : SQLiteOpenHelper(ctx, "clips.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clip_groups(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, " +
                "group_id INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun groups(): List<Group> {
        val out = mutableListOf<Group>()
        readableDatabase.rawQuery("SELECT id, name FROM clip_groups ORDER BY id", null).use {
            while (it.moveToNext()) out.add(Group(it.getLong(0), it.getString(1)))
        }
        return out
    }

    fun addGroup(name: String): Long =
        writableDatabase.insert("clip_groups", null, ContentValues().apply { put("name", name) })

    fun renameGroup(id: Long, name: String) {
        writableDatabase.update("clip_groups", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    /** Deleting a group sends its clips back to Recent. */
    fun deleteGroup(id: Long) {
        writableDatabase.execSQL("UPDATE clips SET group_id=0 WHERE group_id=?", arrayOf(id))
        writableDatabase.delete("clip_groups", "id=?", arrayOf(id.toString()))
    }

    fun addClip(text: String, groupId: Long = 0) {
        val db = writableDatabase
        db.delete("clips", "text=? AND group_id=?", arrayOf(text, groupId.toString()))
        db.insert("clips", null, ContentValues().apply {
            put("text", text); put("group_id", groupId); put("pinned", 0); put("ts", System.currentTimeMillis())
        })
        // Keep Recent from growing forever (pinned clips are never pruned).
        db.execSQL(
            "DELETE FROM clips WHERE group_id=0 AND pinned=0 AND id NOT IN " +
                "(SELECT id FROM clips WHERE group_id=0 AND pinned=0 ORDER BY ts DESC LIMIT 100)"
        )
    }

    fun clips(groupId: Long): List<Clip> {
        val out = mutableListOf<Clip>()
        readableDatabase.rawQuery(
            "SELECT id, text, group_id, pinned FROM clips WHERE group_id=? ORDER BY pinned DESC, ts DESC",
            arrayOf(groupId.toString())
        ).use {
            while (it.moveToNext()) out.add(Clip(it.getLong(0), it.getString(1), it.getLong(2), it.getInt(3) == 1))
        }
        return out
    }

    fun move(clipId: Long, groupId: Long) {
        writableDatabase.execSQL("UPDATE clips SET group_id=? WHERE id=?", arrayOf(groupId, clipId))
    }

    fun setPinned(clipId: Long, pinned: Boolean) {
        writableDatabase.execSQL("UPDATE clips SET pinned=? WHERE id=?", arrayOf(if (pinned) 1 else 0, clipId))
    }

    fun delete(clipId: Long) {
        writableDatabase.delete("clips", "id=?", arrayOf(clipId.toString()))
    }
}
