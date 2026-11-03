# Diseño de marc2d

Cómo debe verse y comportarse la app. El alcance está en [SPEC.md](SPEC.md); si un cambio contradice este
documento, se actualiza en el mismo commit.

## Principios

- **Es un mueble, no un móvil.** El panel está en la pared todo el día: no debe llamar la atención hasta
  que algo importe.
- **Se entiende de un vistazo y a distancia.** Letra grande, pocos elementos y botones anchos.
- **El OLED manda.** Fondo negro puro, y nada fijo en pantalla durante mucho tiempo.
- **Inspiración:** los sprites y las recreativas de los años 90, sin nostalgia decorativa.

## Color

| Uso                 | Color     | Valor     |
| ------------------- | --------- | --------- |
| Fondo               | negro     | `#000000` |
| Hora, cabeza        | blanco    | `#D8D8D8` |
| Fecha, base, textos | gris      | `#A0A0A0` |
| Aviso               | amarillo  | `#F0C020` |
| Peligro, grabación  | rojo      | `#E02020` |

El color significa algo: el amarillo y el rojo solo aparecen en un aviso o un peligro. Nada de color para
decorar. Los símbolos del tiempo van en monocromo, nunca como emojis de color.

## El droide

Es la mascota y el indicador de estado de la app, además de su icono. Es un sprite de 16×16 píxeles, con
cabeza blanca, base gris compacta y un ojo.

- **Se dibuja a píxeles:** sin suavizado, degradados ni rotaciones.
- **Se mueve a saltos:** un paso cada medio segundo, con dos fotogramas de base. Nada de animación fluida.
- **Ocupa el 10 % del ancho** de la pantalla y pasea por la franja de abajo.
- **Se queda donde esté** al cambiar de estado: el estado se lee en el ojo, no en la posición.
- **Dormido** no se mueve, salvo cada 20 minutos, cuando anda hasta otro sitio y vuelve a dormirse.
- **Es propio:** recuerda a un droide de recreativa, pero no debe parecerse a ningún personaje conocido.

Los estados y quién los activa están en [SPEC.md](SPEC.md#droide).

| Estado       | Ojo                                   | Movimiento      |
| ------------ | ------------------------------------- | --------------- |
| Paseo        | negro con brillo, mira hacia donde va | anda y parpadea |
| Durmiendo    | línea cerrada                         | quieto          |
| Sin conexión | una X                                 | quieto          |
| Alarma       | amarillo                              | parpadea        |
| Intruso      | franja roja                           | parpadea        |
| Grabando     | objetivo con centro rojo              | parpadea        |

## Botones

Cuadrados, de borde recto y fondo negro, con un icono de 12×12 píxeles, de la misma familia que el droide.
Nada de botones redondeados de Material. Solo se usa icono si se entiende sin texto: si no, se dibuja otro
mejor, o se añade una palabra. Son blancos, y el rojo queda para **Colgar**.

## Pantallas

- **Principal:** hora, fecha, tiempo y droide, sin botones. Tocar en cualquier sitio abre el menú.
- **Menú:** botones grandes sobre negro. Se cierra con la X, tocando fuera, y solo a los 10 segundos.
- **Ajustes:** fondo negro, campos de borde recto y botones de texto con la misma forma que los demás. Se
  cierra con la X.
- **Cámaras:** a pantalla completa, y se pasa de una a otra deslizando. Si una se puede llamar, muestra
  **Hablar**. Se sale con la X, tocando la imagen, y sola a los 2 minutos.
- **Llamada:** el vídeo ocupa toda la pantalla. **Colgar** nunca cambia de sitio y está en la esquina
  opuesta a **Descolgar**, para que una doble pulsación no corte la llamada.
- **Orientación:** siempre en horizontal.

## Texto

En castellano, con frases cortas y sin tecnicismos en pantalla: "Sin conexión con MQTT" es el límite. El
detalle técnico va en los ajustes, no en la pantalla principal.
