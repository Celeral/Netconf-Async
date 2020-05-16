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

import java.io.IOException;

/**
 *
 * @author Chetan Narsude {@literal <chetan@celeral.com>}
 */
public interface InTransport
{
  /**
   * Tell whether this transport is ready to be read.
   */
  boolean ready() throws IOException;

  /**
   * Reads "one" reply from the transport input stream.
   */
  StringBuffer readOne() throws IOException, JNCException;
}
