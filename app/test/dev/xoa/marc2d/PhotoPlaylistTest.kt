package dev.xoa.marc2d

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhotoPlaylistTest {
	private val photos = listOf("/a.jpg?authSig=1", "/b.jpg?authSig=1", "/c.jpg?authSig=1")

	private fun PhotoPlaylist.take(
		count: Int,
		from: List<String> = photos,
		order: PhotoOrder = PhotoOrder.Name,
	) = List(count) { next(from, order) }

	@Test
	fun `by name goes through them all and starts again`() {
		assertEquals(photos + photos.first(), PhotoPlaylist().take(4))
	}

	@Test
	fun `shuffled shows every photo once before any repeats`() {
		val playlist = PhotoPlaylist(Random(1))
		repeat(3) { assertEquals(photos.toSet(), playlist.take(3, order = PhotoOrder.Shuffle).toSet()) }
	}

	@Test
	fun `a new signature keeps the pass, with the new address`() {
		val playlist = PhotoPlaylist()
		playlist.take(1)
		val signedAgain = photos.map { it.replace("authSig=1", "authSig=2") }
		assertEquals(listOf("/b.jpg?authSig=2", "/c.jpg?authSig=2"), playlist.take(2, signedAgain))
	}

	@Test
	fun `a removed photo is skipped and a new one waits for the next pass`() {
		val playlist = PhotoPlaylist()
		playlist.take(1)
		val changed = listOf("/a.jpg?authSig=1", "/c.jpg?authSig=1", "/d.jpg?authSig=1")
		assertEquals(listOf("/c.jpg?authSig=1", "/a.jpg?authSig=1", "/c.jpg?authSig=1", "/d.jpg?authSig=1"), playlist.take(4, changed))
	}

	@Test
	fun `nothing without photos`() {
		assertNull(PhotoPlaylist().next(emptyList(), PhotoOrder.Shuffle))
	}
}
