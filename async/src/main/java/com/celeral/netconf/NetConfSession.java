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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tailf.jnc.Capabilities;
import com.tailf.jnc.Element;
import com.tailf.jnc.JNCException;
import com.tailf.jnc.NetconfSession;
import com.tailf.jnc.NodeSet;
import com.tailf.jnc.Transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.celeral.utils.Closeables;
import com.celeral.utils.Throwables;

public class NetConfSession extends NetconfSession {
  public static final String NETCONF_BASE_1_1_CAPABILITY =
      Capabilities.URN_IETF_PARAMS_NETCONF + "base:1.1";
  private final Charset charset;
  private MessageCodec<ByteBuffer> codec;

  /**
   * Creates a new session object using the given transport object. This will initialize the
   * transport and send out an initial hello message to the server.
   *
   * @see SSHSession
   * @param transport Transport object
   */
  private static class NetConfTransport implements Transport {
    final AtomicBoolean initialized = new AtomicBoolean();
    NetConfSession session;

    private void setNetConfSession(NetConfSession session) {
      if (initialized.get()) {
        throw Throwables.throwFormatted(
            IllegalStateException.class,
            "Attempt to reinitialize already initialized transport {} with session {}!",
            this,
            session);
      } else {
        synchronized (initialized) {
          this.session = session;
          initialized.notifyAll();
        }
      }
    }

    @Override
    public String readOne(long timeout, TimeUnit timeUnit) throws IOException {
      if (initialized.get() == false) {
        long start = System.nanoTime();
        try {
          synchronized (initialized) {
            initialized.wait(timeUnit.toMillis(timeout));
          }
        } catch (InterruptedException ex) {
          throw Throwables.throwFormatted(
              msg -> new IOException(msg, ex),
              "Initialization of transport interrupted after {}ns!",
              System.nanoTime() - start);
        }

        long elapsedNS = System.nanoTime() - start;
        if (session == null) {
          throw Throwables.throwFormatted(
              msg -> new IOException(msg),
              "Transport initialization timed out in {}ns!",
              elapsedNS);
        }

        initialized.set(true);
        timeout -= timeUnit.convert(elapsedNS, TimeUnit.NANOSECONDS);
      }

      long start = System.nanoTime();
      try {
        return session.response(timeout, timeUnit).get();
      } catch (InterruptedException ex) {
        throw Throwables.throwFormatted(
            msg -> new IOException(msg, ex),
            "Session {} interrupted after {}ns while waiting for response for {} {}!",
            session,
            System.nanoTime() - start,
            timeout,
            timeUnit);
      } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }

        throw Throwables.throwFormatted(
            msg -> new IOException(msg, cause),
            "Session {} failed to read response within {}ns with timeout {} {}",
            session,
            System.nanoTime() - start,
            timeout,
            timeUnit);
      }
    }
  }

  private final ByteArrayOutputStream outputStream;
  private ByteBufferChannel channel;

  public NetConfSession(ByteBufferChannel channel, Charset charset)
      throws JNCException, IOException {
    this(new ByteArrayOutputStream(4096), charset);
    this.channel = channel;
    initializeTransport();
  }

  private void initializeTransport() {
    ((NetConfTransport) super.in).setNetConfSession(this);
  }

  private NetConfSession(ByteArrayOutputStream stream, Charset charset)
      throws JNCException, IOException {
    super(new NetConfTransport(), new PrintStream(stream, false, charset));
    this.outputStream = stream;
    this.charset = charset;
    this.codec = new DefaultMessageCodec(charset);
  }

  abstract static class AbstractByteBufferProcessor<T> implements ByteBufferProcessor {
    protected final CompletableFuture<T> future;
    protected final long timeout;
    protected final TimeUnit timeUnit;

    AbstractByteBufferProcessor(CompletableFuture<T> future, long timeout, TimeUnit timeUnit) {
      this.future = future;
      this.timeout = timeout;
      this.timeUnit = timeUnit;
    }

    @Override
    public void failed(Throwable exc) {
      future.completeExceptionally(exc);
    }
  }

  /**
   * Since we send only one buffer at a time, we try to create less garbage by reusing the buffer as
   * much as possible.
   */
  private ByteBuffer requestBuffer = ByteBuffer.allocate(4096);

  final ConcurrentLinkedQueue<RequestByteBuffferProcessor> writes = new ConcurrentLinkedQueue<>();
  boolean writeInProgress;

  class RequestByteBuffferProcessor extends AbstractByteBufferProcessor<Void> {
    private final String string;

    RequestByteBuffferProcessor(
        String string, CompletableFuture<Void> future, long timeout, TimeUnit timeUnit) {
      super(future, timeout, timeUnit);
      this.string = string;
    }

    @Override
    public boolean process(ByteBuffer sendBuffer) {
      return !codec.encode(requestBuffer, sendBuffer);
    }

    @Override
    public void completed() {
      future.complete(null);

      RequestByteBuffferProcessor next;
      synchronized (writes) {
        next = writes.poll();
        if (next == null) {
          writeInProgress = false;
          return;
        }
      }

      scheduleWrite(next);
    }
  }

  private void scheduleWrite(RequestByteBuffferProcessor processor) {
    byte[] bytes = processor.string.getBytes(charset);
    if (requestBuffer.capacity() >= bytes.length) {
      requestBuffer.clear();
      requestBuffer.put(bytes);
      requestBuffer.flip();
    } else {
      requestBuffer = ByteBuffer.wrap(bytes);
    }

    channel.write(processor);
    processor.future.orTimeout(processor.timeout, processor.timeUnit);
  }

  private ByteBuffer decoded = ByteBuffer.allocate(4096);

  class ResponseByteBufferProcessor extends AbstractByteBufferProcessor<String> {
    ResponseByteBufferProcessor(CompletableFuture<String> future, long timeout, TimeUnit timeUnit) {
      super(future, timeout, timeUnit);
    }

    @Override
    public boolean process(ByteBuffer buffer) {
      if (codec.decode(buffer, decoded)) {
        return false;
      }

      if (!decoded.hasRemaining()) {
        decoded = ByteBuffer.allocate(decoded.capacity() << 1).put(decoded.flip());
      }

      return true;
    }

    @Override
    public void completed() {
      decoded.flip();
      future.complete(charset.decode(decoded).toString());
      decoded.clear();

      ResponseByteBufferProcessor processor;
      synchronized (reads) {
        processor = reads.poll();
        if (processor == null) {
          readInProgress = false;
          return;
        }
      }

      scheduleRead(processor);
    }
  }

  private void scheduleRead(ResponseByteBufferProcessor processor) {
    channel.read(processor);
    processor.future.orTimeout(processor.timeout, processor.timeUnit);
  }

  final ConcurrentLinkedQueue<ResponseByteBufferProcessor> reads = new ConcurrentLinkedQueue<>();
  boolean readInProgress;

  public CompletableFuture<Void> request(String request, long timeout, TimeUnit timeUnit) {
    CompletableFuture<Void> future = new CompletableFuture<>();

    try {
      RequestByteBuffferProcessor processor =
          new RequestByteBuffferProcessor(request, future, timeout, timeUnit);

      synchronized (writes) {
        if (writeInProgress) {
          writes.add(processor);
          return future;
        }

        writeInProgress = true;
      }

      scheduleWrite(processor);
    } catch (Throwable th) {
      future.completeExceptionally(th);
    }

    return future;
  }

  public CompletableFuture<String> response(long timeout, TimeUnit timeUnit) {
    CompletableFuture<String> future = new CompletableFuture<>();

    try {
      ResponseByteBufferProcessor processor =
          new ResponseByteBufferProcessor(future, timeout, timeUnit);

      synchronized (reads) {
        if (readInProgress) {
          reads.add(processor);
          return future;
        }

        readInProgress = true;
      }

      scheduleRead(processor);
    } catch (Throwable th) {
      future.completeExceptionally(th);
    }

    return future;
  }

  Element parseRPCReplyOk(String reply, int mid) {
    try {
      return super.recv_rpc_reply_ok(reply, Integer.toString(mid));
    } catch (JNCException ex) {
      throw new RuntimeException(ex);
    }
  }

  public CompletableFuture<Element> action(
      Element data, long requestTimeout, long responseTimeout, TimeUnit timeUnit)
      throws JNCException, IOException {
    int mid = super.encode_action(out, data);
    out.flush();
    try {
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(reply -> parseRPCReplyOk(reply, mid));
    } finally {
      outputStream.reset();
    }
  }

  public CompletableFuture<AutoCloseable> hello(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    setCapability(NETCONF_BASE_1_1_CAPABILITY);
    encode_hello(out);
    out.flush();
    try {
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(
              reply -> {
                AutoCloseable closeSession =
                    () -> close(requestTimeout, responseTimeout, timeUnit).join();
                try (Closeables closeables = new Closeables(closeSession)) {
                  establish_capabilities(reply);
                  if (capabilities.hasCapability(NETCONF_BASE_1_1_CAPABILITY)) {
                    codec = new ChunkedFramingMessageCodec();
                  }

                  closeables.protect();
                } catch (JNCException ex) {
                  throw new RuntimeException(ex);
                }

                return closeSession;
              });
    } finally {
      outputStream.reset();
    }
  }

  public CompletableFuture<Element> close(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    int mid = encode_closeSession(out);
    out.flush();
    try {
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(reply -> parseRPCReplyOk(reply, mid));
    } finally {
      outputStream.reset();
    }
  }

  public CompletableFuture<Element> kill(
      long sessionId, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    int mid = encode_killSession(out, sessionId);
    out.flush();
    try {
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(reply -> parseRPCReplyOk(reply, mid));
    } finally {
      outputStream.reset();
    }
  }

  public CompletableFuture<NodeSet> get(
      String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    if (!capabilities.hasXPath()) {
      return CompletableFuture.failedFuture(
          new JNCException(
              JNCException.SESSION_ERROR, "the :xpath capability is not supported by server"));
    }
    final int mid = encode_get(out, xpath);
    out.flush();
    try {
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(
              reply -> {
                try {
                  return parse_rpc_reply(parser, reply, Integer.toString(mid), "/data");
                } catch (JNCException ex) {
                  throw new RuntimeException(ex);
                }
              });
    } finally {
      outputStream.reset();
    }
  }

  // make sure that there is a handoff from caller thread to the common forkpool immediately after
  // rpc is called
  // and right before the return is called.
  public CompletableFuture<String> rpc(
      String request, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return CompletableFuture.supplyAsync(
            () ->
                request(request, requestTimeout, timeUnit)
                    .handleAsync(
                        (requestFuture, ex) -> {
                          if (ex == null) {
                            return requestFuture;
                          }

                          if (ex instanceof TimeoutException) {
                            throw new RequestTimeoutException(
                                requestTimeout, timeUnit, ex.getCause());
                          }

                          if (ex instanceof CompletionException) {
                            ex = ex.getCause();
                          }

                          throw new RequestPhaseException(ex);
                        })
                    .thenComposeAsync(
                        voids ->
                            response(responseTimeout, timeUnit)
                                .handle(
                                    (v, ex) -> {
                                      if (ex == null) {
                                        return v;
                                      }

                                      if (ex instanceof TimeoutException) {
                                        throw new ResponseTimeoutException(
                                            requestTimeout, timeUnit, ex.getCause());
                                      }

                                      if (ex instanceof CompletionException) {
                                        ex = ex.getCause();
                                      }

                                      throw new ResponsePhaseException(ex);
                                    })))
        .thenApply(cfs -> cfs.join());
  }

  private static final Logger logger = LogManager.getLogger();
}
