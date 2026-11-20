package dev.xoa.marc2d

import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtspSessionTest {
	/** What a Dahua VTO offers: its video and sound, and a track that only receives, for the voice. */
	private val dahua =
		listOf(
			"v=0",
			"o=- 2252253855 2252253855 IN IP4 0.0.0.0",
			"s=Media Server",
			"c=IN IP4 0.0.0.0",
			"t=0 0",
			"a=control:*",
			"m=video 0 RTP/AVP 96",
			"a=control:trackID=0",
			"a=framerate:25.000000",
			"a=rtpmap:96 H264/90000",
			"a=fmtp:96 packetization-mode=1;profile-level-id=42000D;sprop-parameter-sets=Z0IADekCwS0IAAAfSAAGHBAgAA==,aMqPIAA=",
			"a=recvonly",
			"m=audio 0 RTP/AVP 97",
			"a=control:trackID=1",
			"a=rtpmap:97 L16/16000",
			"a=recvonly",
			"m=audio 0 RTP/AVP 97",
			"a=control:trackID=5",
			"a=rtpmap:97 L16/16000",
			"a=sendonly",
			"",
		).joinToString("\r\n")

	@Test
	fun `reads the intercom's offer`() {
		val offer = RtspOffer.from(dahua)
		val video = assertNotNull(offer.video)
		assertEquals("trackID=0", video.control)
		assertEquals(0x67, assertNotNull(video.parameters).sps[0].toInt() and 0xFF, "the SPS, from sprop-parameter-sets")
		assertEquals(RtspAudioTrack("trackID=1", 97, "L16", 16_000), offer.sound)
		assertEquals(RtspAudioTrack("trackID=5", 97, "L16", 16_000), offer.voice)
		assertNull(RtspOffer.from(dahua.replace("a=sendonly", "a=recvonly")).voice, "nothing to talk to")
		val ulaw = "m=audio 0 RTP/AVP 0\r\na=control:track3\r\na=sendonly\r\n"
		assertEquals(RtspAudioTrack("track3", 0, "PCMU", 8000), RtspOffer.from(ulaw).voice, "a static type needs no rtpmap")
		assertNull(RtspOffer.from("m=audio 0 RTP/AVP 98\r\na=rtpmap:98 opus/48000/2\r\na=control:t\r\na=sendonly\r\n").voice)
	}

	@Test
	fun `the track's control goes after the base, as cameras expect`() {
		val base = "rtsp://192.0.2.10/cam/realmonitor?channel=1&subtype=1"
		assertEquals("$base/trackID=5", RtspSession.controlUri(base, "trackID=5"))
		assertEquals("rtsp://192.0.2.10/a/track1", RtspSession.controlUri("rtsp://192.0.2.10/a/", "track1"))
		assertEquals("rtsp://192.0.2.10/other", RtspSession.controlUri(base, "rtsp://192.0.2.10/other"))
		assertEquals(base, RtspSession.controlUri(base, "*"))
	}

	@Test
	fun `the channel is the one the server chose`() {
		assertEquals(10, interleavedChannel("RTP/AVP/TCP;unicast;interleaved=10-11;ssrc=00000000"))
		assertNull(interleavedChannel("RTP/AVP;unicast;client_port=5000-5001"))
		assertNull(interleavedChannel(null))
	}

	@Test
	fun `G711 as its reference values, both ways`() {
		assertEquals(0xD5.toByte(), alaw(0))
		assertEquals(0x55.toByte(), alaw(-1))
		assertEquals(0xAA.toByte(), alaw(Short.MAX_VALUE))
		assertEquals(0xFF.toByte(), ulaw(0))
		assertEquals(0x80.toByte(), ulaw(Short.MAX_VALUE))
		assertEquals(0x00.toByte(), ulaw(Short.MIN_VALUE))
		for (sample in listOf<Short>(0, 100, -100, 1000, -1000, 20_000, -20_000)) {
			// G.711 keeps about 13 bits: what comes back is close, not the same.
			val tolerance = maxOf(16, kotlin.math.abs(sample.toInt()) / 16)
			assertTrue(kotlin.math.abs(alawToLinear(alaw(sample)) - sample) <= tolerance, "A-law $sample")
			assertTrue(kotlin.math.abs(ulawToLinear(ulaw(sample)) - sample) <= tolerance, "µ-law $sample")
		}
	}

	@Test
	fun `rebuilds H264 frames from single units, aggregates and fragments`() {
		val depacketizer = H264Depacketizer()
		val sps = byteArrayOf(0x67, 1, 2)
		val pps = byteArrayOf(0x68, 3)
		val stapA = byteArrayOf(24) + byteArrayOf(0, 3) + sps + byteArrayOf(0, 2) + pps
		assertNull(depacketizer.push(rtp(1, marker = false, stapA), rtpData(1, false, stapA)))
		// An IDR slice in two fragments.
		val first = byteArrayOf(0x7C, 0x85.toByte(), 10, 11)
		val last = byteArrayOf(0x7C, 0x45, 12)
		assertNull(depacketizer.push(rtp(2, false, first), rtpData(2, false, first)))
		val frame = assertNotNull(depacketizer.push(rtp(3, true, last), rtpData(3, true, last)))
		val start = H264Depacketizer.START_CODE
		assertContentEquals(start + sps + start + pps + start + byteArrayOf(0x65, 10, 11, 12), frame)
		assertTrue(H264Depacketizer.isKeyFrame(frame))

		// A lost fragment drops its unit, never a broken one.
		assertNull(depacketizer.push(rtp(4, false, first), rtpData(4, false, first)))
		val single = byteArrayOf(0x41, 7)
		assertNull(depacketizer.push(rtp(6, true, last), rtpData(6, true, last)))
		val next = assertNotNull(depacketizer.push(rtp(7, true, single), rtpData(7, true, single)))
		assertContentEquals(start + single, next)
		assertFalse(H264Depacketizer.isKeyFrame(next))
	}

	@Test
	fun `a lost fragment takes only its own unit out of the frame`() {
		val depacketizer = H264Depacketizer()
		val sps = byteArrayOf(0x67, 1, 2)
		val first = byteArrayOf(0x7C, 0x85.toByte(), 10, 11)
		val last = byteArrayOf(0x7C, 0x45, 12)
		val single = byteArrayOf(0x41, 7)
		assertNull(depacketizer.push(rtp(1, false, sps), rtpData(1, false, sps)))
		assertNull(depacketizer.push(rtp(2, false, first), rtpData(2, false, first)))
		assertNull(depacketizer.push(rtp(4, false, last), rtpData(4, false, last)))
		val frame = assertNotNull(depacketizer.push(rtp(5, true, single), rtpData(5, true, single)))
		val start = H264Depacketizer.START_CODE
		assertContentEquals(start + sps + start + single, frame)
	}

	@Test
	fun `the credentials are percent-decoded, and a plus stays a plus`() {
		assertEquals("admin" to "p+s@ss", RtspSession.credentials("admin:p+s%40ss"))
		assertEquals("admin" to "a:b", RtspSession.credentials("admin:a%3Ab"))
		assertEquals("admin" to "", RtspSession.credentials("admin"))
	}

	@Test
	fun `a frame that never ends is dropped before it fills the memory`() {
		val depacketizer = H264Depacketizer()
		val big = byteArrayOf(0x41) + ByteArray(60_000)
		// Over the limit without a marker: what came is let go, not kept.
		repeat(100) { assertNull(depacketizer.push(rtp(it, false, big), rtpData(it, false, big))) }
		assertNull(depacketizer.push(rtp(100, true, big), rtpData(100, true, big)), "the rest of the dropped frame")
		val single = byteArrayOf(0x41, 7)
		assertContentEquals(H264Depacketizer.START_CODE + single, depacketizer.push(rtp(101, true, single), rtpData(101, true, single)))
	}

	@Test
	fun `opens video, sound and the backchannel, on the channels the intercom chose`() {
		val server = ServerSocket(0)
		val requests = LinkedBlockingQueue<String>()
		val voice = LinkedBlockingQueue<Pair<Int, ByteArray>>()
		val vto =
			Thread {
				server.accept().use { client ->
					val input = BufferedInputStream(client.getInputStream())
					val output = client.getOutputStream()

					fun reply(
						cseq: String,
						head: String,
						body: String = "",
					) {
						output.write("RTSP/1.0 $head\r\nCSeq: $cseq\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
					}

					fun packet(
						channel: Int,
						bytes: ByteArray,
					) {
						output.write(interleaved(channel, bytes))
					}
					var setups = 0
					while (true) {
						val first = input.read()
						if (first < 0) return@Thread
						if (first == '$'.code) {
							voice += readInterleaved(input) ?: return@Thread
							continue
						}
						val lines = mutableListOf(first.toChar() + (readRtspLine(input, LINE_LIMIT) ?: return@Thread))
						while (true) lines += readRtspLine(input, LINE_LIMIT)?.takeIf { it.isNotEmpty() } ?: break
						val request = lines.joinToString("\n")
						requests += request
						val cseq = lines.first { it.startsWith("CSeq:") }.substringAfter(":").trim()
						when {
							"Authorization: Digest" !in request -> {
								reply(cseq, "401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"Login to VTO\", nonce=\"abc\"")
							}

							request.startsWith("DESCRIBE") -> {
								reply(cseq, "200 OK\r\nContent-Type: application/sdp", dahua)
							}

							// Like the VTO: its own channels, whatever is asked.
							request.startsWith("SETUP") -> {
								val channel = 6 + 2 * setups++
								reply(cseq, "200 OK\r\nSession: 1234;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}")
							}

							request.startsWith("PLAY") -> {
								reply(cseq, "200 OK\r\nSession: 1234")
								packet(6, rtpData(1, true, byteArrayOf(0x41, 7)))
								packet(8, rtpData(1, false, byteArrayOf(0, 1, 0, 2), type = 97))
								packet(7, ByteArray(8))
							}

							else -> {
								reply(cseq, "200 OK\r\nSession: 1234")
							}
						}
					}
				}
			}.apply { start() }
		try {
			val session = RtspSession.open("rtsp://admin:p%40ss@127.0.0.1:${server.localPort}/cam/realmonitor?channel=1&subtype=1")
			val uri = "rtsp://127.0.0.1:${server.localPort}/cam/realmonitor?channel=1&subtype=1"
			val describe = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
			assertTrue(describe.startsWith("DESCRIBE $uri RTSP/1.0"))
			assertFalse("admin" in describe, "the credentials are not in the URL")
			val authorized = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
			assertTrue("Require: www.onvif.org/ver20/backchannel" in authorized)
			val expected = digestResponse("admin", "Login to VTO", "p@ss", "abc", "DESCRIBE", uri)
			assertTrue("response=\"$expected\"" in authorized, "Digest with the decoded password")
			val setups = List(3) { assertNotNull(requests.poll(2, TimeUnit.SECONDS)) }
			assertEquals(listOf("trackID=0", "trackID=1", "trackID=5"), setups.map { it.substringBefore(" RTSP").substringAfterLast("/") })
			assertTrue("Session: 1234" in setups[1], "one session for every track")
			assertTrue(assertNotNull(requests.poll(2, TimeUnit.SECONDS)).startsWith("PLAY $uri RTSP/1.0"))

			val received = LinkedBlockingQueue<Pair<RtspMedia, Int>>()
			val receiving = Thread { session.receive { media, rtp, _ -> received += media to rtp.payloadLength } }.apply { start() }
			assertEquals(RtspMedia.Video to 2, received.poll(2, TimeUnit.SECONDS))
			assertEquals(RtspMedia.Sound to 4, received.poll(2, TimeUnit.SECONDS))
			assertNull(received.poll(200, TimeUnit.MILLISECONDS), "the video's RTCP channel is not media")

			// The voice goes out 20 ms at a time, in big-endian, on the backchannel's channel.
			session.sendVoice(ShortArray(200) { 1 })
			session.sendVoice(ShortArray(200) { 1 })
			val (channel, packet) = assertNotNull(voice.poll(2, TimeUnit.SECONDS))
			assertEquals(10, channel)
			val rtp = assertNotNull(RtpPacket.parse(packet, packet.size))
			assertEquals(97, rtp.payloadType)
			assertEquals(640, rtp.payloadLength)
			assertContentEquals(byteArrayOf(0, 1), packet.copyOfRange(rtp.payloadOffset, rtp.payloadOffset + 2))
			assertNull(voice.poll(200, TimeUnit.MILLISECONDS), "the rest waits for the next frame")

			session.close()
			assertTrue(assertNotNull(requests.poll(2, TimeUnit.SECONDS)).startsWith("TEARDOWN"))
			receiving.join(2000)
			assertFalse(receiving.isAlive, "closing ends the reception")
		} finally {
			server.close()
			vto.join(2000)
		}
	}

	@Test
	fun `a failure never carries the URL`() {
		val server = ServerSocket(0)
		val vto =
			Thread {
				server.accept().use { client ->
					val input = client.getInputStream().bufferedReader()
					while (input.readLine()?.isNotEmpty() == true) {
						// The request.
					}
					client.getOutputStream().write("RTSP/1.0 404 Not Found\r\nCSeq: 1\r\n\r\n".toByteArray())
				}
			}.apply { start() }
		try {
			val error = assertFailsWith<IOException> { RtspSession.open("rtsp://admin:secret@127.0.0.1:${server.localPort}/x") }
			assertFalse("secret" in error.message.orEmpty())
			assertFalse("secret" in assertFailsWith<IOException> { RtspSession.open("http://admin:secret@192.0.2.1/") }.message.orEmpty())
		} finally {
			server.close()
			vto.join(2000)
		}
	}

	private fun rtp(
		sequence: Int,
		marker: Boolean,
		payload: ByteArray,
	): RtpPacket = RtpPacket.parse(rtpData(sequence, marker, payload), 12 + payload.size)!!

	private fun rtpData(
		sequence: Int,
		marker: Boolean,
		payload: ByteArray,
		type: Int = 96,
	): ByteArray = rtpHeader(type, sequence, 0, ssrc = 1, marker = marker) + payload
}

private const val LINE_LIMIT = 1024
