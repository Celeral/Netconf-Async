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
package com.celeral.netconf.ssh;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.RuntimeSshException;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.io.IoInputStream;
import org.apache.sshd.common.io.IoOutputStream;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

import com.celeral.utils.Closeables;

import com.celeral.netconf.ByteBufferChannel;
import com.celeral.netconf.ByteBufferProcessor;

public class SSHByteBufferChannel implements ByteBufferChannel, AutoCloseable {
  private static final int BUFFER_SIZE = 4096;
  private static final String NETCONF_SUBSYSTEM = "netconf";

  private final ClientChannel channel;
  private final IoInputStream in;
  private final IoOutputStream out;

  private final ByteArrayBuffer readByteArrayBuffer;
  private final ByteBuffer readByteBuffer;

  private final ByteArrayBuffer writeByteArrayBuffer;
  private final ByteBuffer writeByteBuffer;

  private final Closeables closeables;

  public SSHByteBufferChannel(ClientSession session, long connectTimeout, TimeUnit timeUnit)
      throws IOException {
    try (Closeables closes = new Closeables()) {
      channel = session.createChannel(Channel.CHANNEL_SUBSYSTEM, NETCONF_SUBSYSTEM);
      closes.add(channel);

      channel.setStreaming(ClientChannel.Streaming.Async);
      channel.open().verify(connectTimeout, timeUnit);

      in = channel.getAsyncOut();
      closes.add(in);

      out = channel.getAsyncIn();
      closes.add(out);

      closes.protect();
      closeables = closes;
    }
    closeables.expose();

    readByteArrayBuffer = new ByteArrayBuffer(BUFFER_SIZE);
    readByteBuffer = ByteBuffer.wrap(readByteArrayBuffer.array());
    readByteBuffer.limit(0);

    writeByteArrayBuffer = new ByteArrayBuffer(BUFFER_SIZE);
    writeByteBuffer = ByteBuffer.wrap(writeByteArrayBuffer.array());
  }

  @Override
  public void read(ByteBufferProcessor consumer) {
    if (readByteBuffer.hasRemaining()) {
      boolean callAgain;
      while (callAgain = consumer.process(readByteBuffer)) {
        if (!readByteBuffer.hasRemaining()) {
          break;
        }
      }

      if (!callAgain) {
        consumer.completed();
        return;
      }
    }

    in.read(readByteArrayBuffer)
        .addListener(
            future -> {
              try {
                int size = future.getRead();
                readByteBuffer.position(0).limit(size);
                readByteArrayBuffer.clear(false);
                read(consumer);
              } catch (RuntimeSshException ex) {
                consumer.failed(ex.getCause());
              } catch (Throwable th) {
                consumer.failed(th);
              }
            });
  }

  @Override
  @SuppressWarnings("UseSpecificCatch")
  public void write(ByteBufferProcessor producer) {
    boolean callAgain = producer.process(writeByteBuffer.clear());
    writeByteArrayBuffer.wpos(writeByteBuffer.position());
    try {
      out.writePacket(writeByteArrayBuffer)
          .addListener(
              future -> {
                writeByteArrayBuffer.rpos(0);
                if (future.isWritten()) {
                  if (callAgain) {
                    write(producer);
                  } else {
                    producer.completed();
                  }
                } else {
                  producer.failed(future.getException());
                }
              });
    } catch (Throwable th) {
      producer.failed(th);
    }
  }

  @Override
  public void close() {
    closeables.close();
  }

  private static final Logger logger = LogManager.getLogger();
}
