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
package com.celeral.netconf.jvaware;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import com.celeral.utils.Throwables;

public class ByteArrayOutputStream {
  public static String toString(java.io.ByteArrayOutputStream stream, Charset charset) {
    try {
      return stream.toString(charset.name());
    } catch (UnsupportedEncodingException e) {
      throw Throwables.throwSneaky(e);
    }
  }
}
