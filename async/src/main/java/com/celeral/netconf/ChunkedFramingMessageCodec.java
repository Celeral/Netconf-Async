/*
 * Copyright © 2020 Celeral.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celeral.netconf;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.celeral.utils.Throwables;

public class ChunkedFramingMessageCodec implements MessageCodec<ByteBuffer> {
  private static final byte LF = 0x0A;
  private static final byte HASH = 0x23;
  private static final byte DIGIT_0 = 0x30;
  private static final byte DIGIT_1 = 0x31;
  private static final byte DIGIT_9 = 0x39;
  long size;
  int readChunkCount;
  int chunkHeaderIndex;
  final byte[] chunkHeaderBytes = new byte[12];

  int writtenChunkCount;

  @Override
  public boolean decode(ByteBuffer from, ByteBuffer to) {
    if (size > 0) {
      final int fromLimit = from.limit();
      int minRemaining = Math.min(from.remaining(), to.remaining());
      if (minRemaining > size) {
        minRemaining = (int) size;
      }

      from.limit(from.position() + minRemaining);
      to.put(from);
      from.limit(fromLimit);
      size -= minRemaining;
    } else {
      while (from.hasRemaining()) {
        byte get = from.get();
        if (get == LF) {
          if (chunkHeaderIndex == 0) {
            chunkHeaderIndex++;
          } else if (chunkHeaderIndex < 3) {
            throw new IllegalArgumentException("Incomplete chunk header");
          } else {
            if (chunkHeaderBytes[1] == HASH) {
              if (chunkHeaderBytes[2] == HASH) {
                if (chunkHeaderIndex == 3) {
                  if (readChunkCount == 0) {
                    throw new IllegalArgumentException("At least one chunk needed.");
                  }

                  chunkHeaderIndex = 0;
                  readChunkCount = 0;
                  return true;
                } else {
                  throw new IllegalArgumentException("Stray bytes after end of chunk character");
                }
              }

              byte firstByte = chunkHeaderBytes[2];
              if (firstByte < DIGIT_1 || firstByte > DIGIT_9) {
                throw new IllegalArgumentException(
                    "Illegal character at the beginning of chunk-size");
              }

              long chunkSize = firstByte - DIGIT_0;
              for (int i = 3; i < chunkHeaderIndex; i++) {
                byte digit = chunkHeaderBytes[i];
                if (digit < DIGIT_0 || digit > DIGIT_9) {
                  throw Throwables.throwFormatted(
                      IllegalArgumentException.class,
                      "Non-digit byte {} at position {} in chunk size",
                      digit,
                      i - 2);
                }

                chunkSize = chunkSize * 10 + digit - DIGIT_0;
              }
              chunkHeaderIndex = 0;
              readChunkCount++;
              size = chunkSize;
              break;
            } else {
              throw new IllegalArgumentException("Invalid byte in chunk header ");
            }
          }
        } else if (chunkHeaderIndex == 0) {
          throw new IllegalArgumentException("Header could not be parsed");
        } else if (chunkHeaderIndex < chunkHeaderBytes.length) {
          chunkHeaderBytes[chunkHeaderIndex++] = get;
        } else {
          throw new IllegalArgumentException("Header is too long");
        }
      }
    }

    return false;
  }

  ByteBuffer chunkHeader = (ByteBuffer) ByteBuffer.allocate(13).flip();
  int writeSize;

  void flushChunkHeader(ByteBuffer to) {
    while (to.hasRemaining() && chunkHeader.hasRemaining()) {
      to.put(chunkHeader.get());
    }
  }

  @Override
  public boolean encode(ByteBuffer from, ByteBuffer to) {
    if (chunkHeader.hasRemaining()) {
      flushChunkHeader(to);
      if (chunkHeader.hasRemaining()) {
        return false;
      }

      if (writtenChunkCount == 0) {
        return true;
      }
    }

    int remaining = from.remaining();
    if (remaining == 0) {
      if (writtenChunkCount == 0) {
        throw new IllegalArgumentException("need to send some data!");
      }

      writtenChunkCount = 0;

      chunkHeader.clear();
      chunkHeader.put(LF);
      chunkHeader.put(HASH);
      chunkHeader.put(HASH);
      chunkHeader.put(LF);
      chunkHeader.flip();

      return false;
    }

    if (writeSize > 0) {
      final int fromLimit = from.limit();
      int minRemaining = Math.min(from.remaining(), to.remaining());
      if (minRemaining > writeSize) {
        minRemaining = writeSize;
      }

      from.limit(from.position() + minRemaining);
      to.put(from);
      from.limit(fromLimit);
      writeSize -= minRemaining;

      return false;
    }

    chunkHeader.clear();
    chunkHeader.put(LF);
    chunkHeader.put(HASH);
    chunkHeader.put(Long.toString(remaining).getBytes(charset));
    chunkHeader.put(LF);
    chunkHeader.flip();
    writtenChunkCount++;
    writeSize = remaining;

    return false;
  }

  final Charset charset = StandardCharsets.UTF_8;

  private static final Logger logger = LogManager.getLogger();
}
