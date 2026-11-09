# PeerBlocks

[![CI](https://github.com/JoaoReis153/PeerBlocks/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/JoaoReis153/PeerBlocks/actions/workflows/ci.yml)

A peer-to-peer file-sharing app in Java. Nodes search each other's files by name, then download a file in blocks from every peer that has it, with no central server.

## How it works

1. Each node listens on port `8080 + id` and shares the files in its folder, `nodes/node-<id>/`.
2. You connect nodes to each other, then search a keyword. Every connected peer replies with its files whose name contains it.
3. Files are identified by their SHA-256 hash, so the same file on several peers is downloaded from all of them at once.
4. The file is split into blocks of up to 1 MB. The downloader keeps a few block requests waiting at each peer, so faster peers end up sending more blocks.
5. If a peer drops or stops answering, its blocks go to the other peers.
6. Blocks are written to a hidden `.part` file. When all have arrived, the hash is checked and the file is saved. A name that's already taken gets a number, as in `name (1).ext`.

## Requirements

- Java 21
- Nothing else: the Gradle wrapper is included.

## Running

Start a node. It asks for an id and opens a window:

```bash
./gradlew run
```

Start a second node in another terminal with a different id, then use **Connect to Node** to link them. The other node asks to accept the connection. To share files, put them in `nodes/node-<id>/` while the node runs. The folder is emptied when the node starts and deleted when it closes.

To open several connected nodes at once:

```bash
./gradlew demo --args="1 2 3"
```

Without ids it opens nodes 1, 2 and 3. The first node shares three generated sample files, so search in another window and download them.

## Tests

```bash
./gradlew build
```

This compiles the code, checks formatting (Spotless), and runs the tests. CI runs the same command on every push. To fix formatting:

```bash
./gradlew spotlessApply
```

## Benchmark

Measures download speed with 1 to N seeders, on loopback and in Docker with a limited network. With 100 Mbit/s per seeder, throughput grows from about 11 MB/s with 1 seeder to about 94 MB/s with 10.

```bash
./gradlew benchmark
benchmark/network/run.sh
```

Options, results and troubleshooting are in [benchmark/README.md](benchmark/README.md).

![Download speed vs number of seeders](benchmark/benchmark_graph.png)

## Project layout

```
src/main/java/dev/joaoreis/peerblocks/
├── App.java       starts one node with a window
├── core/          Node, peers, file index, settings (Config)
├── services/      connections and downloads
├── messaging/     block messages and the wire format
├── filesearch/    search messages and results
└── gui/           Swing windows
src/test/          JUnit tests
src/tools/         Demo and Benchmark launchers
benchmark/         results, graphs, the graph script and network/ (Docker benchmark)
```

Settings such as ports, block size, and timeouts are in [`Config.java`](src/main/java/dev/joaoreis/peerblocks/core/Config.java).

## License

MIT
