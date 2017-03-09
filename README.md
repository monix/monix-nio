# monix-nio

Java NIO utilities for usage with Monix

[![Build Status](https://travis-ci.org/monix/monix-nio.svg?branch=master)](https://travis-ci.org/monix/monix-nio)
[![Coverage Status](https://codecov.io/gh/monix/monix-nio/coverage.svg?branch=master)](https://codecov.io/gh/monix/monix-nio?branch=master)


Join chat:
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/monix/monix?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## Overview
Monix-nio can be used to have the power of Monix combined with underlying Java-nio libraries.
For the moment the following support has been added:

- Read/Write async to a file (combined with utf8 encoding/decoding if necessary)
- Read/Write async to TCP

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
val from = java.nio.file.Paths.get(this.getClass.getResource("/myFile.txt").toURI)
monix.nio.file.readAsync(from, 30)
  .pipeThrough(monix.nio.text.UTF8Codec.utf8Decode)//decode utf8, If you need Array[Byte] just skip the decoding
  .foreach(Console.print(_))//print each char
```

### Write to a file

```scala
implicit val ctx = monix.execution.Scheduler.Implicits.global
val to = java.nio.file.Paths.get("/out.txt")
val bytes = "Test String".getBytes.grouped(3)
monix.reactive.Observable
  .fromIterator(bytes)
  .consumeWith(monix.nio.file.writeAsync(to))
  .runAsync
```

### Copy a file (text with decode and encode utf8)

```scala
val from = java.nio.file.Paths.get("from.txt")
val to = java.nio.file.Paths.get("to.txt")
val consumer = monix.nio.file.writeAsync(to)

val callback = new monix.eval.Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Copied $value bytes.")
  override def onError(ex: Throwable): Unit = println(ex)
}

monix.nio.file.readAsync(from, 3)
  .pipeThrough(monix.nio.text.UTF8Codec.utf8Decode)
  .map{ str =>
    Console.println(str) // do something with it
    str
  }
  .pipeThrough(monix.nio.text.UTF8Codec.utf8Encode)
  .consumeWith(consumer)
  .runAsync(callback)
```

### Read from TCP
```commandline
$ echo 'monix-tcp' | nc -l -k 9000
```
```scala
import monix.execution.Scheduler.Implicits.global

val callback = new monix.eval.Callback[Unit] {
  override def onSuccess(value: Unit): Unit = println("Completed")
  override def onError(ex: Throwable): Unit = println(ex)
}
    
val reader = monix.nio.tcp.readAsync("localhost", 9000)
reader
  .consumeWith(monix.reactive.Consumer.foreach(c => Console.out.print(new String(c))))
  .runAsync(callback)
```

### Write to TCP
```commandline
$ nc -l -k 9000
```
```scala
import monix.execution.Scheduler.Implicits.global

val callback = new monix.eval.Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Sent $value bytes")
  override def onError(ex: Throwable): Unit = println(ex)
}

val tcpConsumer = monix.nio.tcp.writeAsync("localhost", 9000)
val chunkSize = 2
monix.reactive.Observable
  .fromIterator("monix-tcp".getBytes.grouped(chunkSize))
  .consumeWith(tcpConsumer)
  .runAsync(callback)
```

### Make a raw HTTP request
```commandline
$ tail -f conn.txt | nc -l -k 9000 > conn.txt
```
```scala
import monix.execution.Scheduler.Implicits.global
  
val asyncTcpClient = 
  monix.nio.tcp.readWriteAsync("httpbin.org", 80) // or use localhost:9000
  
val callbackR = new monix.eval.Callback[Unit] {
  override def onSuccess(value: Unit): Unit = println("OK")
  override def onError(ex: Throwable): Unit = println(ex)
}
asyncTcpClient
  .tcpObservable
  .map { reader =>
    reader.subscribe(
      (bytes: Array[Byte]) => {
        println(new String(bytes, "UTF-8"))
        monix.execution.Ack.Stop // closes the socket
      },
      err => println(err),
      () => println("Completed"))
    ()
  }
  .runAsync(callbackR)
  
  
val request = 
  "GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n"
  
val callbackW = new monix.eval.Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Sent $value bytes")
  override def onError(ex: Throwable): Unit = println(ex)
}   
asyncTcpClient
  .tcpConsumer
  .flatMap { writer =>
    val data = request.getBytes("UTF-8").grouped(256 * 1024).toArray
    monix.reactive.Observable.fromIterable(data).consumeWith(writer)
  }
  .runAsync(callbackW)
```

## Maintainers

The current maintainers (people who can help you) are:

- Sorin Chiprian ([@creyer](https://github.com/creyer))
- Alexandru Nedelcu ([@alexandru](https://github.com/alexandru))
- Radu Gancea ([@radusw](https://github.com/radusw))

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

