# Quasar [![Build Status](https://travis-ci.org/puniverse/quasar.png?branch=master)](https://travis-ci.org/puniverse/quasar) <br/>Lightweight threads and actors for the JVM


**NOTE: This is alpha software**

## Getting started

In Maven:

```xml
<dependency>
    <groupId>co.paralleluniverse</groupId>
    <artifactId>quasar-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

For clustering support add:

```xml
<dependency>
    <groupId>co.paralleluniverse</groupId>
    <artifactId>quasar-galaxy</artifactId>
    <version>0.2.0</version>
</dependency>
```

Or, build from sources by running:

```
./gradlew
```

## Usage

Currently, there isn’t much in the way of documentation (coming soon!).
In the meantime, you can study the examples [here](https://github.com/puniverse/quasar/tree/master/quasar-core/src/test/java/co/paralleluniverse/actors).

You can also read the introductory [blog post](http://blog.paralleluniverse.co/post/49445260575/quasar-pulsar).

When running code that uses Quasar, the instrumentation agent must be run by adding this to the `java` command line:

```
-javaagent:path-to-quasar-jar.jar
```

## Running Distributed Examples

There are a few examples for distributed actors in the [example package](https://github.com/puniverse/quasar/tree/master/quasar-galaxy/src/main/java/co/paralleluniverse/galaxy/example).
You can run them after downloading the source. In order to run the ping pong example, start the Pong actor by:
```
./gradlew :quasar-galaxy:run -PmainClass=co.paralleluniverse.galaxy.example.pingpong.Pong
```
Start the Ping actor in different terminal by:
```
./gradlew :quasar-galaxy:run -PmainClass=co.paralleluniverse.galaxy.example.pingpong.Ping
```
In order to run the actors in different computer change the following lines in the build.gradle file to apropriate network configuration:
```
        systemProperty "jgroups.bind_addr", "127.0.0.1"
        systemProperty "galaxy.multicast.address", "225.0.0.1"
```
In a similar way you can run the other examples in co.paralleluniverse.galaxy.example.simpleGenEvent and co.paralleluniverse.galaxy.example.simpleGenServer packages.

## Getting help

Questions and suggestions are welcome at this [forum/mailing list](https://groups.google.com/forum/?fromgroups#!forum/quasar-pulsar-user).

## License 

Quasar is free software published under the following license:

```
Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.

This program and the accompanying materials are dual-licensed under
either the terms of the Eclipse Public License v1.0 as published by
the Eclipse Foundation
 
  or (per the licensee's choosing)
 
under the terms of the GNU Lesser General Public License version 3.0
as published by the Free Software Foundation.
```
