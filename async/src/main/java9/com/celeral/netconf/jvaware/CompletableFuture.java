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
package com.celeral.netconf.jvaware;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.celeral.utils.NamedThreadFactory;

import com.celeral.netconf.RequestGenerationException;

public class CompletableFuture {
  public static <T> java.util.concurrent.CompletableFuture<T> orTimeout(
      java.util.concurrent.CompletableFuture<T> future, long duration, TimeUnit timeUnit) {
    return future.orTimeout(duration, timeUnit);
  }

  public static <T> java.util.concurrent.CompletableFuture<T> failedFuture(Throwable ex) {
    return CompletableFuture.failedFuture(ex);
  }
}
