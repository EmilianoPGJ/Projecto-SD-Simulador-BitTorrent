# Projecto-SD-Simulador-BitTorrent
En este repositorio se ponen los archivos utilizado para la creación de la simulación BitTorrent en local, junto a las carpetas necesarias que deben estar creadas de ante mano para que funcione correctamente

El proyecto permite trabajar con un **Tracker** y múltiples **Peers**, donde los archivos se dividen en fragmentos que pueden descargarse y compartirse entre diferentes nodos.

El objetivo es simular de manera sencilla el funcionamiento básico de una red BitTorrent:

* Registro de Peers en un Tracker.
* Descubrimiento de otros Peers.
* Creación y lectura de archivos `.torrent`.
* División de archivos en piezas.
* Descarga simultánea de piezas.
* Compartición de piezas entre Peers.
* Recuperación de descargas interrumpidas.
* Verificación de piezas mediante SHA-1.
* Conversión de un Peer en Seeder al completar un archivo.
* Compartición de piezas parciales después de superar el 20 % de descarga.

---

# Estructura del proyecto

La estructura básica del proyecto es:

```text
BitTorrentSimulator/
│
├── Torrent.java
│
├── Tracker.java
├── Peer.java
│
├── torrents/
│
├── archivos/
│
├── peer_A/
│   ├── shared/
│   └── downloads/
│
├── peer_B/
│   ├── shared/
│   └── downloads/
│
└── peer_C/
    ├── shared/
    └── downloads/
```

Las carpetas de los Peers pueden cambiar dependiendo de la cantidad de nodos que se quieran ejecutar.

Por ejemplo:

```text
peer_A/
peer_B/
peer_C/
peer_D/
```

---

#  Carpetas necesarias

## `archivos/`

Contiene los archivos originales que se utilizarán para crear los torrents.

Ejemplo:

```text
archivos/
├── documento.txt
├── imagen.jpg
├── musica.mp3
└── video.mp4
```

Los archivos pueden ser de diferentes tipos.
---

## `torrents/`

Aquí se almacenan los archivos `.torrent` generados por `Torrent.java`.

Estos archivos contienen información necesaria para identificar y descargar el archivo original.

Entre los datos principales se encuentran:

* Nombre del archivo.
* Tamaño.
* Tamaño de cada pieza.
* Número de piezas.
* Hash SHA-1 de cada pieza.
* `infoHash`.
* Dirección del Tracker.

---

#  Archivos principales

## `Torrent.java`

Es el generador de archivos `.torrent`.

Su función principal es tomar un archivo de `archivos/` y dividirlo lógicamente en piezas.
Para cada pieza calcula un hash SHA-1.
Después genera el archivo:

```text
archivo.torrent
```
---

#  `Tracker.java`

El Tracker funciona como el punto de coordinación de la red.

No se encarga de transportar los archivos.

Su función principal es mantener información sobre los Peers y las piezas disponibles.

El Tracker registra:

```text
ID del Peer
IP
Puerto
Archivos compartidos
Piezas disponibles
```

Cuando un Peer necesita descargar un archivo, consulta al Tracker para saber qué otros Peers pueden proporcionar las piezas.

---

#  `Peer.java`

Es el programa principal de cada nodo de la red.

Un Peer puede funcionar simultáneamente como:

* Cliente.
* Servidor.
* Seeder.
* Leecher.



#  Comunicación entre los componentes

La comunicación básica es:

```text
                 ┌───────────┐
                 │  Tracker  │
                 └─────┬─────┘
                       │
              descubrimiento
                       │
          ┌────────────┼────────────┐
          │            │            │
       ┌──▼──┐      ┌──▼──┐      ┌──▼──┐
       │Peer A│◄────►│Peer B│◄────►│Peer C│
       └─────┘      └─────┘      └─────┘
          ▲            ▲            ▲
          └────────────┴────────────┘
                 transferencia
                    de piezas
```

El Tracker solamente ayuda a descubrir los Peers.

La transferencia de los archivos ocurre directamente entre los Peers.

---

#  Flujo general del programa

## Paso 1 — Crear el torrent

Primero se coloca un archivo en:

```text
archivos/
```

Después se ejecuta:

```text
java Torrent <IP_TRACKER> <PUERTO_TRACKER> <ARCHIVO>
```

Por ejemplo:

```text
java Torrent 127.0.0.1 5000 musica.mp3
```

Se genera:

```text
torrents/musica.mp3.torrent
```

---

## Paso 2 — Iniciar el Tracker

Se inicia el Tracker:

```text
java Tracker 5000
```

El Tracker queda esperando conexiones.

---

## Paso 3 — Iniciar los Peers

Cada Peer utiliza un puerto diferente.
 manera se pueden ejecutar varios Peers en diferentes ventanas de CMD o PowerShell.

---

#  Compartir un archivo

Si un Peer tiene un archivo completo en:

```text
peer_A/shared/
```

puede anunciarlo al Tracker.

El Tracker registra que ese Peer posee todas las piezas.

Este Peer es un:

```text
SEEDER
```

---

#  Descargar un archivo

Cuando otro Peer inicia una descarga:

```text
Peer B
   │
   ▼
Consulta Tracker
   │
   ▼
Obtiene Peers disponibles
   │
   ▼
Solicita piezas
   │
   ├────► Peer A
   │
   └────► Peer C
```

Las piezas se descargan y se almacenan en:

```text
peer_B/downloads/
```

---

#   Descarga concurrente

Las piezas pueden descargarse de manera concurrente.

Esto permite aprovechar diferentes fuentes al mismo tiempo.

El proyecto utiliza `ExecutorService` para administrar los hilos de descarga.

---

#  Recuperación de descargas

Si una descarga se interrumpe, no es necesario comenzar nuevamente.
El archivo `.pieces` indica qué fragmentos ya fueron completados.
Al continuar la descarga, las piezas anteriores se conservan y solamente se solicitan las faltantes.

---

#  Verificación de las piezas

Cada pieza tiene un hash SHA-1.

Cuando un Peer recibe una pieza:

```text
Pieza recibida
      │
      ▼
Calcula SHA-1
      │
      ▼
Compara con .torrent
      │
   ┌──┴──┐
   │     │
  OK   ERROR
   │     │
   ▼     ▼
Guarda  Rechaza
```

Si el hash no coincide, la pieza no se considera válida.

---
