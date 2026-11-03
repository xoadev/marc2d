# marc2d

**M**óvil **A**ndroid **R**eciclado **C**omo **C**onsola **D**omótica: MAR + C² + D.

App Android para un panel de pared: un móvil fijo, alimentado por PoE, que hace de consola de la
casa. No es la centralita: la lógica vive en Home Assistant (HA) y en los telefonillos, y el panel
se conecta a ellos y expone sus funciones:

- A Home Assistant, por su API WebSocket: muestra sus datos y atiende sus eventos.
- A los telefonillos, por SIP: atiende sus llamadas.
- Opcionalmente, ofrece su cámara a Frigate o a un sistema similar.

El diagrama de arquitectura está en el [README](README.md#arquitectura).

El proyecto está en pruebas y sin usuarios: se rompe lo que haga falta.

## Pantalla principal

- La hora en grande y la fecha.
- En la esquina superior derecha, el tiempo actual y la previsión de las próximas 8 horas, en blanco
  y gris, en una lista junto a la hora. Salen de la entidad
  `weather.*` que indica la configuración, no de un servicio propio. Se actualizan cada 10 minutos y
  se ocultan si el dato tiene más de una hora.
- Abajo, un aviso solo si algo falla: HA, el registro en un telefonillo o el permiso del micrófono.
- Tocar en cualquier sitio abre el menú, con **Cámaras** y **Ajustes**.

### Cámaras

Salen de `cameras` en la configuración, con su entidad `camera.*` de HA, que da el vídeo por MJPEG con el
token del panel, sin credenciales nuevas. Se pasa de una a otra deslizando, y solo se descarga la que se
ve. Si tiene `intercom`, muestra **Hablar**, que llama a ese telefonillo. Otro panel aparecerá aquí
cuando publique su cámara y esté en HA: hablar con él queda por decidir.

### Droide

Un sprite de 16×16 en blanco y negro pasea a saltos por la franja de abajo: evita quemar el OLED, y el
color del ojo solo aparece cuando algo importa. Al cambiar de estado se queda donde esté. Dormido, cada
20 minutos anda hasta otro sitio y vuelve a dormirse.

| Estado       | Ojo                  | Quién lo activa                                         |
| ------------ | -------------------- | ------------------------------------------------------- |
| Intruso      | franja roja          | HA, con `select.*_estado`                               |
| Alarma       | amarillo             | HA, con `select.*_estado`                               |
| Grabando     | objetivo rojo        | HA, con `select.*_estado`                               |
| Sin conexión | una X                | el panel, si hay algún aviso en pantalla                |
| Durmiendo    | cerrado              | el panel, por debajo de 5 lx                            |
| Paseo        | mira hacia donde va  | el panel, si no hay nada más                            |

El orden de la tabla es la prioridad. Lo que manda HA se retiene en MQTT: al reiniciarse, el panel
recupera la alarma que estuviera activa.

## Telefonillo

- El panel se registra como extensión SIP en el telefonillo, que hace de servidor SIP.
- HA detecta la pulsación con la integración de Dahua y publica `RING` en
  `marc2d/doorbell/<telefonillo>`, sin retener. Todos los paneles suenan, aunque la app esté en
  segundo plano, y dejan de sonar a los 45 segundos.
- Al descolgar, el panel publica `ANSWERED`, los demás dejan de sonar, y llama por SIP al número del
  telefonillo (`number`, por defecto `8001`). El VTO descuelga solo y da audio en los dos sentidos y
  vídeo. Si cae HA o Mosquitto, los paneles no suenan, pero DMSS sigue funcionando.
- También se atiende una llamada SIP entrante a la extensión.
- Desde su cámara, **Hablar** llama al telefonillo sin que hayan pulsado el timbre.
- Solo en la red de casa. Desde fuera, por Tailscale, el router de subred hace SNAT y el
  telefonillo no alcanza el `Contact` del panel.
- Fuera de esta versión: abrir la puerta.

### Dahua VTO

Comprobado contra un VTO2202F (UDP 5060), que por dentro es un Asterisk:

- La extensión `9901#1` se registra (`200 OK`) con la URI `sip:9901%231@<vto>`, con la
  `#` escapada, y el usuario de Digest `9901#1`, literal.
- El reto es `realm="asterisk"`, MD5 y sin `qop`.
- El botón llama al grupo `9901`, y ninguna extensión recibe ese `INVITE`: el servidor se queda en
  `100 Trying`. Las llamadas directas a una extensión sí llegan. No se cambia el número del botón
  para no arriesgar DMSS, que recibe el timbre por otra vía.
- El VTO acepta llamadas a `8001`: audio PCMU en los dos sentidos y vídeo H.264 hacia el panel. Justo
  tras una pulsación no contesta, mientras dura su propia llamada al grupo: el panel reintenta cada 2
  segundos, hasta 45.
- Un equipo que llega al VTO por la ruta de subred de Tailscale (`--accept-routes`) sale con SNAT
  y el resultado no vale: la prueba se hace desde la LAN directa.

## MQTT

El panel se conecta al broker que indica la configuración y se da de alta solo en HA, con el
autodescubrimiento de dispositivos (`homeassistant/device/marc2d_<panel>/config`). Si se cae, el
testamento de MQTT lo deja como `unavailable`. El telefonillo no depende de MQTT.

| Entidad                    | Dato                  | Cuándo se publica                 |
| -------------------------- | --------------------- | --------------------------------- |
| `sensor.*_luz`             | luz ambiente, en lux  | si cambia al menos 2 lx y un 20 % |
| `sensor.*_bateria`         | nivel, en %           | si cambia, se mira cada minuto    |
| `sensor.*_temperatura...`  | batería, en °C        | si cambia, se mira cada minuto    |
| `binary_sensor.*_cargando` | carga o no            | si cambia, se mira cada minuto    |
| `siren.*_sirena`           | enciende y apaga      | al cambiar                        |
| `select.*_estado`          | estado del droide     | al cambiar                        |

La sirena suena a todo volumen en el canal de alarma y se para sola a los 10 minutos, porque si se
pierde la conexión HA ya no podría apagarla.

## Altavoz

Falta: HA pedirá leer un mensaje en voz alta con el TTS del sistema, también por MQTT.

Comandos de voz: el panel es un satélite de Assist. Se activa con un botón en pantalla, manda el
audio del micrófono a la pipeline de HA (`assist_pipeline/run`) y reproduce la respuesta. La
palabra de activación queda fuera de esta versión.

## Presencia

El panel enciende la pantalla cuando el detector de presencia de su estancia ve a alguien, y la
apaga cuando deja de verlo. El detector es una entidad `binary_sensor.*` de HA que se elige en la
configuración; el panel no usa sus propios sensores.

## Cámara

El panel puede publicar su cámara como flujo RTSP en la red de casa, para que la consuma Frigate o
un sistema similar. Viene desactivado y se activa en la configuración. El panel solo publica el
vídeo: la detección y la grabación son cosa de quien lo consume. La autenticación del flujo está por
decidir.

## Dispositivo

- Una Pixel 3a y, después, una Pixel 4a, alimentadas por PoE y con LineageOS. `minSdk` es 35: la 3a
  lleva LineageOS 22.2, con Android 15.
- LineageOS va sin servicios de Google: la app no puede depender de ellos. Tampoco trae motor de
  TTS: `marc2d_say` necesita uno, que está por elegir.
- LineageOS bloquea la red de las apps nuevas si tiene activada la red restringida: el panel necesita
  **Permitir acceso a la red** en sus ajustes de Android.
- Enchufado, Android no entra en Doze: el servicio en primer plano basta para no perder llamadas.
- La app es la pantalla de inicio y arranca sola al encender el móvil, sin bloqueo de pantalla. Es
  ella quien lanza el servicio, de tipo `microphone`: Android 15 no deja arrancar ese tipo desde
  `BOOT_COMPLETED`. El servicio se reconecta solo a HA y a los telefonillos.
- Modo quiosco: la app es propietaria del dispositivo (Device Owner) y usa Lock Task, así que no se
  puede salir a otras apps. Desde la configuración se pueden abrir los ajustes de Android, y el
  quiosco vuelve al regresar, o salir de él por completo.
- El panel va siempre en horizontal, aunque se gire el móvil.
- Las dos pantallas son OLED: con la pantalla encendida, la hora se desplaza unos píxeles de vez
  en cuando para no quemarla.
- Se instala LineageOS, entre otras cosas, para limitar la carga de la batería al 80 %: siempre al
  100 % podría hincharse. Al llegar al límite, Android indica que no carga aunque esté alimentado: es
  normal que `binary_sensor.*_cargando` esté apagado. Aun así, se revisa de tanto en tanto.

## Configuración

En la app solo se configura la conexión con HA, la URL y un token de larga duración, y el nombre
del panel. Cada panel tiene su propio nombre, como `salon`, del que salen sus temas MQTT y sus
entidades. Todo lo demás
sale de HA, del script `script.marc2d_config`, que el panel llama al conectarse y cada 10 minutos y
que recibe el nombre del panel en la variable `panel` y devuelve:

```json
{
  "intercoms": [
    { "name": "Parking", "server": "192.0.2.10", "extension": "9901#1", "password": "…" }
  ],
  "weather": "weather.casa",
  "mqtt": { "server": "192.0.2.20", "port": 1883, "username": "…", "password": "…" },
  "cameras": [
    { "entity": "camera.portero", "name": "Parking", "intercom": "Parking" }
  ]
}
```

- Es un script porque se edita desde la interfaz de HA y la contraseña no acaba en una entidad, a la
  vista de todos los usuarios y en el historial. Sí aparece en las trazas del script, solo para
  administradores.
- Se guarda en el almacenamiento privado de la app, junto con la conexión, y nunca en el repo: el
  panel sigue atendiendo llamadas aunque HA esté caído.
- El detector de presencia y la publicación de la cámara se añadirán a esta respuesta en sus
  entregas.

## Orden de entrega

1. Hora, fecha, tiempo, modo quiosco y descolgar el telefonillo.
2. Mensajes y sirena.
3. Encender la pantalla con presencia.
4. Comandos de voz.
5. Publicar la cámara.

## Integración de Home Assistant

Prevista para cuando el telefonillo esté cerrado: una integración propia, instalable con HACS, en
`custom_components/marc2d/`. Sustituye al script de configuración y a MQTT, para que instalar marc2d no
exija Mosquitto ni escribir YAML.

- **Alta:** se añade el panel en HA, que muestra un QR con la URL y un código de un solo uso que caduca en
  minutos. El panel lo escanea y lo cambia por su propio token. El QR nunca lleva el token, y quitar el
  panel en HA revoca su acceso.
- **Configuración:** formularios por panel en HA: telefonillos, tiempo, cámaras y la entidad del botón del
  timbre.
- **Entidades:** propias, por el WebSocket que ya usa el panel. Si se cierra, el panel queda `unavailable`.
- **Por decidir:** el protocolo entre el panel y la integración, cómo leer el QR sin servicios de Google, y
  los requisitos de HACS para un repo que también contiene la app.

MQTT se queda hasta que la integración funcione en una casa real.

## Decisiones

- Kotlin y Jetpack Compose con Material 3.
- Se compila con Kotlin Toolchain, sin Gradle.
- Linter: ktlint con las reglas de Compose, mediante un plugin propio en `plugins/ktlint`, porque
  el Toolchain no trae ninguno. detekt queda fuera mientras la 2.0 siga en alfa. El Toolchain no
  ejecuta Android Lint.
- SIP con Linphone SDK. Es GPLv3: vale para uso propio; para darla a terceros hace falta su
  licencia comercial.
- HA con Ktor Client, motor OkHttp y kotlinx.serialization: WebSocket y JSON sobre corrutinas.
- MQTT con un cliente 3.1.1 propio sobre `Socket`: una sola conexión crea las entidades del panel,
  publica sus lecturas, recibe órdenes y avisa a HA si el panel se cae. Paho no publica versiones
  desde 2020, y HiveMQ arrastra Netty, cuyos `META-INF` duplicados no se pueden excluir con el
  Toolchain. El panel solo necesita QoS 0 al publicar y sin TLS, porque va por la LAN. Va detrás de
  la interfaz `MqttClient`, para poder sustituirlo por una librería tocando una sola clase.
- Configuración con Jetpack DataStore.
- RTSP con RootEncoder y RTSP-Server. Están mantenidas, pero por un solo autor y se publican en
  JitPack: la cámara es opcional y va detrás de una interfaz para poder sustituirlas.
- TTS, audio y pantalla con las API de Android, sin librerías.
- Sin framework de inyección de dependencias: el servicio crea y conecta las piezas a mano.
- Un servicio en primer plano mantiene el registro SIP y la conexión con HA, porque el telefonillo
  no manda push.
- Solo se usan versiones estables de librerías con publicaciones recientes. Las versiones están en
  `libs.versions.toml` y las librerías entran en el catálogo cuando se usan por primera vez.
