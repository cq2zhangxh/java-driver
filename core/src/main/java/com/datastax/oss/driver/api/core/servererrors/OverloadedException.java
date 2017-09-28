/*
 * Copyright (C) 2017-2017 DataStax Inc.
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
package com.datastax.oss.driver.api.core.servererrors;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.retry.RetryPolicy;
import com.datastax.oss.driver.api.core.session.Request;

/**
 * Thrown when the coordinator reported itself as being overloaded.
 *
 * <p>This exception is processed by {@link RetryPolicy#onErrorResponse(Request, Throwable, int)},
 * which will decide if it is rethrown directly to the client or if the request should be retried.
 * If all other tried nodes also fail, this exception will appear in the {@link
 * AllNodesFailedException} thrown to the client.
 */
public class OverloadedException extends QueryExecutionException {

  public OverloadedException(Node coordinator) {
    super(coordinator, String.format("%s is bootstrapping", coordinator), false);
  }

  private OverloadedException(Node coordinator, String message, boolean writableStackTrace) {
    super(coordinator, message, writableStackTrace);
  }

  @Override
  public DriverException copy() {
    return new OverloadedException(getCoordinator(), getMessage(), true);
  }
}