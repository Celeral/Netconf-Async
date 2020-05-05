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
package com.celeral.netconf.ssh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.tailf.jnc.Element;
import com.tailf.jnc.JNCException;
import com.tailf.jnc.NodeSet;

import org.junit.Ignore;
import org.junit.Test;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.celeral.netconf.NetConfSession;
import com.celeral.netconf.transport.Credentials;

@Ignore
public class SSHByteBufferChannelTest {

  @Test
  public void testSomeMethod() throws Exception {
    Credentials creds =
        new Credentials() {
          @Override
          public KeyPair getKeyPair()
              throws IOException, NoSuchAlgorithmException, InvalidKeySpecException,
                  InvalidAlgorithmParameterException {
            return null;
          }

          @Override
          public String getPassword() {
            return "admin";
          }

          @Override
          public String getUserName() {
            return "admin";
          }
        };

    try (SSHSessionFactory factory = new SSHSessionFactory();
        SSHSession session = factory.getSession("localhost", 8830, creds)) {
      try (SSHByteBufferChannel channel = session.getChannel(30, TimeUnit.SECONDS)) {
        NetConfSession netconf = new NetConfSession(channel, StandardCharsets.UTF_8);

        long start = System.nanoTime();

        CompletableFuture<AutoCloseable> hello =
            netconf.hello(3000000, 3000000, TimeUnit.MICROSECONDS);
        try (AutoCloseable ac = hello.join()) {
          logger.info("session id = {}", netconf.sessionId);
          CompletableFuture<NodeSet> software = netconf.get("software", 30, 30, TimeUnit.SECONDS);
          CompletableFuture<NodeSet> software1 = netconf.get("software", 30, 30, TimeUnit.SECONDS);
          //                CompletableFuture<Element> reboot = session.action(getRebootAction(),
          // 30, 30, TimeUnit.MINUTES);

          software.join();
          software1.join();
          //                reboot.join();
        } finally {
          logger.info("passed time = {}", System.nanoTime() - start);
        }
      }
      try (SSHByteBufferChannel channel = session.getChannel(30, TimeUnit.SECONDS)) {
        NetConfSession netconf = new NetConfSession(channel, StandardCharsets.UTF_8);

        long start = System.nanoTime();

        CompletableFuture<AutoCloseable> hello =
            netconf.hello(3000000, 3000000, TimeUnit.MICROSECONDS);
        try (AutoCloseable ac = hello.join()) {
          logger.info("session id = {}", netconf.sessionId);
          CompletableFuture<NodeSet> software = netconf.get("software", 30, 30, TimeUnit.SECONDS);
          CompletableFuture<NodeSet> software1 = netconf.get("software", 30, 30, TimeUnit.SECONDS);
          //                CompletableFuture<Element> reboot = session.action(getRebootAction(),
          // 30, 30, TimeUnit.MINUTES);

          software.join();
          software1.join();
          //                reboot.join();
        } finally {
          logger.info("passed time = {}", System.nanoTime() - start);
        }
      }
    }
  }

  protected Element getRebootAction() throws JNCException {
    Element request = Element.create("http://celeral.com/actions", "request");
    Element action = Element.create("http://celeral.com/actions", "reboot");
    request.addChild(action);
    return request;
  }

  private static final Logger logger = LogManager.getLogger();
}
