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
package com.tailf.jnc;

/**
 *
 * @author Chetan Narsude {@literal <chetan@celeral.com>}
 */
public interface OutTransport
{
  /**
   * Prints an integer to the transport output stream.
   */
  void print(long i);

  /**
   * Prints a string to the transport output stream.
   */
  void print(String s);

  /**
   * Prints an integer to the transport output stream and an additional line
   * break.
   */
  void println(int i);

  /**
   * Prints a string to the transport output stream and an additional line
   * break.
   */
  void println(String s);

  /**
   * Signals that the final chunk of data has be printed to the output
   * transport stream.
   * <p>
   * This method furthermore flushes the transport output stream buffer.
   */
  void flush();
}
