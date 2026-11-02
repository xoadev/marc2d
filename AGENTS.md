# Instrucciones para agentes

marc2d es una app Android que convierte un móvil fijo en la pared en la consola de la casa. Se conecta
a Home Assistant y a los telefonillos, y expone sus funciones. No es la centralita: si algo es lógica de
la casa, va en Home Assistant.

## Documentos

- `SPEC.md` es la fuente de verdad del alcance, el orden de entrega y las decisiones. Si un cambio la
  contradice, se actualiza en el mismo commit.
- `README.md` tiene el diagrama de arquitectura: se actualiza si cambian las piezas o sus conexiones.

## Ciclo de trabajo

Spec, cambio, `./kotlin check` y `./kotlin build -m app`, commit, PR. No se da nada por terminado sin
esos comandos en verde. Todo entra en `main` por PR, desde una rama propia.

```sh
./kotlin check               # ktlint, reglas de Compose y tests
./kotlin build -m app        # compila la APK
./kotlin do ktlintFormat     # corrige el formato
```

Se compila con Kotlin Toolchain: no se añade Gradle. La CI también ejecuta markdownlint con
`.markdownlint-cli2.yaml`.

## Convenciones

- **Kotlin:** tabuladores, comas finales y 160 columnas como máximo; el detalle está en `.editorconfig`.
- **Paquete:** todo el código cuelga de `io.x0a.marc2d`.
- **Documentación y comentarios:** en castellano. Los comentarios explican el porqué, no el qué.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`).
- **Linter:** se corrige la causa; desactivar una regla requiere un comentario con el motivo. Un cambio
  en `plugins/ktlint` se prueba con un fichero que incumpla las reglas: tiene que fallar.
- **Sin compatibilidad hacia atrás:** el proyecto no tiene usuarios, se rompe lo que haga falta.

## Dependencias

- Solo versiones estables de librerías con publicaciones recientes: nada de alfas, betas ni librerías
  abandonadas.
- Todas las versiones van en `libs.versions.toml`, y una librería entra cuando se usa por primera vez.
  La excepción son ktlint y sus reglas, que están en `plugins/ktlint/plugin.yaml`.
- Nada puede depender de los servicios de Google: los paneles llevan LineageOS sin ellos.
- Una librería nueva se justifica en `SPEC.md`, en Decisiones. Si basta la API de Android, no se añade.

## Secretos

Los tokens, las contraseñas y las direcciones de otras instalaciones se guardan en el almacenamiento
privado de la app, nunca en el repo, los logs ni los tests. Las copias de seguridad de Android están
desactivadas por este motivo: no se reactivan.

## Pruebas con dispositivos reales

- `adb` siempre con `-s <serie>`: puede haber otros móviles conectados, incluidos los personales. Hay
  que comprobar el modelo antes de instalar nada.
- Las pruebas contra un telefonillo se hacen desde la LAN directa. Por la ruta de subred de Tailscale
  sale con SNAT y el resultado no vale.
- Los detalles comprobados del VTO están en `SPEC.md`: no se cambian sin volver a comprobarlos contra
  el equipo.
- Sonar la sirena, hablar por los altavoces o llamar a servicios de Home Assistant afecta a una casa con
  gente dentro: se pide permiso antes de hacerlo.
