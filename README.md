# netconf-async
[![Master Build Status](https://travis-ci.org/Celeral/netconf-async.svg?branch=master)](https://travis-ci.org/Celeral/netconf-async/branches)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.celeral/netconf-async/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.celeral%22%20AND%20a%3A%22netconf-async%22)
[![Javadoc](https://javadoc.io/badge/com.celeral/netconf-async.svg)](https://www.javadoc.io/doc/com.celeral/netconf-async)
[![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/https/oss.sonatype.org/com.celeral/netconf-async.svg)](https://oss.sonatype.org/#nexus-search;gav~com.celeral~netconf-async~~~)

Async interface to tailf's jnc (java netconf) library and netconf-1.1 extension

## Motivation
Working experience with Tailf's JNC has been excellent. It's one of the best client implementations I have known and have been using it exclusively to implement solutions at Cisco. Unfortunately, there has been lack of any kind of active development or support for JNC. JNC uses Ganymede SSH library underneath which has also not been updated since 2006 as of this writing.

On one hand, my first introduction to the issues the team faced with JNC was due to lack of support for both JNC and Ganymede. While working with it, I realized that it's not just lack of support for what's already implemented but the developers have been starving to get implementation for NetConf-1.1. Even the community effort to get that going has not been well received by JNC's governing team. For the last few years, there have been a few PR to the effect which have not been reviewed.

Therefore I decided to take the code from JNC published under Apache 2.0 license and restructure it so that I can easily support:
1. NetConf 1.1
1. Different Kinds of Transports such as SSH, TLS
1. Asynchronous API
1. Better error reporting to enable users write fault tolerant code

## Roadmap
1. Build active support community 
1. Evangelize wider adoption of netconf-async
1. Support TLS

## Contribute
1. Development help is needed to implement TLS Support - it's quite trivial due  abstraction but time/motivation is needed.
1. Support help is needed to fix reported issues - none so far.

## How to Get It
Add the dependency to your code:

### Maven

Functionality of this package is contained in
Java package `com.celeral.netconf`.

To use the package, you need to use following Maven dependency:

```xml
<properties>
  ...
  <!-- Use the latest version whenever possible. -->
  <celeral.version.netconf>1.1.0</celeral.version.netconf>
  ...
</properties>

<dependencies>
  ...
  <dependency>
    <groupId>com.celeral</groupId>
    <artifactId>netconf-async</artifactId>
    <version>${celeral.version.netconf}</version>
  </dependency>
  ...
</dependencies>
```

or download jars from [Central Maven repository](https://repo1.maven.org/maven2/com/celeral/netconf-async/).

### Non-Maven

For non-Maven use cases, you download jars from [Central Maven repository](https://repo1.maven.org/maven2/com/celeral/netconf-async/).

## Java Code Example

Then in your java code, you could use it as follows:

```java
  try (SSHSessionFactory factory = new SSHSessionFactory();
        // create a transport session - in this case SSH based!
        SSHSession session = factory.getSession(host, port, username, password, keypair)) {        

    try (SSHByteBufferChannel channel = session.getChannel(30, TimeUnit.SECONDS)) {
        NetConfSession netconf = new NetConfSession(channel, StandardCharsets.UTF_8);
        CompletableFuture<AutoCloseable> hello = netconf.hello(3000000, 3000000, TimeUnit.MICROSECONDS);
        try (AutoCloseable ac = hello.join()) {
          logger.info("session id = {}", netconf.sessionId);
          CompletableFuture<NodeSet> software = netconf.get("software", 30, 300, TimeUnit.SECONDS);
          
          // 
          // optionally do your stuff here while netconf is working asynchronously.
          // It will succeed, or fail with timeout or because of some other error.
          // 30 seconds timeout is for communicating the RPC to the server.
          // 300 seconds timeout is for the server to execute the RPC and respond back.
          //
          
          // when you can's proceed without the RPC result, join it.
          NodeSet result = software.join();
          
          //
          // result is usable here!
          //
        } // close netconf session
      } // close byte bufffer channel
      
      // as long as the transport session is alive, you can create multiple netconf sessions from it.
      try (SSHByteBufferChannel channel = session.getChannel(30, TimeUnit.SECONDS)) {
        NetConfSession netconf = new NetConfSession(channel, StandardCharsets.UTF_8);

        long start = System.nanoTime();

        CompletableFuture<AutoCloseable> hello = netconf.hello(3000000, 3000000, TimeUnit.MICROSECONDS);
        try (AutoCloseable ac = hello.join()) {
          CompletableFuture<NodeSet> software = netconf.get("software", 30, 30, TimeUnit.SECONDS);
          CompletableFuture<Element> reboot = netconf.action(getRebootAction(), 1, 30, TimeUnit.MINUTES);
          
          reboot.join();
                    
          // software is guaranteed to have completed if reboot.join() returns below as per the protocol,
          // with or without error. So the get below will return instantaneously.
          NodeSet result = software.get();

        } // close netconf session
        catch (CompletionException ex) {
          if (ex.getCause() instanceof RequestPhaseException) {
            //
            // you could go more granular with the actions to find out whether
            // netconf.get failed or netconf.action failed. But at this point
            // we are sure, whichever operation failed, it failed before the
            // request was completely transmitted to the server.
            //
          }
          else if (ex.getCause() instanceof ResponsePhaseException) {
            // 
            // here we know that the operation failed while waiting for the
            // response from the server, so server has most likely seen our request.
            //
          }          
        }
        finally {
          logger.info("passed time = {}", System.nanoTime() - start);
        }
      } // close the byte buffer channel
    } // close ssh transport session
```

Quite a few use cases require connecting to multiple devices but do not wish to wait for the operation to finish. A helper method to do a one of operations with a bunch of devices could look like follows:
```java
import com.celeral.utils.Closeables;
import com.celeral.utils.Throwables;

...

  /**
   * Creates a netconf session with deviceIP:830 using the given credentials and once the capabilties
   * are exchanged successfully, use the function to derive a future using the session. Teardown the
   * netconf session only after the future is completed. The exceptions encountered while setting up
   * the session are thrown by the method, the errors encountered while using the session are reported
   * via API exposed by the return value. Whereas any errors encountered after the returned future is
   * already complete are reported via the consumer passed as postComplete argument.
   */   
  public static <T> CompletableFuture<T> invokeNetConfRPC(String deviceIP, String username, String password, KeyPair keypair,
                                                          Function<NetConfSession, CompletableFuture<T>> function
                                                          Consumer<Throwable> postComplete) throws IOException, JNCException
  {
    CompletableFuture<T> retvalue;
    
    // we keep track of all the resources we allocate. if this try block ever throws
    // an exception, we free up the resources allocated immediately. If it completes
    // normally, we defer the freeing up the resources until we are done with the RPC
    // call.
    try (final Closeables closeables = new Closeables("Could not invoke rpc {} to device {}", function, deviceIP)) {
      SSHSessionFactory factory = new SSHSessionFactory();
      closeables.add(factory); // deffer
      SSHSession session = factory.getSession(deviceIP, 830, username, password, keypair);
      closeables.add(session); // deffer
      SSHByteBufferChannel channel = session.getChannel(30, SECONDS);
      closeables.add(channel); // defer
      final NetConfSession client = new NetConfSession(channel, StandardCharsets.UTF_8);
      CompletableFuture<AutoCloseable> hello = client.hello(30, 30, SECONDS);
      closeables.add(() -> { // defer
        // close only if we should!
        if (!hello.isCompletedExceptionally()) {
          hello.get().close();
        }
      });

      // schedule the rpc call if hello has succeeded, save the return value
      retvalue = hello.thenCompose(ac -> function.apply(client));
      
      // when rpc is done, tear down freeing all the deferred resource along the way
      retvalue.whenComplete((x, y) -> {
        try {
          synchronized (closeables) {
            closeables.expose();
            closeables.close();
          }
          postComplete.accept(null);
        } catch (Throwable th) {
          postComplete.accept(th);
        }
      });

      // we protect/export & close the resources in the synchronized block because
      // there is a possibility that futures are executing as we are scheduling them
      // we do not want the protect to kick in while we are closing the closeables.
      synchronized (closeables) {
        closeables.protect();
      }
    }

    // return the future we saved obtained by calling netconf rpc.
    return retvalue;
  }
```

Or for the purist in you, that would want to ensure that there are no blocking at all, you could use a little help from
the async class from the same utils library from where you used Closeables above. The Async interface provides a very
variations of the utility method which allows you to get try-with-resource like semantics easily so that you can write
clean async code without leaking resources.


```java
import static com.celeral.utils.Async.applyWith;
import static com.celeral.utils.Async.applyWithAsync;

...

  public static <T> CompletableFuture<T> invokeNetConfRPC(String deviceIP, String username, String password, KeyPair keypair,
                                                          Function<NetConfSession, CompletableFuture<T>> function)
  {
    return applyWith(SSHSessionFactory::new,
                factory -> applyWithAsync(() -> factory.getSession(deviceIP, 830, username, password, keypair),
                                session -> applyWith(() -> session.getChannel(30, SECONDS),
                                                channel -> {
                                                  final NetConfSession client = new NetConfSession(channel, StandardCharsets.UTF_8);
                                                  return applyWith(() -> client.hello(30, 30, SECONDS).join(),
                                                              hello -> function.apply(client),
                                                              hello -> hello.close());
                                                },
                                                channel -> channel.close()),
                                session -> session.close()),
                factory -> factory.close());
  }

```

And then from elsewhere in your code you could e.g. make a netconf get call against multiple devices at once:
```java
  for (String deviceIP : devices) {
    CompletableFuture<NodeSet> result = invokeNetConfRPC(deviceIP, username, password, keypair,
                                                         client -> client.get("version", 30, 90, SECONDS),
                                                         /* do not pass the following argument in the later flavor of invokeNetConfRPC */
                                                         th -> {
                                                           if (th != null) {
                                                             logger.warn("ignoring postresult for {}", deviceIP, th);
                                                           }
                                                         });
    map.put(deviceIP, result); // save the results for later tracking
  }
```

Also note that almost for all the calls on the `NetConfSession` object, the return type is `CompletableFuture`; It's intentionally that way so that you could use all the APIs available for `CompletableFuture` introduced in Java 8 and enhanced in Java 9 while using this library.

As a side note, the library used some features available only in Java 9, so I had to mandate use of Java 11 which is the next Long Term Support Java Release after Java 8.
