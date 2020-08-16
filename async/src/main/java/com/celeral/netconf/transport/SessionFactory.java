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
package com.celeral.netconf.transport;

import java.io.IOException;
import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;

public interface SessionFactory {
  public CompletableFuture<? extends Session> getSession(
      String host, int port, String username, String password, KeyPair keypair) throws IOException;
}
