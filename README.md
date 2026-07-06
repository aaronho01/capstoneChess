# Capstone Chess

A chess engine built in Java, developed as a STEM Academy capstone project at Essex High School
between January 2023 and May 2025. The project spans 25,000+ lines of code and includes a
graphical interface and full chess rule enforcement.

## Features

- Complete rule implementation including castling, en passant, and pawn promotion
- AI opponent utilizing an alpha-beta algorithm with various optimizations including delta pruning and razoring
- Graphical interface built with Swing

## Requirements

JDK 25 or later

## Running the Project

Download the latest JAR from the [Releases](https://github.com/aaronho01/capstoneChess/releases) page and run:

```bash
java -jar CapstoneChess.jar
```

## Building from Source

```bash
javac -cp "lib/*" -d out src/engine/*.java src/engine/forBoard/*.java src/engine/forGUI/*.java src/engine/forPiece/*.java src/engine/forPlayer/*.java src/engine/forPlayer/forAI/*.java
jar cfm CapstoneChess.jar src/manifest.txt -C out .
java -jar CapstoneChess.jar
```

## Contact

For questions or suggestions, reach out at aaron012674@gmail.com.