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

import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Assert;
import org.junit.Test;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ChunkedFramingMessageCodecTest {

  @Test
  public void testEncodeOfShortMessage() {
    String message =
        "<nc:rpc xmlns:nc=\"urn:ietf:params:xml:ns:netconf:base:1.0\" nc:message-id=\"1\">\n"
            + "<nc:get>\n"
            + "<nc:filter nc:type=\"xpath\" nc:select=\"software\"/>\n"
            + "</nc:get>\n"
            + "</nc:rpc>";

    ChunkedFramingMessageCodec codec = new ChunkedFramingMessageCodec();
    ByteBuffer from = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
    ByteBuffer to = ByteBuffer.allocateDirect(4096);

    MessageCodec.logBB(from.asReadOnlyBuffer(), StandardCharsets.UTF_8, "from");

    int i = 0;
    while (codec.encode(from, to) == false) {
      if (++i == 4) {
        fail("Unable to encode as expected");
      }
    }

    MessageCodec.logBB((ByteBuffer) to.asReadOnlyBuffer().flip(), StandardCharsets.UTF_8, "to");
    Assert.assertEquals("iterations", 3, i);
  }

  @Test
  public void testDecodeOfShortMessage() {
    String message =
        "\n#394\n"
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<rpc-reply xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" nc:message-id=\"1\" xmlns:nc=\"urn:ietf:params:xml:ns:netconf:base:1.0\"><data><software xmlns=\"http://viptela.com/oper-system\"><version>99.99.999-4488</version><active>true</active><default>true</default><timestamp>2020-04-25T03:15:36-00:00</timestamp><next>true</next></software></data></rpc-reply>\n"
            + "##\n";
    ChunkedFramingMessageCodec codec = new ChunkedFramingMessageCodec();
    test(codec, message);
  }

  @Test
  public void testDecodeOfTwoShortMessages() {
    String message =
        "\n#394\n"
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<rpc-reply xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" nc:message-id=\"1\" xmlns:nc=\"urn:ietf:params:xml:ns:netconf:base:1.0\"><data><software xmlns=\"http://viptela.com/oper-system\"><version>99.99.999-4488</version><active>true</active><default>true</default><timestamp>2020-04-25T03:15:36-00:00</timestamp><next>true</next></software></data></rpc-reply>\n"
            + "##\n";
    ChunkedFramingMessageCodec codec = new ChunkedFramingMessageCodec();
    test(codec, message);
    test(codec, message);
  }

  protected void test(MessageCodec<ByteBuffer> codec, String message) {
    ByteBuffer from = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
    ByteBuffer to = ByteBuffer.allocateDirect(4096);

    MessageCodec.logBB(from.asReadOnlyBuffer(), StandardCharsets.UTF_8, "from");

    int i = 0;
    while (codec.decode(from, to) == false) {
      if (++i == 3) {
        fail("Unable to decode as expected");
      }
    }

    MessageCodec.logBB((ByteBuffer) to.asReadOnlyBuffer().flip(), StandardCharsets.UTF_8, "to");
    Assert.assertEquals("iterations", 2, i);
  }

  private static final Logger logger = LogManager.getLogger();
}
