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

/** Lo que se puede hacer con una conexión abierta. No bloquea: se puede llamar desde el hilo principal. */
interface MqttSession {
	fun publish(
		topic: String,
		payload: String,
		retain: Boolean,
	)

	fun subscribe(topic: String)
}

/**
 * Lo único que el panel necesita de MQTT. Para cambiar de implementación, como una librería, basta
 * con otra clase que cumpla este contrato: nada fuera de ella conoce el protocolo.
 */
fun interface MqttClient {
	/**
	 * Abre una conexión con sesión limpia y dura lo que ella: termina con una excepción al perderla, y
	 * al cancelarla se corta sin despedirse, para que el broker publique el testamento.
	 */
	suspend fun run(
		options: MqttOptions,
		onConnected: (MqttSession) -> Unit,
		onMessage: (topic: String, payload: String) -> Unit,
	)
}
