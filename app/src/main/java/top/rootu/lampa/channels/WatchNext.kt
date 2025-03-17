package top.rootu.lampa.channels

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9
import androidx.tvprovider.media.tv.TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST
import androidx.tvprovider.media.tv.TvContractCompat.buildWatchNextProgramUri
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.R
import top.rootu.lampa.content.LampaProvider
import top.rootu.lampa.helpers.Helpers
import top.rootu.lampa.helpers.Helpers.getJson
import top.rootu.lampa.helpers.Helpers.isAndroidTV
import top.rootu.lampa.helpers.Prefs.CUB
import top.rootu.lampa.helpers.Prefs.FAV
import top.rootu.lampa.helpers.Prefs.isInLampaWatchNext
import top.rootu.lampa.helpers.Prefs.lastPlayedPrefs
import top.rootu.lampa.helpers.Prefs.syncEnabled
import top.rootu.lampa.helpers.Prefs.wathToRemove
import top.rootu.lampa.models.LampaCard
import java.util.Locale


object WatchNext {
    private const val TAG = "WatchNext"
    private const val RESUME_ID = "-1"

    @SuppressLint("RestrictedApi")
    private val WATCH_NEXT_MAP_PROJECTION = arrayOf(
        TvContractCompat.BaseTvColumns._ID,
        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
        TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE
    )

    @SuppressLint("RestrictedApi")
    fun add(card: LampaCard) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isAndroidTV) return
        card.id?.let { movieId ->
            val existingProgram = findProgramByMovieId(movieId)
            val removed = removeIfNotBrowsable(existingProgram)
            val shouldUpdateProgram = existingProgram != null && !removed
            if (shouldUpdateProgram) {
                val contentValues = WatchNextProgram.Builder(existingProgram).build().toContentValues()
                val rowsUpdated = App.context.contentResolver.update(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    contentValues, null, null
                )
                if (rowsUpdated < 1) {
                    Log.e(TAG, "Failed to update Watch Next program ${existingProgram?.id}")
                }
            } else {
                val programUri = App.context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    getProgram(card).toContentValues()
                )
                if (programUri == null || programUri == Uri.EMPTY) {
                    Log.e(TAG, "Failed to insert movie $movieId into the Watch Next")
                }
            }
        }
    }

    fun rem(movieId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isAndroidTV) return
        movieId?.let { deleteFromWatchNext(it) }
    }

    suspend fun updateWatchNext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isAndroidTV) return
        val context = App.context
        val deleted = removeStale()
        if (BuildConfig.DEBUG) Log.d(TAG, "WatchNext cards removed: $deleted")

        val lst = when {
            // CUB
            context.syncEnabled -> context.CUB
                ?.filter { it.type == LampaProvider.LATE }
                ?.mapNotNull { it.data?.also { data -> data.fixCard() } }
                .orEmpty()
            // FAV
            else -> context.FAV?.card
                ?.filter { context.FAV?.wath?.contains(it.id) == true }
                ?.sortedBy { context.FAV?.wath?.indexOf(it.id) }
                ?.onEach { it.fixCard() }
                .orEmpty()
        }

        val (excludePending, pending) = lst.partition {
            !context.wathToRemove.contains(it.id.toString())
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "updateWatchNext() WatchNext items:${excludePending.size} ${excludePending.map { it.id }} pending to remove:${pending.size} ${pending.map { it.id }}"
            )
        }

        excludePending.forEach { card ->
            withContext(Dispatchers.Default) {
                try {
                    add(card)
                } catch (e: Exception) { // FIXME: WTF? Not allowed to change ID
                    if (BuildConfig.DEBUG) Log.d(TAG, "Error adding $card to WatchNext: $e")
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    fun addLastPlayed(card: LampaCard) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isAndroidTV) return
        card.id?.let { movieId ->
            if (movieId.isNotEmpty()) {
                deleteFromWatchNext(RESUME_ID)
            }
            val programUri = App.context.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                getProgram(card, true).toContentValues()
            )
            if (programUri == null || programUri == Uri.EMPTY) {
                Log.e(TAG, "Failed to insert movie $movieId into the Watch Next")
            }
        }
    }

    fun removeContinueWatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isAndroidTV) return
        deleteFromWatchNext(RESUME_ID)
    }

    @SuppressLint("RestrictedApi")
    fun getInternalIdFromWatchNextProgramId(watchNextId: Long): String? {
        val curWatchNextUri = buildWatchNextProgramUri(watchNextId)
        var watchNextProgram: WatchNextProgram? = null
        App.context.contentResolver.query(
            curWatchNextUri, null, null, null, null
        ).use { cursor ->
            if (cursor != null && cursor.count != 0) {
                cursor.moveToFirst()
                watchNextProgram = WatchNextProgram.fromCursor(cursor)
            }
        }
        return watchNextProgram?.internalProviderId
    }

    @SuppressLint("RestrictedApi")
    fun getCardFromWatchNextProgramId(watchNextId: Long): LampaCard? {
        val curWatchNextUri = buildWatchNextProgramUri(watchNextId)
        var watchNextProgram: WatchNextProgram? = null
        App.context.contentResolver.query(
            curWatchNextUri, null, null, null, null
        ).use { cursor ->
            if (cursor != null && cursor.count != 0) {
                cursor.moveToFirst()
                watchNextProgram = WatchNextProgram.fromCursor(cursor)
            }
        }
        val json = watchNextProgram?.intent?.getStringExtra("LampaCardJS")
        return getJson(json, LampaCard::class.java)
    }

    @SuppressLint("RestrictedApi")
    private fun deleteFromWatchNext(movieId: String) {
        val program = findProgramByMovieId(movieId)
        program?.let {
            if (BuildConfig.DEBUG) Log.d(TAG, "deleteFromWatchNext($movieId) removeProgram(${it.id})")
            removeProgram(it.id)
        }
    }
    // Find the movie by our app's internal id.
    @SuppressLint("RestrictedApi")
    private fun findProgramByMovieId(movieId: String): WatchNextProgram? {
        val cursor = App.context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WATCH_NEXT_MAP_PROJECTION,
            null,
            null,
            null
        )
        cursor?.let {
            if (it.moveToFirst()) {
                do {
                    val program = WatchNextProgram.fromCursor(it)
                    if (movieId == program.internalProviderId) {
                        cursor.close()
                        return program
                    }
                } while (it.moveToNext())
            }
            cursor.close()
        }
        return null
    }
    // Remove items not in Lampa Watch Later
    @SuppressLint("RestrictedApi")
    private fun removeStale(): Int {
        var count = 0
        val cursor = App.context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WATCH_NEXT_MAP_PROJECTION,
            null,
            null,
            null
        )
        cursor?.let {
            if (it.moveToFirst()) {
                do {
                    val program = WatchNextProgram.fromCursor(it)
                    if (!App.context.isInLampaWatchNext(program.internalProviderId) && program.internalProviderId != RESUME_ID) {
                        count++
                        removeProgram(program.id)
                    }
                } while (it.moveToNext())
            }
            cursor.close()
        }
        return count
    }
    // Check is a program has been removed from the UI by the user. If so, then
    // remove the program from the content provider.
    @SuppressLint("RestrictedApi")
    private fun removeIfNotBrowsable(program: WatchNextProgram?): Boolean {
        if (program?.isBrowsable == false) {
            removeProgram(program.id)
            return true
        }
        return false
    }

    private fun removeProgram(watchNextProgramId: Long): Int {
        val rowsDeleted = App.context.contentResolver.delete(
            buildWatchNextProgramUri(watchNextProgramId),
            null, null
        )
        if (rowsDeleted < 1) {
            Log.e(TAG, "Failed to delete program $watchNextProgramId from Watch Next")
        }
        return rowsDeleted
    }

    @SuppressLint("RestrictedApi")
    private fun getProgram(card: LampaCard, resume: Boolean = false): WatchNextProgram {
        val info = mutableListOf<String>()
        val programId = if (resume) RESUME_ID else card.id

        card.vote_average?.let { if (it > 0.0) info.add("%.1f".format(it)) }

        var title = card.title
        var type = TvContractCompat.WatchNextPrograms.TYPE_MOVIE

        if (card.type == "tv") {
            if (!card.name.isNullOrEmpty()) title = card.name
            type = if (resume) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
            else TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES
            card.number_of_seasons?.let { info.add("S$it") }
        }

        card.genres?.joinToString(", ") { g ->
            g?.name?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }.toString()
        }?.let { info.add(it) }
        // https://developer.android.com/codelabs/watchnext-for-movie-tv-episodes#3
        val watchType = if (resume) WATCH_NEXT_TYPE_CONTINUE else WATCH_NEXT_TYPE_WATCHLIST

        val builder = WatchNextProgram.Builder()
            .setType(type)
            .setWatchNextType(watchType)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setTitle(title)
            .setDescription(card.overview)
            .setGenre(info.joinToString(" · "))
            .setReviewRating((card.vote_average?.div(2) ?: 0).toString())
            .setIntent(Helpers.buildPendingIntent(card, resume))
            .setInternalProviderId(programId) // Our internal ID
            .setDurationMillis(card.runtime?.times(60000) ?: 0)
            .setReleaseDate(card.release_year)
            .setSearchable(true)
            .setLive(false)
//        if (type == TYPE_TV_EPISODE) {
//            builder.setEpisodeNumber(video.episodeNumber.toInt())
//            builder.setSeasonNumber(video.seasonNumber.toInt())
//            // Use TV series name and season number to generate a fake season name.
//            builder.setSeasonTitle(context.getString(R.string.season, video.category, video.seasonNumber))
//            // Use the name of the video as the episode name.
//            builder.setEpisodeTitle(video.name)
//            // Use TV series name as the tile, in this sample,
//            // we use category as a fake TV series.
//            builder.setTitle(video.category)
//        }
        if (resume) {
            val watchPosition = App.context.lastPlayedPrefs.getInt("position", 0)
            val duration = App.context.lastPlayedPrefs.getInt("duration", 0)
            builder.setLastPlaybackPositionMillis(watchPosition)
                .setDurationMillis(duration)
        }

        if (card.img.isNullOrEmpty()) {
            val resourceId = R.drawable.empty_poster // in-app poster
            val emptyPoster = Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(App.context.resources.getResourcePackageName(resourceId))
                .appendPath(App.context.resources.getResourceTypeName(resourceId))
                .appendPath(App.context.resources.getResourceEntryName(resourceId))
                .build()
            builder.setPosterArtUri(emptyPoster)
                .setPosterArtAspectRatio(ASPECT_RATIO_2_3)
        } else {
            builder.setPosterArtUri(Uri.parse(card.img))
                .setPosterArtAspectRatio(ASPECT_RATIO_2_3)
        }

        if (!card.background_image.isNullOrEmpty()) {
            builder.setThumbnailUri(Uri.parse(card.background_image))
                .setThumbnailAspectRatio(ASPECT_RATIO_16_9)
        }

        return builder.build()
    }
}