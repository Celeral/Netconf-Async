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

public interface ByteBufferProcessor {
  /**
   * Process the argument either from reading the data from it or writing the data to it. The
   * processing semantics is decided by the context in which the call is made. e.g. if the call is
   * made in response to read, the buffer will contain the data to be read. If the call is made in
   * response to write, the buffer needs to be filled with the data that needs to be written.
   *
   * @param buffer the medium to exchange the bytes
   * @return true to register interest to be called one more time, false otherwise.
   */
  boolean process(ByteBuffer buffer);

  /**
   * The caller of this processor encountered failure. The failure is typically IO related.
   *
   * @param exc exception populated with the context of the failure.
   */
  void failed(Throwable exc);

  void completed();
}
