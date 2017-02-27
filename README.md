# monix-nio

Java NIO utilities for usage with Monix

??[![Build Status](https://travis-ci.org/monix/monix-nio.svg?branch=master)](https://travis-ci.org/monix/monix-nio)
??[![Coverage Status](https://codecov.io/gh/monix/monix-nio/coverage.svg?branch=master)](https://codecov.io/gh/monix/monix-nio?branch=master)


We can talk under the Monix Gitter:
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/monix/monix?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## Overview
Monix-nio can be used to have the power of Monix combined with underlying Java-nio libraries
For the moment the following support has been added:
- Read/Write async to a file (combined with utf8 encoding/decoding if necessary)

## Usage
### Read from a text file

```scala
  implicit val ctx = monix.execution.Scheduler.Implicits.global
  val from = Paths.get(this.getClass.getResource("/myFile.txt").toURI)
  monix.nio.file.readAsync(from, 30)
    .pipeThrough(utf8Decode)//decode utf8, If you need Array[Byte] just skip the decoding
    .foreach(Console.print(_))//print each char
```

### Write to a file
```scala
  implicit val ctx = monix.execution.Scheduler.Implicits.global
  val to = Paths.get("/out.txt")
  val bytes = "Test String".getBytes.grouped(3)
  Observable
    .fromIterator(bytes)
    .consumeWith(file.writeAsync(to))
    .runAsync
```
### Copy a file (text with decode and encode utf8)
```scala
    val from = Paths.get("from.txt")
    val to = Paths.get("to.txt")
    val consumer = file.writeAsync(to)
    readAsync(from, 3)
      .pipeThrough(utf8Decode)
      .map{ str =>
        Console.println(str) // do something with it
        str
      }
      .pipeThrough(utf8Encode)
      .consumeWith(consumer)
      .runAsync(callback)
```

The current maintainers (people who can help you) are:
- Sorin Chiprian ([@creyer](https://github.com/creyer))
- Alexandru Nedelcu ([@alexandru](https://github.com/alexandru))




