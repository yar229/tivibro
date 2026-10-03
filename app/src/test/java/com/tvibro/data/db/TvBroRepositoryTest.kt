package com.tvibro.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tvibro.data.Prefs
import com.tvibro.data.model.Channel
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.EpgSource
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType
import com.tvibro.data.model.Program
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TvBroRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: TvBroRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TvBroDatabase.DB_NAME)
        // The guide's shift is a setting that outlives a single test, so it starts every one of
        // them on no shift at all.
        Prefs.get(context).epgOffsetMinutes = 0
        repo = TvBroRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TvBroDatabase.DB_NAME)
    }

    private fun playlist(name: String = "Test", type: PlaylistType = PlaylistType.FILE): Playlist =
        Playlist(name = name, type = type, url = "http://host/p.m3u", mac = "00:11:22:33:44:55")

    private fun channel(
        streamId: String,
        name: String = streamId,
        group: String = "News",
        order: Int = 0,
        isVod: Boolean = false,
    ) = Channel(
        streamId = streamId,
        name = name,
        groupTitle = group,

        orderIndex = order,
        isVod = isVod,
    )

    // ------------------------------------------------------------- playlists

    @Test
    fun `insert and read playlist keeps mac address`() {
        val id = repo.insertPlaylist(playlist())
        val stored = repo.playlist(id)
        assertEquals("Test", stored?.name)
        assertEquals("00:11:22:33:44:55", stored?.mac)
        assertEquals(PlaylistType.FILE, stored?.type)
    }

    @Test
    fun `playlists are listed`() {
        repo.insertPlaylist(playlist("A"))
        repo.insertPlaylist(playlist("B"))
        assertEquals(setOf("A", "B"), repo.playlists().map { it.name }.toSet())
    }

    @Test
    fun `delete playlist removes its channels`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a"), channel("b")))
        assertEquals(2, repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").size)

        repo.deletePlaylist(pid)

        assertTrue(repo.playlists().isEmpty())
        assertTrue(repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").isEmpty())
    }

    // -------------------------------------------------------------- channels

    @Test
    fun `channels are filtered by tv flag`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("live"), channel("movie", isVod = true)))

        val tv = repo.channels(listOf(pid), "", ChannelFilter.TV, "manual")
        val all = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual")

        assertEquals(listOf("live"), tv.map { it.streamId })
        assertEquals(2, all.size)
    }

    @Test
    fun `channels are filtered by group`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(
            pid,
            listOf(channel("a", group = "News"), channel("b", group = "Sports"))
        )

        val news = repo.channels(listOf(pid), "News", ChannelFilter.ALL, "manual")
        assertEquals(listOf("a"), news.map { it.streamId })
    }

    @Test
    fun `hidden and blocked channels are excluded by default`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a"), channel("b"), channel("c")))
        val stored = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual")
        repo.setChannelFlags(stored[0].id, hidden = true)
        repo.setChannelFlags(stored[1].id, blocked = true)

        assertEquals(1, repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").size)
        assertEquals(
            3,
            repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual", showHidden = true, showBlocked = true).size
        )
    }

    @Test
    fun `favorites filter works`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a"), channel("b")))
        val stored = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual")
        repo.setChannelFlags(stored[0].id, favorite = true)

        val favs = repo.channels(listOf(pid), "", ChannelFilter.FAVORITES, "manual")
        assertEquals(listOf("a"), favs.map { it.streamId })
    }

    @Test
    fun `search matches name and number`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(
            pid,
            listOf(channel("sky1", name = "Sky Sports Main").also { it.number = "101" }, channel("bbc1", name = "BBC One"))
        )

        val byName = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual", search = "sky")
        val byNumber = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual", search = "101")

        assertEquals(listOf("sky1"), byName.map { it.streamId })
        assertEquals(listOf("sky1"), byNumber.map { it.streamId })
    }

    @Test
    fun `groups are unique and ordered by order index`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(
            pid,
            listOf(
                channel("a", group = "News", order = 0),
                channel("b", group = "Sports", order = 1),
                channel("c", group = "News", order = 2),
            )
        )

        assertEquals(listOf("News", "Sports"), repo.groupsOf(pid))
    }

    @Test
    fun `limit caps the number of returned channels`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, (1..10).map { channel("c$it", order = it) })

        assertEquals(3, repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual", limit = 3).size)
    }

    @Test
    fun `progress and watch time are stored`() {
        val pid = repo.insertPlaylist(playlist())
        val list = listOf(channel("a"))
        repo.insertChannels(pid, list)
        val storedId = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").single().id

        repo.setProgress(storedId, 120_000L, 3_600_000L)
        repo.addWatchTime(storedId, 5_000L)
        val reloaded = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").single()

        assertEquals(120_000L, reloaded.progressMs)
        assertEquals(3_600_000L, reloaded.durationMs)
        assertEquals(5_000L, reloaded.watchTimeMs)
        assertTrue(reloaded.lastWatched > 0L)
    }

    // ------------------------------------------------------- replaceChannels

    @Test
    fun `replace channels preserves user state by stream id`() {
        val pid = repo.insertPlaylist(playlist())
        val original = listOf(channel("a", name = "Old A"), channel("b", name = "Old B"))
        repo.insertChannels(pid, original)
        val stored = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual")
        val idA = stored.first { it.streamId == "a" }.id
        repo.setChannelFlags(idA, favorite = true)
        repo.setChannelFlags(idA, hidden = true)
        repo.setProgress(idA, 55_000L, 100_000L)

        val replaced = repo.replaceChannels(
            pid,
            listOf(
                channel("a", name = "New A"),
                channel("b", name = "New B"),
                channel("c", name = "New C"),
            )
        )

        val byId = replaced.associateBy { it.streamId }
        assertEquals(setOf("a", "b", "c"), byId.keys)
        assertEquals("New A", byId.getValue("a").name)
        assertTrue(byId.getValue("a").favorite)
        assertTrue(byId.getValue("a").hidden)
        assertEquals(55_000L, byId.getValue("a").progressMs)
        assertFalse(byId.getValue("b").favorite)
    }

    @Test
    fun `replace channels drops streams that disappeared`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a"), channel("b")))

        val replaced = repo.replaceChannels(pid, listOf(channel("a")))

        assertEquals(listOf("a"), replaced.map { it.streamId })
        assertEquals(1, repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").size)
    }

    @Test
    fun `replace channels is idempotent`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a", name = "A")))

        repo.replaceChannels(pid, listOf(channel("a", name = "A2")))
        val firstId = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").single().id
        val second = repo.replaceChannels(pid, listOf(channel("a", name = "A3")))

        assertEquals(listOf("a"), second.map { it.streamId })
        assertEquals(firstId, second.single().id)
        assertEquals(1, repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").size)
    }

    @Test
    fun `replace channels keeps epg and history links of retained streams`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a"), channel("gone")))
        val stored = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual")
        val idA = stored.first { it.streamId == "a" }.id
        val idGone = stored.first { it.streamId == "gone" }.id
        val now = System.currentTimeMillis()
        repo.replacePrograms(listOf(idA, idGone), listOf(
            Program(channelId = idA, title = "News", start = now, stop = now + 3_600_000L),
        ))
        repo.addHistory(idA, 1_000L)

        repo.replaceChannels(pid, listOf(channel("a", name = "A renamed")))

        val programs = repo.programsFor(idA, now - 1_000L, now + 7_200_000L)
        assertEquals(1, programs.size)
        assertEquals("News", programs.first().title)
        assertEquals(1, repo.history().count { it.channelId == idA })
    }

    @Test
    fun `replace channels removes epg of streams that disappeared`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a"), channel("gone")))
        val stored = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual")
        val idA = stored.first { it.streamId == "a" }.id
        val idGone = stored.first { it.streamId == "gone" }.id
        val now = System.currentTimeMillis()
        repo.replacePrograms(listOf(idA, idGone), listOf(
            Program(channelId = idA, title = "Keep", start = now, stop = now + 3_600_000L),
            Program(channelId = idGone, title = "Drop", start = now, stop = now + 3_600_000L),
        ))

        repo.replaceChannels(pid, listOf(channel("a")))

        assertEquals(1, repo.programsFor(idA, now - 1_000L, now + 7_200_000L).size)
        assertTrue(repo.programsFor(idGone, now - 1_000L, now + 7_200_000L).isEmpty())
    }

    @Test
    fun `replace channels with an empty list clears the playlist`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel("a")))

        assertTrue(repo.replaceChannels(pid, emptyList()).isEmpty())
        assertTrue(repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").isEmpty())
    }

    @Test
    fun `replace channels keeps user state of untouched playlist`() {
        val pidA = repo.insertPlaylist(playlist("A"))
        val pidB = repo.insertPlaylist(playlist("B"))
        repo.insertChannels(pidA, listOf(channel("a")))
        repo.insertChannels(pidB, listOf(channel("b")))
        val storedB = repo.channels(listOf(pidB), "", ChannelFilter.ALL, "manual").single()
        repo.setChannelFlags(storedB.id, favorite = true)

        repo.replaceChannels(pidA, listOf(channel("a2")))

        val reloadedB = repo.channels(listOf(pidB), "", ChannelFilter.ALL, "manual").single()
        assertTrue(reloadedB.favorite)
    }

    // ------------------------------------------------------------ epg source

    @Test
    fun `epg sources are stored and bound per playlist`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertEpgSource(EpgSource(name = "Global", url = "http://epg/global.xml", playlistId = 0))
        repo.insertEpgSource(EpgSource(name = "Local", url = "http://epg/local.xml", playlistId = pid))

        assertEquals(setOf("Global", "Local"), repo.epgSources().map { it.name }.toSet())
        assertEquals(listOf("Local"), repo.epgSourcesFor(pid).map { it.name })
        assertEquals(setOf("Global", "Local"), repo.epgSourcesForAny(pid).map { it.name }.toSet())
    }

    @Test
    fun `disabled epg sources are ignored`() {
        val pid = repo.insertPlaylist(playlist())
        repo.insertEpgSource(
            EpgSource(name = "Off", url = "http://epg/off.xml", playlistId = pid, enabled = false)
        )
        assertTrue(repo.epgSourcesFor(pid).isEmpty())
        assertTrue(repo.epgSourcesForAny(pid).isEmpty())
    }

    // ------------------------------------------------------ backup / restore

    @Test
    fun `backup archive contains the database and preferences`() {
        repo.insertPlaylist(playlist("Backup me"))
        val target = File(context.cacheDir, "tvibro_backup_test.zip")

        assertTrue(repo.backupTo(target))
        assertTrue(target.exists())
        assertTrue(target.length() > 0)

        java.util.zip.ZipFile(target).use { zip ->
            assertTrue(zip.getEntry("tvibro.db") != null)
            assertTrue(zip.getEntry("tvibro_prefs.txt") != null)
        }
    }

    @Test
    fun `restore brings back a deleted playlist`() {
        val pid = repo.insertPlaylist(playlist("Survivor"))
        repo.insertChannels(pid, listOf(channel("keepme")))
        val target = File(context.cacheDir, "tvibro_backup_restore.zip")
        assertTrue(repo.backupTo(target))

        repo.deletePlaylist(pid)
        assertTrue(repo.playlists().isEmpty())

        assertTrue(repo.restoreFrom(target))

        val restored = repo.playlists()
        assertEquals(listOf("Survivor"), restored.map { it.name })
        assertEquals(
            listOf("keepme"),
            repo.channels(listOf(restored.single().id), "", ChannelFilter.ALL, "manual").map { it.streamId }
        )
    }

    @Test
    fun `restore fails gracefully for a non archive file`() {
        val bogus = File(context.cacheDir, "not_a_backup.zip")
        bogus.writeText("definitely not a zip")
        assertFalse(repo.restoreFrom(bogus))
    }

    @Test
    fun `restore fails gracefully for a missing file`() {
        assertFalse(repo.restoreFrom(File(context.cacheDir, "absent.zip")))
    }

    // ------------------------------------------------------------ epg offset

    /**
     * A programme the guide reads an hour and a half back: its times come out moved, and the window
     * that finds it is walked on the scale the rows are stored on rather than on the wall clock.
     */
    @Test
    fun `guide times move with the offset`() {
        val channelId = channelWithProgram("a", "News", start = 1_700_000_000_000L)
        Prefs.get(context).epgOffsetMinutes = -90

        val shown = repo.programsFor(channelId, 1_700_000_000_000L - 90 * 60_000L - 1_000L, 1_700_000_000_000L + 3600_000L)

        assertEquals(1, shown.size)
        assertEquals(1_700_000_000_000L - 90 * 60_000L, shown.first().start)
    }

    /**
     * A programme moved forward by the offset is not the one on air any more. A query that only
     * moved what it handed out would still answer with it, which is how a guide ends up promising
     * something that has been running for two hours.
     */
    @Test
    fun `a programme pushed forward stops being the one on air`() {
        val stored = 1_700_000_000_000L
        val channelId = channelWithProgram("a", "News", start = stored)
        Prefs.get(context).epgOffsetMinutes = 120

        assertNull(repo.currentProgram(channelId, stored + 30 * 60_000L))

        val onAir = repo.currentProgram(channelId, stored + 2 * 3600_000L + 30 * 60_000L)
        assertEquals("News", onAir?.title)
        assertEquals(stored + 2 * 3600_000L, onAir?.start)
    }

    @Test
    fun `the shift is applied on the way out and never written back`() {
        val stored = 1_700_000_000_000L
        val channelId = channelWithProgram("a", "News", start = stored)

        Prefs.get(context).epgOffsetMinutes = 60
        assertEquals(
            stored + 3600_000L,
            repo.programsFor(channelId, stored - 1000L, stored + 2 * 3600_000L).first().start,
        )

        // Back on no shift the row is what the source reported, so moving the setting costs
        // nothing and needs no reimport of the guide.
        Prefs.get(context).epgOffsetMinutes = 0
        assertEquals(
            stored,
            repo.programsFor(channelId, stored - 1000L, stored + 2 * 3600_000L).first().start,
        )
    }

    @Test
    fun `pruning keeps what the guide still shows`() {
        val stored = 1_700_000_000_000L
        val channelId = channelWithProgram("a", "News", start = stored)
        Prefs.get(context).epgOffsetMinutes = 120

        // The row has been over for two hours as far as the guide is concerned, but not on the
        // scale it is stored on, so a cutoff in wall-clock time must not reach it.
        repo.clearProgramsBefore(stored + 2 * 3600_000L)
        assertEquals(1, repo.programsFor(channelId, stored - 1000L, stored + 5 * 3600_000L).size)

        repo.clearProgramsBefore(stored + 4 * 3600_000L)
        assertTrue(repo.programsFor(channelId, stored - 1000L, stored + 5 * 3600_000L).isEmpty())
    }

    private fun channelWithProgram(streamId: String, title: String, start: Long): Long {
        val pid = repo.insertPlaylist(playlist())
        repo.insertChannels(pid, listOf(channel(streamId)))
        val id = repo.channels(listOf(pid), "", ChannelFilter.ALL, "manual").first { it.streamId == streamId }.id
        repo.replacePrograms(listOf(id), listOf(
            Program(channelId = id, title = title, start = start, stop = start + 3600_000L),
        ))
        return id
    }
}
