package com.example.models

import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "favorite_songs")
data class FavoriteSongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val videoId: String,
    val bannerUrl: String,
    val timestamp: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val localContentUri: String = ""
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp,
        localContentUri = localContentUri
    )

    companion object {
        fun fromSong(song: Song) = FavoriteSongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            videoId = song.videoId,
            bannerUrl = song.bannerUrl,
            timestamp = song.timestamp,
            localContentUri = song.resolvedLocalUri
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
    val playCount: Int = 1,
    val localContentUri: String = ""
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp,
        localContentUri = localContentUri
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
    val addedAt: Long = System.currentTimeMillis(),
    val localContentUri: String = ""
) {
    fun toSong() = Song(
        id = songId,
        title = title,
        artist = artist,
        videoId = videoId,
        bannerUrl = bannerUrl,
        timestamp = timestamp,
        localContentUri = localContentUri
    )
}

@Entity(tableName = "spotify_playlists")
data class SpotifyPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val spotifyPlaylistId: Long = 0,
    val name: String,
    val spotifyUrl: String = "",
    val coverUrl: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "spotify_playlist_songs",
    primaryKeys = ["spotifyPlaylistId", "songId"]
)
data class SpotifyPlaylistSongEntity(
    val spotifyPlaylistId: Long,
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
    val downloadedAt: Long = System.currentTimeMillis(),
    val localContentUri: String = ""
) {
    fun toSong(): Song {
        val resolvedUri = if (localContentUri.isNotBlank()) {
            localContentUri
        } else if (localFilePath.isNotBlank()) {
            Uri.fromFile(File(localFilePath)).toString()
        } else {
            ""
        }
        return Song(
            id = id,
            title = title,
            artist = artist,
            videoId = videoId,
            bannerUrl = bannerUrl,
            timestamp = timestamp,
            localContentUri = resolvedUri
        )
    }
}

@Dao
interface MusicLibraryDao {
    @Query("SELECT * FROM favorite_songs ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteSongEntity)

    @Query("DELETE FROM favorite_songs WHERE id = :songId")
    suspend fun removeFavorite(songId: String)

    @Query("SELECT * FROM session_history ORDER BY playedAt DESC LIMIT 50")
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

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteAllSongsInPlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun getSongsInPlaylist(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs ORDER BY addedAt ASC")
    fun getAllPlaylistSongs(): Flow<List<PlaylistSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Query("SELECT * FROM spotify_playlists ORDER BY createdAt DESC")
    fun getAllSpotifyPlaylists(): Flow<List<SpotifyPlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createSpotifyPlaylist(playlist: SpotifyPlaylistEntity): Long

    @Query("DELETE FROM spotify_playlists WHERE spotifyPlaylistId = :spotifyPlaylistId")
    suspend fun deleteSpotifyPlaylist(spotifyPlaylistId: Long)

    @Query("DELETE FROM spotify_playlist_songs WHERE spotifyPlaylistId = :spotifyPlaylistId")
    suspend fun deleteAllSongsInSpotifyPlaylist(spotifyPlaylistId: Long)

    @Query("SELECT * FROM spotify_playlist_songs ORDER BY addedAt ASC")
    fun getAllSpotifyPlaylistSongs(): Flow<List<SpotifyPlaylistSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToSpotifyPlaylist(entry: SpotifyPlaylistSongEntity)

    @Query("DELETE FROM spotify_playlist_songs WHERE spotifyPlaylistId = :spotifyPlaylistId AND songId = :songId")
    suspend fun removeSongFromSpotifyPlaylist(spotifyPlaylistId: Long, songId: String)

    @Query("UPDATE spotify_playlist_songs SET videoId = :videoId, bannerUrl = :bannerUrl WHERE songId = :songId")
    suspend fun updateSpotifyPlaylistSongVideoId(songId: String, videoId: String, bannerUrl: String)

    @Query("UPDATE spotify_playlist_songs SET bannerUrl = :bannerUrl WHERE songId = :songId")
    suspend fun updateSpotifyPlaylistSongBannerUrl(songId: String, bannerUrl: String)

    @Query("UPDATE spotify_playlists SET coverUrl = :coverUrl WHERE spotifyPlaylistId = :spotifyPlaylistId")
    suspend fun updateSpotifyPlaylistCoverUrl(spotifyPlaylistId: Long, coverUrl: String)

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
        SpotifyPlaylistEntity::class,
        SpotifyPlaylistSongEntity::class,
        DownloadedSongEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AKMusicDatabase : RoomDatabase() {
    abstract fun musicLibraryDao(): MusicLibraryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_songs ADD COLUMN localContentUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE session_history ADD COLUMN localContentUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE playlist_songs ADD COLUMN localContentUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloaded_songs ADD COLUMN localContentUri TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spotify_playlists` (" +
                        "`spotifyPlaylistId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`spotifyUrl` TEXT NOT NULL DEFAULT '', " +
                        "`coverUrl` TEXT NOT NULL DEFAULT '', " +
                        "`createdAt` INTEGER NOT NULL" +
                        ")"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spotify_playlist_songs` (" +
                        "`spotifyPlaylistId` INTEGER NOT NULL, " +
                        "`songId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`videoId` TEXT NOT NULL, " +
                        "`bannerUrl` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`spotifyPlaylistId`, `songId`)" +
                        ")"
                )
            }
        }
    }
}
