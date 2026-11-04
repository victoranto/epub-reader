# EPUB Audiolibro (Android)

App sencilla para convertir un EPUB en audiolibro usando las voces del propio móvil (gratis y sin internet).

## Qué hace
- **Abrir EPUB**: desde el botón o con "Abrir con…" en el gestor de archivos.
- **Escuchar**: lee el libro capítulo a capítulo, con pausa, anterior/siguiente y velocidad (0.5x–2x). Toca un capítulo para empezar desde ahí.
- **Exportar**: genera un `.m4a` por capítulo (`01 - Título.m4a`, …) en una carpeta con el nombre del libro, dentro de la carpeta que elijas. Se puede escuchar luego en cualquier reproductor.

El idioma de la voz se toma del EPUB (`dc:language`). Para mejor calidad, en Ajustes → Sistema → Idioma → Salida de texto a voz, elige "Speech Services by Google" y descarga la voz en español de alta calidad.

## Compilar
- GitHub Actions compila el APK en cada push (pestaña *Actions* → último run → artefacto `epub-audiolibro-apk`).
- O en Android Studio: abrir la carpeta y darle a Run.

## Limitaciones (v0.1)
- La exportación se hace con la app abierta (la pantalla se mantiene encendida). Libros largos pueden tardar bastante.
- Las imágenes y notas al pie se ignoran.
