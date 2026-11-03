package dev.xoa.marc2d

data class MqttWill(
	val topic: String,
	val payload: String,
)

data class MqttOptions(
	val server: String,
	val port: Int,
	val clientId: String,
	val username: String,
	val password: String,
	val will: MqttWill,
) {
	override fun toString(): String = "MqttOptions(server=$server, port=$port, clientId=$clientId, username=$username)"
}

/** What can be done with an open connection. It does not block: it can be called from the main thread. */
interface MqttSession {
	fun publish(
		topic: String,
		payload: String,
		retain: Boolean,
	)

	fun subscribe(topic: String)
}

/**
 * All the panel needs from MQTT. To change the implementation, for example to a library, all it takes
 * is another class that meets this contract: nothing outside it knows the protocol.
 */
fun interface MqttClient {
	/**
	 * Opens a clean-session connection and lasts as long as it does: it ends with an exception when the connection is lost, and
	 * on cancellation it cuts off without saying goodbye, so that the broker publishes the last will.
	 */
	suspend fun run(
		options: MqttOptions,
		onConnected: (MqttSession) -> Unit,
		onMessage: (topic: String, payload: String) -> Unit,
	)
}
