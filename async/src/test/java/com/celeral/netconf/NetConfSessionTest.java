/*
 * Copyright © 2020 Celeral.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celeral.netconf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

public class NetConfSessionTest {
  public ByteBufferChannel getWriteFailingChannel() {
    return new ByteBufferChannel() {
      @Override
      public void write(ByteBufferProcessor producer) {
        throw new RuntimeException("WriteFailure");
      }

      @Override
      public void read(ByteBufferProcessor consumer) {
        new Thread(
                () -> {
                  try {
                    synchronized (this) {
                      this.wait(100);
                    }
                    consumer.process(
                        ByteBuffer.wrap("irrelevent response".getBytes(StandardCharsets.UTF_8)));
                    consumer.completed();
                  } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                  }
                })
            .start();
      }
    };
  }

  public ByteBufferChannel getReadFailingChannel() {
    return new ByteBufferChannel() {
      @Override
      public void write(ByteBufferProcessor producer) {
        new Thread(
                () -> {
                  try {
                    synchronized (this) {
                      this.wait(100);
                    }
                    producer.process(ByteBuffer.allocate(4096));
                    producer.completed();
                  } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                  }
                })
            .start();
      }

      @Override
      public void read(ByteBufferProcessor consumer) {
        throw new RuntimeException("ReadFailure");
      }
    };
  }

  public ByteBufferChannel getByteBufferChannel(
      Consumer<ByteBufferProcessor> write, Consumer<ByteBufferProcessor> read) {
    return new ByteBufferChannel() {
      @Override
      public void write(ByteBufferProcessor producer) {
        new Thread(
                () -> {
                  try {
                    synchronized (this) {
                      this.wait(100);
                    }
                    producer.process(ByteBuffer.allocate(4096));
                    write.accept(producer);
                  } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                  }
                })
            .start();
      }

      @Override
      public void read(ByteBufferProcessor consumer) {
        new Thread(
                () -> {
                  try {
                    synchronized (this) {
                      this.wait(100);
                    }
                    consumer.process(
                        ByteBuffer.wrap("irrelevent response".getBytes(StandardCharsets.UTF_8)));
                    read.accept(consumer);
                  } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                  }
                })
            .start();
      }
    };
  }

  @Test(timeout = 1000)
  public void testRequestReplySuccess() throws Throwable {
    NetConfSession session =
        new NetConfSession(
            getByteBufferChannel(
                producer -> producer.completed(), consumer -> consumer.completed()),
            StandardCharsets.UTF_8);
    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 200, 200, TimeUnit.MILLISECONDS);
    rpc.join();
  }

  @Test(expected = RequestTimeoutException.class, timeout = 1000)
  public void testRequestTimeout() throws Throwable {
    NetConfSession session =
        new NetConfSession(
            getByteBufferChannel(
                producer -> producer.completed(), consumer -> consumer.completed()),
            StandardCharsets.UTF_8);
    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 10, 100000000, TimeUnit.MICROSECONDS);
    try {
      rpc.join();
    } catch (CompletionException ex) {
      throw ex.getCause();
    }
  }

  @Test(expected = ResponseTimeoutException.class, timeout = 1000)
  public void testResponseTimeout() throws Throwable {
    NetConfSession session =
        new NetConfSession(
            getByteBufferChannel(
                producer -> producer.completed(), consumer -> consumer.completed()),
            StandardCharsets.UTF_8);
    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 100000000, 10, TimeUnit.MICROSECONDS);
    try {
      rpc.join();
    } catch (CompletionException ex) {
      throw ex.getCause();
    }
  }

  @Test(expected = RequestPhaseException.class, timeout = 1000)
  public void testRequestFailure() throws Throwable {
    NetConfSession session =
        new NetConfSession(
            getByteBufferChannel(
                producer -> producer.failed(new RuntimeException("request")),
                consumer -> consumer.completed()),
            StandardCharsets.UTF_8);

    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 100000000, 100000000, TimeUnit.MICROSECONDS);
    try {
      rpc.join();
    } catch (CompletionException ex) {
      throw ex.getCause();
    }
  }

  @Test(expected = ResponsePhaseException.class, timeout = 1000)
  public void testResponseFailure() throws Throwable {
    NetConfSession session =
        new NetConfSession(
            getByteBufferChannel(
                producer -> producer.completed(),
                consumer -> consumer.failed(new RuntimeException("response"))),
            StandardCharsets.UTF_8);

    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 100000000, 100000000, TimeUnit.MICROSECONDS);
    try {
      rpc.join();
    } catch (CompletionException ex) {
      throw ex.getCause();
    }
  }

  @Test(expected = RequestPhaseException.class, timeout = 1000)
  public void testRequestCallFailure() throws Throwable {
    NetConfSession session = new NetConfSession(getWriteFailingChannel(), StandardCharsets.UTF_8);

    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 100000000, 100000000, TimeUnit.MICROSECONDS);
    try {
      rpc.join();
    } catch (CompletionException ex) {
      throw ex.getCause();
    }
  }

  @Test(expected = ResponsePhaseException.class, timeout = 1000)
  public void testResponseCallFailure() throws Throwable {
    NetConfSession session = new NetConfSession(getReadFailingChannel(), StandardCharsets.UTF_8);

    CompletableFuture<String> rpc =
        session.rpc("irrelevent request", 100000000, 100000000, TimeUnit.MICROSECONDS);
    try {
      rpc.join();
    } catch (CompletionException ex) {
      throw ex.getCause();
    }
  }
}
