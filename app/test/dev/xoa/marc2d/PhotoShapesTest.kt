package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoShapesTest {
	private val wide = "/wide.jpg?authSig=1"
	private val tall = "/tall.jpg?authSig=1"
	private val photos = listOf(wide, tall)

	@Test
	fun `tells landscape, portrait and nearly square photos apart`() {
		assertEquals(PhotoShape.Landscape, PhotoShape.of(4000, 3000))
		assertEquals(PhotoShape.Portrait, PhotoShape.of(3000, 4000))
		assertEquals(PhotoShape.Square, PhotoShape.of(1000, 980))
		assertTrue(PhotoShape.Square.fits(landscapeScreen = true) && PhotoShape.Square.fits(landscapeScreen = false))
	}

	@Test
	fun `in landscape a portrait photo is refused and then skipped without downloading it`() {
		val shapes = PhotoShapes()
		assertFalse(shapes.skip(tall, photos, landscape = true))
		assertFalse(shapes.accept(tall, 3000, 4000, photos, landscape = true))
		// A new signature is the same photo.
		assertTrue(shapes.skip("/tall.jpg?authSig=2", photos, landscape = true))
		assertTrue(shapes.accept(wide, 4000, 3000, photos, landscape = true))
		assertFalse(shapes.skip(tall, photos, landscape = false))
	}

	@Test
	fun `with no photo of the screen's shape, they all show`() {
		val shapes = PhotoShapes()
		val portraits = listOf(tall, "/tall2.jpg")
		assertFalse(shapes.accept(tall, 3000, 4000, portraits, landscape = true))
		assertTrue(shapes.accept("/tall2.jpg", 3000, 4000, portraits, landscape = true))
		assertFalse(shapes.skip(tall, portraits, landscape = true))
	}

	@Test
	fun `with the orientation off, every photo shows`() {
		val shapes = PhotoShapes()
		assertTrue(shapes.accept(tall, 3000, 4000, photos, landscape = null))
		assertFalse(shapes.skip(tall, photos, landscape = null))
	}
}
