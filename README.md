# ZAK Mercantil Prueba 0.2

Asistente Android independiente para probar el recorrido de compra de divisas
observado en el video del usuario. Se detiene en **Verifica tu operación**.
No existe una acción para pulsar Aceptar, Continuar o aceptar una declaración.

## Cambios de 0.2

- Observa cambios de pantalla con eventos de accesibilidad y un respaldo cada
  220 ms. Puede pasar al siguiente control tras 220 ms en vez de esperar siempre
  1,7 s; las repeticiones del mismo Comprar conservan una espera mayor.
- Reconoce la estructura del formulario aunque algunos campos estén fuera de
  la pantalla. Recuerda el origen y motivo ya seleccionados al desplazarse.
- Solicita mostrar los controles fuera de vista, usa el desplazamiento nativo
  y recurre a un gesto de desplazamiento limitado a la ventana activa de Mercantil.
- Recuerda qué lista desplegable está abierta y busca su opción sin reabrir el
  selector mientras se desplaza. Limita los desplazamientos y los intentos.
- Mantiene la parada en Verifica tu operación y ante la declaración jurada.

## Configuración acordada

- Monto editable; inicial 20.
- Moneda: USD o EUR.
- Cuentas: conserva las seleccionadas por Mercantil; no modifica sus selectores.
- Origen: Ahorros personales.
- Motivo: Ahorros.

## Primera prueba en el teléfono

1. Actualizar con `ZAK-Mercantil-Prueba-0.2.apk` sobre la versión anterior.
2. Abrir ZAK Mercantil Prueba y activar su servicio desde el botón de accesibilidad.
3. Abrir Mercantil e iniciar sesión directamente en el banco.
4. Volver al asistente, elegir monto y moneda y pulsar Probar recorrido.
5. Si el menú no se abre, tocar la flecha amarilla inferior de Mercantil; el
   asistente continúa al reconocer Mercado de divisas.
6. Revisar los datos en Verifica tu operación. El asistente se detiene y vibra.

Si Mercantil no expone un control a accesibilidad, el asistente se detiene y
muestra el último paso al volver a su pantalla. Los selectores se basan en las
etiquetas visibles del video; requieren comprobación en el teléfono real.

## Límites y protecciones de esta entrega

- Inicio de sesión manual en Mercantil. No pide ni guarda contraseñas.
- Sin permiso de Internet, Telegram, capturas, alarmas, arranque automático,
  receptores de órdenes externas ni lectura de otras aplicaciones.
- El servicio exige el permiso del sistema BIND_ACCESSIBILITY_SERVICE.
- El lanzador ignora extras de intent; abrirlo desde otra app no inicia un flujo.
- Comprueba el paquete de la ventana antes de leer y actuar.
- Registro en memoria con mensajes fijos; no guarda balances ni texto del banco.
- Se detiene ante errores y recibos, y limita duración e intentos por control.
- No reutiliza un formulario previo sin haber verificado el campo de monto.
- Compilación release con depuración desactivada; firma de prueba local.
- La referencia 0 del comprobante aportado no impide reconocer el mensaje de éxito.

## Compilar

JDK 17, SDK Android 34, Gradle 8.7. Definir `sdk.dir` en `local.properties` o
ANDROID_HOME. Ejecutar `./gradlew :app:testReleaseUnitTest :app:assembleRelease`.
El APK release sin firmar se firma después con `apksigner`. No se distribuyen
claves privadas en el proyecto.

Paquete de la app bancaria confirmado en su ficha de Google Play:
https://play.google.com/store/apps/details?id=com.mercantilbanco.mercantilmovil

No se ha conectado un teléfono ni ejecutado una operación bancaria desde este
entorno. Las pruebas unitarias comprueban decisiones del recorrido con textos
representativos del video; no sustituyen la prueba de accesibilidad en Mercantil.
