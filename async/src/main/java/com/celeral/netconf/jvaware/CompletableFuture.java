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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.celeral.utils.NamedThreadFactory;

import com.celeral.netconf.RequestGenerationException;

public class CompletableFuture {
  /* get a thread factory with the name we recognize and daemon threads */
  private static ThreadFactory getNamedThreadFactory() {
    ThreadGroup threadGroup = new ThreadGroup("java8:CompletableFuture.orTimeout");
    threadGroup.setDaemon(true);
    return new NamedThreadFactory(threadGroup);
  }

  private static final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(1, getNamedThreadFactory());

  /* ensure that we give a chance to the timeouts to kick in before we exit */
  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread("java8:CompletableFuture.orTimeout:shutdown") {
              @Override
              public void run() {
                scheduler.shutdown();
              }
            });
  }

  public static <T> java.util.concurrent.CompletableFuture<T> orTimeout(
      java.util.concurrent.CompletableFuture<T> future, long duration, TimeUnit timeUnit) {
    final AtomicReference<TimeoutException> reference = new AtomicReference<>(null);
    final ScheduledFuture<?> schedule =
        scheduler.schedule(
            () -> {
              if (!future.isDone()) {
                final TimeoutException ex = new TimeoutException();
                reference.set(ex);
                future.completeExceptionally(reference.get());
              }
            },
            duration,
            timeUnit);

    future.whenComplete(
        (r, th) -> {
          if ((th == null || th != reference.get()) && !schedule.isDone()) {
            schedule.cancel(false);
          }
        });
    return future;
  }

  public static <T> java.util.concurrent.CompletableFuture<T> failedFuture(Throwable ex) {
    java.util.concurrent.CompletableFuture<T> future =
        new java.util.concurrent.CompletableFuture<>();
    future.completeExceptionally(ex);
    return future;
  }
}
