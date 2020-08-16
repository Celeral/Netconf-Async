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
package com.celeral.netconf.ssh;

import java.io.IOException;
import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;

import com.celeral.utils.Closeables;

import com.celeral.netconf.transport.SessionFactory;

public class SSHSessionFactory implements SessionFactory, AutoCloseable {
  public static final TimeUnit DEFAULT_TIMEUNIT = TimeUnit.SECONDS;
  public static final long DEFAULT_CONNECT_TIMEOUT = 30;
  public static final long DEFAULT_AUTH_TIMEOUT = 30;

  protected final SshClient client;
  protected TimeUnit timeUnit = DEFAULT_TIMEUNIT;
  protected long connectTimeout = DEFAULT_CONNECT_TIMEOUT;
  protected long authTimeout = DEFAULT_AUTH_TIMEOUT;

  public SSHSessionFactory() {
    client = SshClient.setUpDefaultClient();

    PropertyResolverUtils.updateProperty(client, FactoryManager.TCP_NODELAY, true);
    PropertyResolverUtils.updateProperty(client, FactoryManager.SOCKET_KEEPALIVE, true);
    client.setServerKeyVerifier((session, remoteAddress, serverKey) -> true);

    client.start();
  }

  protected void completeExceptionally(CompletableFuture<?> future, Throwable throwable) {
    future.completeExceptionally(
        throwable instanceof CompletionException ? throwable : new CompletionException(throwable));
  }

  @SuppressWarnings("UseSpecificCatch")
  public <T> CompletableFuture<T> getFuture(Consumer<CompletableFuture<T>> consumer) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      consumer.accept(future);
    } catch (Throwable th) {
      completeExceptionally(future, th);
    }

    return future;
  }

  @Override
  public CompletableFuture<SSHSession> getSession(
      String host, int port, String username, String password, KeyPair keypair) throws IOException {
    return getFuture(
        future -> {
          ConnectFuture connect;
          try {
            connect = client.connect(username, host, port);
          } catch (IOException ex) {
            completeExceptionally(future, ex);
            return;
          }

          connect.addListener(
              connectFuture -> {
                if (connect.isConnected()) {
                  ClientSession session = connect.getSession();
                  try (Closeables closeables = new Closeables(session)) {
                    session.setPasswordIdentityProvider(
                        PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
                    session.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
                    if (keypair == null) {
                      session.addPasswordIdentity(password);
                    } else {
                      session.addPublicKeyIdentity(keypair);
                    }

                    try {
                      AuthFuture auth = session.auth();
                      auth.addListener(
                          authFuture -> {
                            if (auth.isSuccess()) {
                              future.complete(new SSHSession(session));
                            } else {
                              completeExceptionally(future, auth.getException());
                            }
                          });
                    } catch (IOException ex) {
                      completeExceptionally(future, ex);
                    }

                    closeables.protect();
                  }
                } else {
                  completeExceptionally(future, connect.getException());
                }
              });
        });
  }

  @Override
  public void close() {
    client.stop();
  }

  private static final Logger logger = LogManager.getLogger();
}
