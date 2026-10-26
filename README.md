# Sensi Coach (Android)

App mínima (sin AndroidX ni Compose, sin librerías) para dejar el teléfono listo para jugar.

**Qué hace:** No molestar en «solo alarmas» y brillo al 70 % (con tus permisos, y lo restaura al desactivar),
notificación con temperatura y temporizador de la rutina de 10 min (cuenta atrás del sistema, vibra al cambiar de paso)
y botones para abrir Free Fire, Free Fire MAX y CODM.

**También incluye:** un calibrador (DPI + resolución + arma → valores de partida para los deslizadores del juego), una arena de práctica dentro de la app que mide velocidad, aceleración, ángulo, muestreo y latencia toque→frame de TU arrastre, con tres modos (Flick: comprueba, reajusta, verifica y fija un multiplicador por arma y distancia (deshace el ajuste si no mejora); Umbral 90 %: método de escalera ponderada que encuentra el tamaño de cabeza con el que aciertas el 90 %; Seguimiento: tiempo en cabeza, error de altura y retraso sobre una cabeza en movimiento), con la tendencia medida como promedio móvil de tus sesiones, un HUD flotante opcional (mini panel para cambiar de arma; no lee ni toca el juego) y un ajuste opcional de resolución/densidad con root o ADB que se revierte solo si no confirmas.

**Qué NO hace, a propósito:** no usa Accesibilidad, no simula ni modifica toques, no lee la pantalla ni la memoria y no
modifica archivos del juego. Los valores de sensibilidad los escribes tú a mano en el juego.

## Compilar
- **Android Studio (PC):** File > Open > carpeta SensiCoach > Run. Para el APK: Build > Build APK(s).
- **Con GitHub:** crea un repositorio y sube el *contenido* de la carpeta SensiCoach (no la carpeta en sí: `.github` debe quedar en la raíz). En Actions > Build APK (arranca solo al subir, o Run workflow), y descarga `SensiCoach-debug`; dentro está `app-debug.apk`.

## Permisos que te pedirá
Notificaciones, acceso a No molestar, modificar ajustes del sistema y mostrar sobre otras apps (para el HUD). Se conceden en los Ajustes de Android al pulsar el botón correspondiente.

## Medir la RAM (objetivo: menos de 50 MB)
Con el teléfono por USB: `adb shell dumpsys meminfo app.sensicoach` y mira «TOTAL PSS». Con el servicio en segundo plano y la pantalla cerrada
debería ser bastante menor que con la pantalla abierta.
