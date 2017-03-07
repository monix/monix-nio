# monix-nio

Java NIO utilities for usage with Monix

[![Build Status](https://travis-ci.org/monix/monix-nio.svg?branch=master)](https://travis-ci.org/monix/monix-nio)
[![Coverage Status](https://codecov.io/gh/monix/monix-nio/coverage.svg?branch=master)](https://codecov.io/gh/monix/monix-nio?branch=master)


Join chat:
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/monix/monix?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## Overview

Monix-nio can be used to have the power of Monix combined 
with underlying Java-nio libraries

For the moment the following support has been added:

- Read/Write async to a file (combined with utf8 encoding/decoding if necessary)

## Usage

**WARNING:** highly experimental, breaks backwards compatibility often, 
might have bugs, use with great care!

### Adding dependency to SBT

```scala
libraryDependencies += "io.monix" %% "monix-nio" % "0.0.1"
```

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

## Maintainers

The current maintainers (people who can help you) are:

- Sorin Chiprian ([@creyer](https://github.com/creyer))
- Alexandru Nedelcu ([@alexandru](https://github.com/alexandru))

## Contributing

The Monix project welcomes contributions from anybody wishing to
participate.  All code or documentation that is provided must be
licensed with the same license that Monix is licensed with (Apache
2.0, see LICENSE.txt).

People are expected to follow the
[Typelevel Code of Conduct](http://typelevel.org/conduct.html) when
discussing Monix on the Github page, Gitter channel, or other venues.

Feel free to open an issue if you notice a bug, have an idea for a
feature, or have a question about the code. Pull requests are also
gladly accepted. For more information, check out the
[contributor guide](CONTRIBUTING.md).

## License

All code in this repository is licensed under the Apache License,
Version 2.0.  See [LICENCE.txt](./LICENSE.txt).

