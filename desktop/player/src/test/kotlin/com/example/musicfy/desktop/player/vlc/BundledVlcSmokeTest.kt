package com.example.musicfy.desktop.player.vlc

import com.example.musicfy.desktop.player.testing.NeedsLibVlc
import com.example.musicfy.desktop.player.testing.RangeFileServer
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Experiment E1 (spec §5.0): raw vlcj against the bundled libVLC, no Musicfy engine code involved. */
@Category(NeedsLibVlc::class)
class BundledVlcSmokeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        private lateinit var factory: MediaPlayerFactory

        @BeforeClass
        @JvmStatic
        fun loadLibVlc() {
            val audible = System.getProperty("musicfy.vlc.audible") == "true"
            factory = try {
                MediaPlayerFactory(VlcNatives.discovery(), *vlcArgs(silent = !audible, verbose = true).toTypedArray())
            } catch (t: Throwable) {
                throw AssertionError("libVLC did not load (bundled dir: ${VlcNatives.bundledDir()}). Run scripts/desktop/fetch-libvlc.sh", t)
            }
            println("E1: libVLC loaded from ${factory.nativeLibraryPath()}")
        }

        @AfterClass
        @JvmStatic
        fun release() {
            if (::factory.isInitialized) factory.release()
        }
    }

    private fun fixture(name: String): ByteArray =
        BundledVlcSmokeTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes()

    @Test
    fun theBundledCopyIsLoadedOnMacAndWindows() {
        if (!RuntimeUtil.isMac() && !RuntimeUtil.isWindows()) return
        val bundled = VlcNatives.bundledDir()!!.canonicalPath
        assertTrue("loaded ${factory.nativeLibraryPath()}, expected under $bundled", File(factory.nativeLibraryPath()).canonicalPath.startsWith(bundled))
    }

    @Test
    fun opusWebmOverHttpPlaysSeeksAndEnds() = playsSeeksAndEnds("silence-5s.webm")

    @Test
    fun aacM4aOverHttpPlaysSeeksAndEnds() = playsSeeksAndEnds("silence-5s.m4a")

    @Test
    fun localFilePlaysToTheEnd() {
        val file = tmp.newFile("song.webm").apply { writeBytes(fixture("silence-5s.webm")) }
        val result = play(file.toPath().toUri().toString(), seekToMs = null)
        assertFalse("libVLC reported an error for file://", result.errored)
        assertTrue("file:// never finished", result.finished)
    }

    private fun playsSeeksAndEnds(name: String) {
        RangeFileServer(mapOf(name to fixture(name))).use { http ->
            val result = play(http.url(name), seekToMs = 2_500)
            assertFalse("libVLC reported an error for $name", result.errored)
            assertTrue("$name: length ${result.lengthMs} ms, expected about 5000", result.lengthMs in 4_500..5_500)
            assertTrue("$name: seek to 2500 ms did not land (max time ${result.maxTimeMs} ms)", result.seekLanded)
            assertTrue("$name never finished", result.finished)
        }
    }

    private class PlayResult(val errored: Boolean, val finished: Boolean, val lengthMs: Long, val maxTimeMs: Long, val seekLanded: Boolean)

    private fun play(mrl: String, seekToMs: Long?): PlayResult {
        val player = factory.mediaPlayers().newMediaPlayer()
        val playing = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val errored = AtomicBoolean(false)
        val maxTime = AtomicLong(0)
        val length = AtomicLong(0)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
            override fun finished(mediaPlayer: MediaPlayer) = finished.countDown()
            override fun error(mediaPlayer: MediaPlayer) {
                errored.set(true)
                playing.countDown()
                finished.countDown()
            }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                maxTime.accumulateAndGet(newTime, ::maxOf)
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = length.set(newLength)
        })
        try {
            assertTrue("play() refused $mrl", player.media().play(mrl))
            assertTrue("never started playing $mrl", playing.await(15, SECONDS))
            var seekLanded = seekToMs == null
            if (seekToMs != null && !errored.get()) {
                player.controls().setTime(seekToMs)
                val deadline = System.currentTimeMillis() + 4_000
                while (System.currentTimeMillis() < deadline && maxTime.get() < seekToMs - 100) Thread.sleep(50)
                seekLanded = maxTime.get() >= seekToMs - 100
            }
            val done = finished.await(20, SECONDS)
            return PlayResult(errored.get(), done && !errored.get(), length.get(), maxTime.get(), seekLanded)
        } finally {
            player.controls().stop()
            player.release()
        }
    }
}
