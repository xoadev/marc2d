package dev.xoa.marc2d

import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class ReadAtMostTest {
	@Test
	fun `reads a photo up to the limit and refuses one past it`() =
		runBlocking {
			val photo = ByteArray(1000) { it.toByte() }
			assertContentEquals(photo, readAtMost(ByteReadChannel(photo), limit = 1000))
			assertNull(readAtMost(ByteReadChannel(photo), limit = 999))
		}
}
