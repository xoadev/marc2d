package dev.xoa.marc2d

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtspBackchannelTest {
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
			"a=rtpmap:96 H264/90000",
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
	fun `finds the track for the voice in the intercom's offer`() {
		assertEquals(BackchannelTrack("trackID=5", 97, "L16", 16_000), BackchannelTrack.from(dahua))
		assertNull(BackchannelTrack.from(dahua.replace("a=sendonly", "a=recvonly")), "nothing to talk to")
		val ulaw = "m=audio 0 RTP/AVP 0\r\na=control:track3\r\na=sendonly\r\n"
		assertEquals(BackchannelTrack("track3", 0, "PCMU", 8000), BackchannelTrack.from(ulaw), "a static type needs no rtpmap")
		assertNull(BackchannelTrack.from("m=audio 0 RTP/AVP 98\r\na=rtpmap:98 opus/48000/2\r\na=control:t\r\na=sendonly\r\n"))
	}

	@Test
	fun `the track's control goes after the base, as cameras expect`() {
		val base = "rtsp://192.0.2.10/cam/realmonitor?channel=1&subtype=1"
		assertEquals("$base/trackID=5", RtspBackchannel.controlUri(base, "trackID=5"))
		assertEquals("rtsp://192.0.2.10/a/track1", RtspBackchannel.controlUri("rtsp://192.0.2.10/a/", "track1"))
		assertEquals("rtsp://192.0.2.10/other", RtspBackchannel.controlUri(base, "rtsp://192.0.2.10/other"))
		assertEquals(base, RtspBackchannel.controlUri(base, "*"))
	}

	@Test
	fun `the channel is the one the server chose`() {
		assertEquals(10, RtspBackchannel.interleavedChannel("RTP/AVP/TCP;unicast;interleaved=10-11;ssrc=00000000"))
		assertEquals(null, RtspBackchannel.interleavedChannel("RTP/AVP;unicast;client_port=5000-5001"))
		assertEquals(null, RtspBackchannel.interleavedChannel(null))
	}

	@Test
	fun `G711 as its reference values`() {
		assertEquals(0xD5.toByte(), alaw(0))
		assertEquals(0x55.toByte(), alaw(-1))
		assertEquals(0xAA.toByte(), alaw(Short.MAX_VALUE))
		assertEquals(0xFF.toByte(), ulaw(0))
		assertEquals(0x80.toByte(), ulaw(Short.MAX_VALUE))
		assertEquals(0x00.toByte(), ulaw(Short.MIN_VALUE))
	}

	@Test
	fun `opens the backchannel with Digest and sends the voice interleaved, in big-endian`() {
		val server = ServerSocket(0)
		val requests = LinkedBlockingQueue<String>()
		val packets = LinkedBlockingQueue<ByteArray>()
		val channels = LinkedBlockingQueue<Int>()
		val vto =
			Thread {
				server.accept().use { client ->
					val input = DataInputStream(BufferedInputStream(client.getInputStream()))
					val output = client.getOutputStream()

					fun reply(
						cseq: String,
						head: String,
						body: String = "",
					) {
						output.write("RTSP/1.0 $head\r\nCSeq: $cseq\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
					}
					while (true) {
						val first = input.read()
						if (first < 0) return@Thread
						if (first == '$'.code) {
							channels += input.read()
							val length = input.readUnsignedShort()
							packets += ByteArray(length).also(input::readFully)
							continue
						}
						val lines = mutableListOf(first.toChar() + input.readLine())
						while (true) lines += input.readLine().takeIf { it.isNotEmpty() } ?: break
						val request = lines.joinToString("\n")
						requests += request
						val cseq = lines.first { it.startsWith("CSeq:") }.substringAfter(":").trim()
						val method = lines.first().substringBefore(" ")
						when {
							"Authorization: Digest" !in request -> reply(cseq, "401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"Login to VTO\", nonce=\"abc\"")
							method == "DESCRIBE" -> reply(cseq, "200 OK\r\nContent-Type: application/sdp", dahua)
							method == "SETUP" -> reply(cseq, "200 OK\r\nSession: 1234;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=10-11;ssrc=00000000")
							else -> reply(cseq, "200 OK\r\nSession: 1234")
						}
					}
				}
			}.apply { start() }
		try {
			val channel = RtspBackchannel.open("rtsp://admin:p%40ss@127.0.0.1:${server.localPort}/cam/realmonitor?channel=1&subtype=1")
			val describe = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
			assertTrue(describe.startsWith("DESCRIBE rtsp://127.0.0.1:${server.localPort}/cam/realmonitor?channel=1&subtype=1 RTSP/1.0"))
			assertTrue("admin" !in describe, "the credentials are not in the URL")
			val authorized = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
			assertTrue("Require: www.onvif.org/ver20/backchannel" in authorized)
			val uri = "rtsp://127.0.0.1:${server.localPort}/cam/realmonitor?channel=1&subtype=1"
			val expected = md5("${md5("admin:Login to VTO:p@ss")}:abc:${md5("DESCRIBE:$uri")}")
			assertTrue("response=\"$expected\"" in authorized, "Digest with the decoded password")
			val setup = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
			assertTrue(setup.startsWith("SETUP $uri/trackID=5 RTSP/1.0"))
			assertTrue("interleaved=0-1" in setup)
			val play = assertNotNull(requests.poll(2, TimeUnit.SECONDS))
			assertTrue(play.startsWith("PLAY $uri RTSP/1.0"))
			assertTrue("Session: 1234" in play)

			// The microphone's frames go out 20 ms at a time.
			channel.send(ShortArray(200) { 1 })
			channel.send(ShortArray(200) { 1 })
			val packet = assertNotNull(packets.poll(2, TimeUnit.SECONDS))
			assertEquals(10, channels.poll(), "on the channel the intercom chose, as a Dahua VTO does")
			val rtp = assertNotNull(RtpPacket.parse(packet, packet.size))
			assertEquals(97, rtp.payloadType)
			assertEquals(640, rtp.payloadLength)
			assertContentEquals(byteArrayOf(0, 1), packet.copyOfRange(rtp.payloadOffset, rtp.payloadOffset + 2))
			assertNull(packets.poll(200, TimeUnit.MILLISECONDS), "the rest waits for the next frame")
			channel.close()
			assertTrue(assertNotNull(requests.poll(2, TimeUnit.SECONDS)).startsWith("TEARDOWN"))
		} finally {
			server.close()
			vto.join(2000)
		}
	}

	@Test
	fun `an intercom without a backchannel fails without the URL in the error`() {
		val server = ServerSocket(0)
		val vto =
			Thread {
				server.accept().use { client ->
					val input = client.getInputStream().bufferedReader()
					while (input.readLine()?.isNotEmpty() == true) {
						// The request.
					}
					val body = dahua.replace("a=sendonly", "a=recvonly")
					client.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
				}
			}.apply { start() }
		try {
			val error = assertFailsWith<IOException> { RtspBackchannel.open("rtsp://admin:secret@127.0.0.1:${server.localPort}/x") }
			assertTrue("secret" !in error.message.orEmpty())
		} finally {
			server.close()
			vto.join(2000)
		}
	}
}
