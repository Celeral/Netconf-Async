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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public interface MessageCodec<T extends Buffer> {
  boolean decode(T from, T to);

  boolean encode(T from, T to);

  static void logBB(ByteBuffer bb, Charset charset, String ann) {
    byte[] array = new byte[bb.remaining()];
    bb.get(array);

    logger.debug("{} *{}*", ann, new String(array, charset));
  }

  static final Logger logger = LogManager.getLogger();
}
