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

import java.text.MessageFormat;
import java.util.concurrent.TimeUnit;

public class RequestTimeoutException extends RequestPhaseException {
  private static final long serialVersionUID = 20200503042300L;

  private final long timeout;
  private final TimeUnit timeUnit;

  public RequestTimeoutException(long timeout, TimeUnit timeUnit, Throwable cause) {
    super(cause);
    this.timeout = timeout;
    this.timeUnit = timeUnit;
  }

  @Override
  public String getMessage() {
    return MessageFormat.format("Timedout after {0} {1}", timeout, timeUnit);
  }
}
