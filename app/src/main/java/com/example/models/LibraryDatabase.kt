package com.example.models

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorite_songs")
data class FavoriteSongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val videoId: String,
    val bannerUrl: String,
    val timestamp: Long,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp
    )

    companion object {
        fun fromSong(song: Song) = FavoriteSongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            videoId = song.videoId,
            bannerUrl = song.bannerUrl,
            timestamp = song.timestamp
        )
    }
}

@Entity(tableName = "session_history")
data class SessionHistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val videoId: String,
    val bannerUrl: String,
    val timestamp: Long,
    val playedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp
    )
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val title: String,
    val artist: String,
    val videoId: String,
    val bannerUrl: String,
    val timestamp: Long,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toSong() = Song(
        id = songId,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp
    )
}

@Entity(tableName = "downloaded_songs")
data class DownloadedSongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val videoId: String,
    val bannerUrl: String,
    val timestamp: Long,
    val localFilePath: String,
    val fileSizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp
    )
}

@Dao
interface MusicLibraryDao {
    @Query("SELECT * FROM favorite_songs ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteSongEntity)

    @Query("DELETE FROM favorite_songs WHERE id = :songId")
    suspend fun removeFavorite(songId: String)

    @Query("SELECT * FROM session_history ORDER BY playedAt DESC LIMIT 30")
    fun getRecentSessionHistory(): Flow<List<SessionHistoryEntity>>

    @Query("SELECT * FROM session_history WHERE id = :songId LIMIT 1")
    suspend fun getHistoryItem(songId: String): SessionHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryItem(item: SessionHistoryEntity)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    fun getSongsInPlaylist(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs ORDER BY addedAt DESC")
    fun getAllPlaylistSongs(): Flow<List<PlaylistSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Query("SELECT * FROM downloaded_songs ORDER BY downloadedAt DESC")
    fun getAllDownloadedSongs(): Flow<List<DownloadedSongEntity>>

    @Query("SELECT * FROM downloaded_songs WHERE id = :songId LIMIT 1")
    suspend fun getDownloadedSong(songId: String): DownloadedSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedSong(song: DownloadedSongEntity)

    @Query("DELETE FROM downloaded_songs WHERE id = :songId")
    suspend fun deleteDownloadedSong(songId: String)
}

@Database(
    entities = [
        FavoriteSongEntity::class,
        SessionHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        DownloadedSongEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AKMusicDatabase : RoomDatabase() {
    abstract fun musicLibraryDao(): MusicLibraryDao
}
