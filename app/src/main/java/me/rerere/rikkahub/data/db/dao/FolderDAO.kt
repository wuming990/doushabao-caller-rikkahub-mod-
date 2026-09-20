package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.FolderEntity

@Dao
interface FolderDAO {
    @Query("SELECT * FROM conversation_folder WHERE assistant_id = :assistantId ORDER BY sort_index ASC, create_at ASC")
    fun getFoldersOfAssistant(assistantId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM conversation_folder WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    /**
     * v304：整批写回文件夹（拖动排序用）。Room 会把一次 @Update 的列表包在一个事务里，
     * 中途失败不会写出一半排序。
     */
    @Update
    suspend fun updateAll(folders: List<FolderEntity>)

    /**
     * v304：新文件夹的排序号 —— 排在最后。
     * 用户手动排过序之后，库里是 0..n-1 的连续编号，新文件夹若还用 0 会插到最前面。
     */
    @Query("SELECT COALESCE(MAX(sort_index), -1) + 1 FROM conversation_folder WHERE assistant_id = :assistantId")
    suspend fun nextSortIndex(assistantId: String): Int

    @Query("UPDATE conversation_folder SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM conversation_folder WHERE id = :id")
    suspend fun deleteById(id: String)
}
