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
import monix.nio.text.UTF8Codec._
import monix.nio.file._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
val from = java.nio.file.Paths.get("/myFile.txt")
  
readAsync(from, 30)
  .pipeThrough(utf8Decode) // decode utf8, If you need Array[Byte] just skip the decoding
  .foreach(Console.print)  // print each char
```

### Write to a file

```scala
import monix.reactive.Observable
import monix.nio.file._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
val to = java.nio.file.Paths.get("/out.txt")
val bytes = "Hello world!".getBytes.grouped(3)
  
Observable
  .fromIterator(bytes)
  .consumeWith(writeAsync(to))
  .runAsync
```

### Copy a file (text with decode and encode utf8)

```scala
import monix.eval.Callback
import monix.nio.text.UTF8Codec._
import monix.nio.file._
  
val from = java.nio.file.Paths.get("from.txt")
val to = java.nio.file.Paths.get("to.txt")
  
val consumer = writeAsync(to)
  
val callback = new Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Copied $value bytes.")
  override def onError(ex: Throwable): Unit = println(ex)
}
readAsync(from, 3)
  .pipeThrough(utf8Decode)
  .map { str =>
    Console.println(str) // do something with it
    str
  }
  .pipeThrough(utf8Encode)
  .consumeWith(consumer)
  .runAsync(callback)
```

### File system watcher
```scala
import java.nio.file.{ Paths, WatchEvent }
import monix.nio.file._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
  
val path = Paths.get("/tmp")
  
def printEvent(event: WatchEvent[_]): Unit = {
  val name = event.context().toString
  val fullPath = path.resolve(name)
  println(s"${event.kind().name()} - $fullPath")
}
  
watchAsync(path)
  .foreach(p => p.foreach(printEvent))
```

### Read from TCP
```commandline
$ echo 'monix-tcp' | nc -l -k 9000
```
```scala
import monix.reactive.Consumer
import monix.nio.tcp._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
  
val callback = new monix.eval.Callback[Unit] {
  override def onSuccess(value: Unit): Unit = println("Completed")
  override def onError(ex: Throwable): Unit = println(ex)
}
readAsync("localhost", 9000)
  .consumeWith(Consumer.foreach(c => Console.out.print(new String(c))))
  .runAsync(callback)
```

### Write to TCP
```commandline
$ nc -l -k 9000
```
```scala
import monix.reactive.Observable
import monix.nio.tcp._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
  
val tcpConsumer = writeAsync("localhost", 9000)
val chunkSize = 2
  
val callback = new monix.eval.Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Sent $value bytes.")
  override def onError(ex: Throwable): Unit = println(ex)
}
Observable
  .fromIterator("Hello world!".getBytes.grouped(chunkSize))
  .consumeWith(tcpConsumer)
  .runAsync(callback)
```

### Create a TCP server and/or client (TCP server-client echo example)
```scala
import monix.reactive.Observable
import monix.eval. { Callback, Task }
import monix.execution.Ack.Continue
import monix.nio.tcp._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
  
val serverProgramT = for {
  server <- asyncServer(java.net.InetAddress.getByName(null).getHostName, 9001)
  socket <- server.accept()
  
  conn <- Task.now(readWriteAsync(socket))
  reader <- conn.tcpObservable
  writer <- conn.tcpConsumer
  
  echoedLen <- reader.doOnTerminateEval(_ => conn.stopWriting()).consumeWith(writer)
  _ <- conn.close()
  _ <- server.close()
} yield {
  echoedLen
}
  
val client = readWriteAsync("localhost", 9001, 256 * 1024)
val clientProgramT = for {
  writer <- client.tcpConsumer
  _ <- Observable.fromIterable(Array("Hello world!".getBytes())).consumeWith(writer)
  _ <- client.stopWriting()
  reader <- client.tcpObservable
  _ <- Task.now(reader
    .doOnTerminateEval(_ => client.close())
    .subscribe(
      bytes => { 
        println(new String(bytes))
        Continue 
      },
      err => println(err),
      () => println("Echo received.")
    ))
} yield {}
  
serverProgramT.runAsync(new Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Echoed $value bytes.")
  override def onError(ex: Throwable): Unit = println(ex)
})
clientProgramT.runAsync
```

### Make a raw HTTP request
```scala
import monix.reactive.Observable
import monix.eval.Callback
import monix.nio.tcp._
  
implicit val ctx = monix.execution.Scheduler.Implicits.global
  
val asyncTcpClient = readWriteAsync("httpbin.org", 80, 256 * 1024)
val request = 
  "GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n"
   
val callbackR = new Callback[Unit] {
  override def onSuccess(value: Unit): Unit = println("OK")
  override def onError(ex: Throwable): Unit = println(ex)
}
asyncTcpClient
  .tcpObservable
  .map { reader =>
    reader
    .doOnTerminateEval(_ => asyncTcpClient.close()) // clean
    .subscribe(
      (bytes: Array[Byte]) => {
        println(new String(bytes, "UTF-8"))
        monix.execution.Ack.Stop 
      },
      err => println(err),
      () => println("Completed")
    )
    ()
  }
  .runAsync(callbackR)
  
val callbackW = new Callback[Long] {
  override def onSuccess(value: Long): Unit = println(s"Sent $value bytes")
  override def onError(ex: Throwable): Unit = println(ex)
}   
asyncTcpClient
  .tcpConsumer
  .flatMap { writer =>
    val data = request.getBytes("UTF-8").grouped(256 * 1024).toArray
    Observable
      .fromIterable(data)
      .consumeWith(writer)
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

