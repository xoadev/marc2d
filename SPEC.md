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

- Hora y tiempo. El tiempo sale de una entidad `weather.*` de HA, no de un servicio propio.

## Telefonillo

- El panel se registra como extensión SIP en el telefonillo, que hace de servidor SIP.
- Cuando llaman, suena aunque la app esté en segundo plano. Se descuelga con audio en los dos
  sentidos y vídeo del portero, y se cuelga.
- Solo en la red de casa. Desde fuera, por Tailscale, el router de subred hace SNAT y el
  telefonillo no alcanza el `Contact` del panel.
- Fuera de esta versión: abrir la puerta.

### Dahua VTO

Comprobado contra el VTO del parking (`192.168.1.108`, UDP 5060), que por dentro es un Asterisk:

- La extensión `9901#1` se registra (`200 OK`) con la URI `sip:9901%231@192.168.1.108`, con la
  `#` escapada, y el usuario de Digest `9901#1`, literal.
- El reto es `realm="asterisk"`, MD5 y sin `qop`.
- Un equipo que llega al VTO por la ruta de subred de Tailscale (`--accept-routes`) sale con SNAT
  y el resultado no vale: la prueba se hace desde la LAN directa.

## Altavoz

HA habla al panel con eventos de su bus, que el panel escucha por WebSocket:

| Evento         | Datos              | Qué hace el panel                                          |
| -------------- | ------------------ | ---------------------------------------------------------- |
| `marc2d_say`   | `message`          | lo lee en voz alta con el TTS del sistema                  |
| `marc2d_siren` | `on`: `true/false` | arranca o para la sirena, a todo volumen y en canal alarma |

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
- Enchufado, Android no entra en Doze: el servicio en primer plano basta para no perder llamadas.
- El servicio arranca al encender el móvil y se reconecta solo a HA y a los telefonillos. La
  pantalla principal avisa cuando alguna conexión está caída. Android 15 limita qué tipos de
  servicio en primer plano pueden arrancar con `BOOT_COMPLETED`: hay que comprobarlo en la primera
  entrega.
- Las dos pantallas son OLED: con la pantalla encendida, la hora se desplaza unos píxeles de vez
  en cuando para no quemarla.
- Siempre al 100 %, la batería puede hincharse: se revisa de tanto en tanto.

## Configuración

En la app: URL y token de larga duración de HA, el detector de presencia, servidor, extensión y
contraseña de cada telefonillo, y si se publica la cámara. Se guardan en el almacenamiento privado de la app, nunca en el
repo.

## Orden de entrega

1. Descolgar el telefonillo.
2. Hora y tiempo.
3. Mensajes y sirena.
4. Encender la pantalla con presencia.
5. Comandos de voz.
6. Publicar la cámara.

## Decisiones

- Kotlin y Jetpack Compose con Material 3.
- Se compila con Kotlin Toolchain, sin Gradle.
- Linter: ktlint con las reglas de Compose, mediante un plugin propio en `plugins/ktlint`, porque
  el Toolchain no trae ninguno. detekt queda fuera mientras la 2.0 siga en alfa. El Toolchain no
  ejecuta Android Lint.
- SIP con Linphone SDK. Es GPLv3: vale para uso propio; para darla a terceros hace falta su
  licencia comercial.
- HA con Ktor Client, motor OkHttp y kotlinx.serialization: WebSocket y JSON sobre corrutinas.
- Configuración con Jetpack DataStore.
- RTSP con RootEncoder y RTSP-Server. Están mantenidas, pero por un solo autor y se publican en
  JitPack: la cámara es opcional y va detrás de una interfaz para poder sustituirlas.
- TTS, audio y pantalla con las API de Android, sin librerías.
- Sin framework de inyección de dependencias: el servicio crea y conecta las piezas a mano.
- Un servicio en primer plano mantiene el registro SIP y la conexión con HA, porque el telefonillo
  no manda push.
- Solo se usan versiones estables de librerías con publicaciones recientes. Las versiones están en
  `libs.versions.toml` y las librerías entran en el catálogo cuando se usan por primera vez.
