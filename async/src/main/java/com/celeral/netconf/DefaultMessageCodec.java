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
import java.nio.charset.Charset;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DefaultMessageCodec implements MessageCodec<ByteBuffer> {
  public static final String END_MARKER = "]]>]]>";

  private final byte[] endMarkerBytes;
  private final int endMarkerLength;

  private final FIFO fifo;

  private int endMarkerPosition;
  private State currentState;

  public DefaultMessageCodec(Charset charset) {
    this.endMarkerBytes = END_MARKER.getBytes(charset);
    this.endMarkerLength = endMarkerBytes.length;
    this.endMarkerPosition = endMarkerLength;
    this.fifo = new FIFO(endMarkerLength);

    initializeStateMachine();
  }

  static class FIFO {
    byte[] bytes;
    int start;
    /* inclusive */
    int length;

    FIFO(int count) {
      bytes = new byte[count];
    }

    /** pushes the byte in the fifo, if needed boots the least recently pushed byte out. */
    byte push(byte b) {
      if (length == bytes.length) {
        try {
          return bytes[start];
        } finally {
          bytes[start++] = b;
          if (start == bytes.length) {
            start = 0;
          }
        }
      }

      int index = start + length;
      if (index >= bytes.length) {
        index -= bytes.length;
      }
      bytes[index] = b;
      length++;
      return 0;
    }

    /**
     * pop a byte at a time from the fifo.
     *
     * @return popped byte if available or 0
     */
    byte pop() {
      if (length == 0) {
        return 0;
      }

      try {
        return bytes[start++];
      } finally {
        if (start == bytes.length) {
          start = 0;
        }
      }
    }

    void clear() {
      length = 0;
    }
  }

  @Override
  public boolean decode(ByteBuffer from, ByteBuffer to) {
    int available = from.remaining();
    if (available > 0) {
      State state = currentState;
      try {
        do {
          final byte aByte = from.get();
          state = state.getNextStep(aByte);
          switch (state) {
            case Sixth:
              state = State.None;
              fifo.clear();
              return true;

            case None:
              byte popped;
              while (to.hasRemaining() && (popped = fifo.pop()) != 0) {
                to.put(popped);
                if (--available == 0) {
                  break;
                }
              }

              if (to.hasRemaining() && available > 0) {
                to.put(aByte);
              } else {
                /* guaranteed to have room because output buffer had a room which got filled by fifo's byte */
                fifo.push(aByte);
              }
              break;

            default:
              if (to.hasRemaining()) {
                byte displaced = fifo.push(aByte);
                if (displaced != 0) {
                  to.put(displaced);
                }
              }
              break;
          }
        } while (to.hasRemaining() && --available > 0);
      } finally {
        currentState = state;
      }
    }

    return false;
  }

  @Override
  public boolean encode(ByteBuffer from, ByteBuffer to) {
    if (endMarkerPosition == endMarkerLength) {
      int available = to.remaining();
      int remaining = from.remaining();

      if (available < remaining) {
        while (available-- > 0) {
          to.put(from.get());
        }

        return false;
      }

      to.put(from);
      endMarkerPosition = 0;
    }

    return writeEndMarker(to);
  }

  private boolean writeEndMarker(ByteBuffer to) {
    while (to.hasRemaining()) {
      to.put(endMarkerBytes[endMarkerPosition++]);
      if (endMarkerPosition == endMarkerLength) {
        return true;
      }
    }

    return false;
  }

  enum State {
    None((byte) 0),
    First((byte) ']'),
    Second((byte) ']'),
    Third((byte) '>'),
    Fourth((byte) ']'),
    Fifth((byte) ']'),
    Sixth((byte) '>');

    private final byte stateByte;
    private State[] nextStates;

    State(byte b) {
      this.stateByte = b;
    }

    @SuppressWarnings("AssignmentToCollectionOrArrayFieldFromParameter")
    public void setNextSteps(State... nextStates) {
      this.nextStates = nextStates;
    }

    public State getNextStep(byte b) {
      if (nextStates != null) {
        for (State state : nextStates) {
          if (b == state.stateByte) {
            return state;
          }
        }
      }

      return None;
    }
  }

  private void initializeStateMachine() {
    State.None.setNextSteps(State.First);
    State.First.setNextSteps(State.Second);
    State.Second.setNextSteps(State.Third, State.Second);
    State.Third.setNextSteps(State.Fourth, State.First);
    State.Fourth.setNextSteps(State.Fifth);
    State.Fifth.setNextSteps(State.Sixth, State.Second);

    currentState = State.None;
  }

  private static final Logger logger = LogManager.getLogger();
}
