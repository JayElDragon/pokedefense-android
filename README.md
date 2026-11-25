# PokéDefense local para Android

## Instalar y jugar

1. Copia el APK y `defenseborne.zip` al teléfono e instala el APK. Android puede pedir permiso para instalar desde la aplicación con la que abriste el archivo.
2. Abre **PokéDefense local**, toca **Importar ZIP** y selecciona `defenseborne.zip` en el selector de archivos. También puedes importar directamente `pokedefense.zip`.
3. El juego se abre en horizontal al terminar la importación. La aplicación conserva una copia y el progreso local para los siguientes arranques. En dispositivos lentos, espera a que se preparen los mapas al abrir el juego.

Requiere Android 8.0 o posterior y un Android System WebView actualizado que pueda ejecutar el juego web. El APK contiene el visor. El código y los recursos del juego se leen de tu copia importada. Los servicios que requieren el servidor de PokéDefense, como las clasificaciones y el cooperativo en línea, no están disponibles.

## Rendimiento

Abre **Settings → Rendimiento** para elegir **Bajo** o **Alto**. Bajo se usa cuando todavía no hay una preferencia guardada. Usa mapas más sencillos y un fondo de menú estático, reduce los efectos y limita el dibujo de los combates a unas 30 imágenes por segundo. Alto recupera los efectos normales y deja que el juego dibuje al ritmo que permita el dispositivo. Los controles individuales de comodidad se conservan. Puedes cambiar de perfil durante una partida. Los efectos cambian al momento y el detalle del mapa se aplica al cargar el siguiente mapa. Tu elección se guarda para sesiones futuras.

## Compilar en Windows

Con JDK 17 en PATH, Android SDK Platform 35 y Android Build-Tools 35.0.0, abre PowerShell en esta carpeta y ejecuta:

```powershell
.\build.ps1
```

Usa `-Sdk` para indicar otra ubicación del SDK y `-Apk` para cambiar la salida. El script genera una clave de desarrollo en `.build` para las compilaciones locales. Conserva la misma clave para instalar futuras compilaciones como actualizaciones.

## Créditos

PokéDefense es el juego web original. Este repositorio contiene el código del port para Android. Se necesita una copia local del juego para usarlo. [PokéDefense](https://pokedefense.com/)
