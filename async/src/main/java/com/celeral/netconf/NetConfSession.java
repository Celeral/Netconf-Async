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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.tailf.jnc.Capabilities;
import com.tailf.jnc.Element;
import com.tailf.jnc.InTransport;
import com.tailf.jnc.JNCException;
import com.tailf.jnc.NetconfSession;
import com.tailf.jnc.NodeSet;
import com.tailf.jnc.OutTransport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.celeral.utils.Closeables;
import com.celeral.utils.Throwables;

public class NetConfSession extends NetconfSession {
  public static final String NETCONF_BASE_1_1_CAPABILITY =
      Capabilities.URN_IETF_PARAMS_NETCONF + "base:1.1";

  private final ByteBufferChannel channel;
  private final Charset charset;
  private final ExecutorService executorService;

  final ProgressingQueue<Schedulable> reads;
  final ProgressingQueue<Schedulable> writes;

  /**
   * Since we send only one buffer at a time, we try to create less garbage by reusing the buffer as
   * much as possible.
   */
  private ByteBuffer requestBuffer;

  private ByteBuffer responseBuffer;

  private final ByteArrayOutputStream outputStream;
  private MessageCodec<ByteBuffer> codec;

  private static class NetConfTransport implements InTransport {
    final AtomicBoolean initialized = new AtomicBoolean();
    NetConfSession session;

    long timeout = 10;
    TimeUnit timeUnit = TimeUnit.MINUTES;

    public void setTimeout(long timeout, TimeUnit timeUnit) {
      this.timeout = timeout;
      this.timeUnit = timeUnit;
    }

    private void setNetConfSession(NetConfSession session) {
      if (initialized.get()) {
        throw Throwables.throwFormatted(
            IllegalStateException.class,
            "Attempt to reinitialize already initialized transport {} with session {}!",
            this,
            session);
      } else {
        synchronized (initialized) {
          initialized.set(true);
          this.session = session;
          initialized.notifyAll();
        }
      }
    }

    @Override
    public StringBuffer readOne() throws IOException {
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
        return new StringBuffer(session.response(timeout, timeUnit).get());
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

    @Override
    public boolean ready() throws IOException {
      throw new UnsupportedOperationException(
          "Not supported yet."); // To change body of generated methods, choose Tools | Templates.
    }
  }

  /**
   * Creates a new session object using the given ByteBuffer channel object. It initializes the
   * session with the ForkPool.commonPool() executor service. It uses Unlike the JNC's
   * NetConfSession constructor, this constructor does not invoke hello with the server
   * automatically.So one would need to make a call to it before initiating any rpc.
   *
   * @param channel ByteBuffer channel ready to communicate with remote server
   * @param charset Charset for converting the RPC request and response from String object
   * @throws com.tailf.jnc.JNCException passes the JNCException thrown by super class as it is
   */
  public NetConfSession(ByteBufferChannel channel, Charset charset) throws JNCException {
    this(channel, charset, ForkJoinPool.commonPool());
  }

  private void initializeTransport() {
    ((NetConfTransport) super.in).setNetConfSession(this);
  }

  private static class PrintStreamOutTransport extends PrintStream implements OutTransport {
    PrintStreamOutTransport(OutputStream stream, Charset charset) {
      super(stream, false, charset);
    }
  }

  /**
   * Creates a new session object using the given ByteBuffer channel object.It initializes the
   * session with the ForkPool.commonPool() executor service. It uses Unlike the JNC's
   * NetConfSession constructor, this constructor does not invoke hello with the server
   * automatically.So one would need to make a call to it before initiating any rpc.
   *
   * @param channel ByteBuffer channel ready to communicate with remote server
   * @param charset Charset for converting the RPC request and response from String object
   * @param executorService executor service for async operations
   * @throws com.tailf.jnc.JNCException passes the exception thrown by super class as it is
   */
  public NetConfSession(ByteBufferChannel channel, Charset charset, ExecutorService executorService)
      throws JNCException {
    super();
    this.executorService =
        Objects.requireNonNull(executorService, "executorService argument must be non-null!");
    this.charset = charset;
    this.channel = channel;

    this.outputStream = new ByteArrayOutputStream(4096);
    super.setOutTransport(new PrintStreamOutTransport(this.outputStream, charset));

    this.codec = new DefaultMessageCodec(charset);

    this.reads = new ProgressingQueue<>();
    this.responseBuffer = ByteBuffer.allocate(4096);
    this.writes = new ProgressingQueue<>();
    this.requestBuffer = ByteBuffer.allocate(4096);
  }

  private interface Schedulable {
    void schedule();
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
      future.completeExceptionally(new CompletionException(exc));
    }
  }

  private static class ProgressingQueue<T> {
    private boolean inProgress;
    private LinkedList<T> queue = new LinkedList<>();

    public synchronized T poll() {
      T t = queue.poll();
      if (t == null) {
        inProgress = false;
      }

      return t;
    }

    public synchronized boolean offer(T t) {
      if (inProgress) {
        return queue.offer(t);
      }

      inProgress = true;
      return false;
    }

    public synchronized boolean isInProgress() {
      return inProgress;
    }
  }

  class RequestByteBuffferProcessor extends AbstractByteBufferProcessor<Void>
      implements Schedulable {
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

      Schedulable processor = writes.poll();
      if (processor != null) {
        processor.schedule();
      }
    }

    @Override
    public void schedule() {
      byte[] bytes = string.getBytes(charset);
      if (requestBuffer.capacity() >= bytes.length) {
        requestBuffer.clear();
        requestBuffer.put(bytes);
        requestBuffer.flip();
      } else {
        requestBuffer = ByteBuffer.wrap(bytes);
      }

      channel.write(this);
      future.orTimeout(timeout, timeUnit);
    }
  }

  class ResponseByteBufferProcessor extends AbstractByteBufferProcessor<String>
      implements Schedulable {
    ResponseByteBufferProcessor(CompletableFuture<String> future, long timeout, TimeUnit timeUnit) {
      super(future, timeout, timeUnit);
    }

    @Override
    public boolean process(ByteBuffer buffer) {
      if (codec.decode(buffer, responseBuffer)) {
        return false;
      }

      if (!responseBuffer.hasRemaining()) {
        responseBuffer =
            ByteBuffer.allocate(responseBuffer.capacity() << 1).put(responseBuffer.flip());
      }

      return true;
    }

    @Override
    public void completed() {
      responseBuffer.flip();
      future.complete(charset.decode(responseBuffer).toString());
      responseBuffer.clear();

      Schedulable processor = reads.poll();
      if (processor != null) {
        processor.schedule();
      }
    }

    @Override
    public void schedule() {
      channel.read(this);
      future.orTimeout(timeout, timeUnit);
    }
  }

  static <T> CompletableFuture<T> enqueueOrSchedule(
      Function<CompletableFuture<T>, Schedulable> function, ProgressingQueue<Schedulable> queue) {
    CompletableFuture<T> future = new CompletableFuture<>();

    try {
      Schedulable schedulable = function.apply(future);
      if (!queue.offer(schedulable)) {
        schedulable.schedule();
      }
    } catch (Throwable th) {
      future.completeExceptionally(new CompletionException(th));
    }

    return future;
  }

  /**
   * Send the request to the netconf server.
   *
   * <p>request method support one of the two fundamental building phases of the RPC operation with
   * the server. The other phase being the response phase. Any failure during the request phase is
   * communicated to the caller as the RequestPhaseException. RequestTimeoutException is a subclass
   * of the RequestPhaseException and when raised signifies failure to successfully complete the
   * request phase within the given timeout period.
   *
   * @param request the request payload to send to the server
   * @param timeout the timeout for the request operation to be finished
   * @param timeUnit the unit for the timeout value
   * @return the future which communicates success or the RequestPhaseException upon failure
   */
  public CompletableFuture<Void> request(String request, long timeout, TimeUnit timeUnit) {
    return NetConfSession.enqueueOrSchedule(
        future -> new RequestByteBuffferProcessor(request, future, timeout, timeUnit), writes);
  }

  /**
   * Receive a response from the netconf server.
   *
   * <p>response method implements one of the two fundamental building phases of the RPC operation
   * with the server. The other phase being the request phase. Any failure during the response phase
   * is communicated to the caller as the ResponsePhaseException. ResponseTimeoutException is a
   * subclass of the ResponsePhaseException and when raised signifies failure to successfully
   * complete the response phase within the given timeout period. The response timeout period is
   * cumulative of the time it takes the server to prepare the response and the time it takes for
   * the client to receive after server transmits it.
   *
   * @param timeout the timeout for the response operation to be finished
   * @param timeUnit the unit for the timeout value
   * @return the future which communicates success with the response string or the
   *     ResponsePhaseException upon failure
   */
  public CompletableFuture<String> response(long timeout, TimeUnit timeUnit) {
    return NetConfSession.enqueueOrSchedule(
        future -> new ResponseByteBufferProcessor(future, timeout, timeUnit), reads);
  }

  public CompletableFuture<Element> readReply(long timeout, TimeUnit timeUnit) {
    return response(timeout, timeUnit)
        .thenApply(
            reply -> {
              try {
                return parser.parse(reply);
              } catch (JNCException ex) {
                throw new RuntimeException(ex);
              }
            });
  }

  public CompletableFuture<Element> receiveNotification(long timeout, TimeUnit timeUnit) {
    return response(timeout, timeUnit)
        .thenApply(
            reply -> {
              try {
                Element t = receive_notification_parse(reply);
                return receive_notification_post_process(t);
              } catch (JNCException ex) {
                throw new RuntimeException(ex);
              }
            });
  }

  public CompletableFuture<Element> action(
      Element data, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> action_request(data),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
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
              },
              executorService);
    } finally {
      outputStream.reset();
    }
  }

  @FunctionalInterface
  static interface RequestFunction {
    int get() throws Exception;
  }

  @FunctionalInterface
  static interface ReplyFunction<T> {
    T apply(String reply, int mid) throws Exception;
  }

  private <T> CompletableFuture<T> rpc_request_reponse(
      RequestFunction supplier,
      ReplyFunction<T> function,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    try {
      int mid = supplier.get();
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(
              reply -> {
                try {
                  return function.apply(reply, mid);
                } catch (Exception ex) {
                  throw new ResponseConsumptionException(ex);
                }
              },
              executorService);
    } catch (Exception ex) {
      return CompletableFuture.failedFuture(new RequestGenerationException(ex));
    } finally {
      outputStream.reset();
    }
  }

  public CompletableFuture<Element> close(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> close_session_request(),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> commit(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> commit_request(),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> confirmedCommit(
      int confirmationTimeoutInSeconds,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> confirmed_commit_request(confirmationTimeoutInSeconds),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      String sourceUrl, int target, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceUrl, target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      String sourceUrl,
      String targetUrl,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceUrl, targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      int source, String targetUrl, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(source, targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      int source, int target, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(source, target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      NodeSet sourceTrees,
      String targetUrl,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceTrees, targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      NodeSet sourceTrees,
      int target,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceTrees, target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      Element sourceTree,
      int target,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(new NodeSet(sourceTree), target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      Element sourceTree,
      String targetUrl,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(new NodeSet(sourceTree), targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> kill(
      long sessionId, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> kill_session_request(sessionId),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      String streamName,
      String eventFilter,
      String startTime,
      String stopTime,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(streamName, eventFilter, startTime, stopTime),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      String streamName,
      NodeSet eventFilter,
      String startTime,
      String stopTime,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(streamName, eventFilter, startTime, stopTime),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      String streamName, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(streamName, (String) null, null, null),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(null, (String) null, null, null),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> deleteConfig(
      int dataStore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> delete_config_request(dataStore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> unlock(
      int dataStore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> unlock_request(dataStore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> unlockPartial(
      int lockId, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> unlock_partial_request(lockId),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> validate(
      int datastore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> validate_request(datastore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> validate(
      String url, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> validate_request(url),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> validate(
      Element configTree, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> validate_request(configTree),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> deleteConfig(
      String targetURL, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> delete_config_request(targetURL),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> discardChanges(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> discard_changes_request(),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> editConfig(
      int datastore,
      Element configTree,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> edit_config_request(datastore, configTree),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> editConfig(
      int datastore,
      NodeSet configTrees,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> edit_config_request(datastore, configTrees),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> editConfig(
      int datastore, String url, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> edit_config_request(datastore, url),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> get(
      String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_request(xpath),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> get(
      Element subtreeFilter, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_request(subtreeFilter),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      int datastore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(datastore),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(RUNNING),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      int datastore,
      Element subtreeFilter,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(datastore, subtreeFilter),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      int datastore, String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(datastore, xpath),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(RUNNING, xpath),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getStreams(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_request(get_streams_filter()),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> lock(
      int dataStore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> lock_request(dataStore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Integer> lockPartial(
      String[] select, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> lock_partial_request(select),
        (reply, mid) ->
            lock_partial_post_process(
                parse_rpc_reply(parser, reply, Integer.toString(mid), "/data")),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Integer> lockPartial(
      String select, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return lockPartial(new String[] {select}, requestTimeout, responseTimeout, timeUnit);
  }

  /**
   * Make a netconf RPC call using the request and the return the future which completes when the
   * response is ready.
   *
   * <p>This call is the foundational implementation for all the netconf RPC messages
   * implementation. Except for submitting the job to the underlying executor service, that is
   * executed in the caller's thread, all the operations are executed by the executor service. The
   * only exception to this could be the IO that's executed by the implementations of the
   * ByteBufferChannel and ByteBufferProcessor.
   *
   * <p>Note that care has been taken to ensure that the return completable is either successful or
   * fails with either the RequestPhaseException or ResponsePhaseException. Failure with
   * RequestPhaseException signifies that the request was not transmitted in its entirety to the
   * server. Whereas failure with ResponsePhaseException signifies that the server most likely saw
   * the request and the error happened while we were either waiting for a response, or receiving a
   * response, or parsing the response received. Appropriate root cause may be deduced by looking at
   * the root cause of the thrown exception. RequestTimeoutExceptions and ResponseTimeoutExceptions
   * are subclasses of RequestPhaseException and ResponsePhaseException respectively and signify the
   * respective operations could not be validated to have finished within the timeout values passed
   * to the method.
   *
   * @param request the netconf request to be sent as is to the netconf server
   * @param requestTimeout timeout value to be used in conjunction with timeUnit to send the request
   * @param responseTimeout timeout value to be used in conjunction with timeUnit to wait for the
   *     response
   * @param timeUnit timeunit to be used for requestTimeout and responseTimeout
   * @return Future which holds the response from the netconf server or failure reason
   */
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
                        },
                        executorService)
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
                                    }),
                        executorService),
            executorService)
        .thenApply(cfs -> cfs.join());
  }

  private static final Logger logger = LogManager.getLogger();
}
