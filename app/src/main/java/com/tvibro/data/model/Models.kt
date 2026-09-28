package com.tvibro.data.model

/** Type of a playlist source. */
enum class PlaylistType(val id: Int) {
    FILE(0),
    REMOTE_M3U(1),
    XTREAM(2),
    STALKER(3);

    companion object {
        fun from(id: Int) = entries.firstOrNull { it.id == id } ?: FILE
    }
}

data class Playlist(
    val id: Long = 0,
    var name: String = "",
    var type: PlaylistType = PlaylistType.FILE,
    var url: String = "",
    var login: String = "",
    var password: String = "",
    var userAgent: String = "",
    var epgUrl: String = "",
    var mac: String = "",
    var enabled: Boolean = true,
    var orderIndex: Int = 0,
    var dateAdded: Long = System.currentTimeMillis(),
    var lastUpdate: Long = 0,
    var hidden: Boolean = false,
) {
    val isRemote: Boolean
        get() = type == PlaylistType.REMOTE_M3U || type == PlaylistType.XTREAM || type == PlaylistType.STALKER
}

/**
 * A playable entry. Live channels have [isVod] == false, movies/shows have it true.
 */
data class Channel(
    var id: Long = 0,
    var playlistId: Long = 0,
    var playlistName: String = "",
    var streamId: String = "",
    var name: String = "",
    var number: String = "",
    var logoUrl: String = "",
    var groupTitle: String = "",
    var tvgId: String = "",
    var tvgName: String = "",
    var url: String = "",
    var catchupSource: String = "",
    var catchupDays: Int = 0,
    var catchupType: String = "",
    var isVod: Boolean = false,
    var vodCategory: String = "",
    var vodYear: String = "",
    var vodRating: String = "",
    var seriesName: String = "",
    var season: String = "",
    var episode: String = "",
    var containerExtension: String = "",
    var favorite: Boolean = false,
    var hidden: Boolean = false,
    var blocked: Boolean = false,
    var inMyList: Boolean = false,
    var orderIndex: Int = 0,
    var dateAdded: Long = System.currentTimeMillis(),
    var lastModified: Long = System.currentTimeMillis(),
    var watchTimeMs: Long = 0,
    var lastWatched: Long = 0,
    var progressMs: Long = 0,
    var durationMs: Long = 0,
    var isSeries: Boolean = false,
)

data class Program(
    val id: Long = 0,
    var channelId: Long = 0,
    var tvgId: String = "",
    var title: String = "",
    var subtitle: String = "",
    var description: String = "",
    var category: String = "",
    var icon: String = "",
    var start: Long = 0,
    var stop: Long = 0,
) {
    val isLive: Boolean
        get() = System.currentTimeMillis() in start until stop
}

data class EpgSource(
    val id: Long = 0,
    var name: String = "",
    var url: String = "",
    var playlistId: Long = 0,
    var priority: Int = 0,
    var lastUpdate: Long = 0,
    var enabled: Boolean = true,
)

data class Reminder(
    val id: Long = 0,
    var channelId: Long = 0,
    var programTitle: String = "",
    var start: Long = 0,
    var minutesBefore: Int = 5,
    var action: Int = 0,
)

data class HistoryEntry(
    val channelId: Long,
    val watchedAt: Long,
    val watchTimeMs: Long,
)

/** One fetched playlist, before it is merged into the database. */
data class ParsedChannel(
    val streamId: String,
    val name: String,
    val number: String = "",
    val logoUrl: String = "",
    val groupTitle: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val url: String = "",
    val catchupSource: String = "",
    val catchupDays: Int = 0,
    val catchupType: String = "",
    val isVod: Boolean = false,
    val vodCategory: String = "",
    val vodYear: String = "",
    val vodRating: String = "",
    val seriesName: String = "",
    val season: String = "",
    val episode: String = "",
    val containerExtension: String = "",
    val durationMs: Long = 0,
)

/** Filter applied to the channel list. */
enum class ChannelFilter(val id: Int) {
    ALL(0),
    TV(1),
    FAVORITES(2),
    HISTORY(3),
    UNASSIGNED(4);

    companion object {
        fun from(id: Int) = entries.firstOrNull { it.id == id } ?: ALL
    }
}
