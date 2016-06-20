/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.util;

import java.util.concurrent.Executor;

/**
 * An object that performs work on the current thread and optionally slows the rate of execution.
 * By default work submitted with {@link #execute(Runnable)} will not be throttled. Work can be
 * throttled by invoking {@link #setWorkFactor(double)}.
 * <p>
 * This class is *NOT* thread-safe. It is intended to be used from a single thread. However, the
 * work factor may be set from any thread.
 */
public class ThrottlingExecutor implements Executor {
  public static final double MAX_WORK_FACTOR = 1.0;
  public static final double MIN_WORK_FACTOR = 0.001;

  private final HighResolutionClock clock;

  private volatile double workToIdleFactor;
  private long pendingNanos;

  public ThrottlingExecutor() {
    this(new SystemHighResolutionClock());
  }

  ThrottlingExecutor(HighResolutionClock clock) {
    this.clock = clock;
  }

  /**
   * Executes the given command on the current thread. If throttling is enabled (the work factor
   * is less than 1.0) this command may optionally insert a delay before returning to satisfy the
   * requested work factor.
   * <p>
   * This method will not operate correct if used by more than one thread.
   *
   * @param command the work to execute
   */
  public void execute(Runnable command) {
    final double currentWorkToIdleFactor = workToIdleFactor;

    // If we're not throttling, do not get clock time, etc. This substantially reduces the overhead
    // per invocation of this feature (by ~75%).
    if (currentWorkToIdleFactor == 0.0) {
      command.run();
    } else {
      final long startWorkNanos = clock.nanoTime();
      command.run();
      final long workNanos = clock.nanoTime() - startWorkNanos;

      // NOTE: we accumulate pending delay nanos here, but we later update the pending nanos during
      // the sleep operation (if applicable), so they do not continue to grow.
      pendingNanos = Util.clampAdd(pendingNanos, (long) (workNanos * currentWorkToIdleFactor));
      if (pendingNanos > 0) {
        try {
          pendingNanos = clock.sleep(pendingNanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Sets the work factor for this executor. A work factor of {@code 1.0} indicates that execution
   * should proceed at full throughput. A work factor of less than {@code 1.0} will introduce
   * delays into the {@link #execute(Runnable)} call to approximate the requested work factor. For
   * example, if the work factor is {@code 0.7} then approximately 70% of the execute call will be
   * spent executing the supplied command while 30% will be spent idle.
   *
   * @param workFactor the work factor to set for this executor.
   */
  public void setWorkFactor(double workFactor) {
    if (workFactor < MIN_WORK_FACTOR) {
      throw new IllegalArgumentException("Work factor must be >= " + MIN_WORK_FACTOR);
    }
    if (workFactor > MAX_WORK_FACTOR) {
      throw new IllegalArgumentException("Work factor must be <= " + MAX_WORK_FACTOR);
    }

    workToIdleFactor = (1.0 - workFactor) / workFactor;
  }

  /**
   * Returns the current work factor in use.
   * @see #setWorkFactor(double)
   * @return the current work factor.
   */
  public double getWorkFactor() {
    return 1.0 / (workToIdleFactor + 1.0);
  }

  /**
   * Returns the total amount of delay (in nanoseconds) that needs to be applied to subsequent work.
   * Alternatively this can be thought to capture the error between expected delay and actual
   * applied delay. This accounts for variance in the precision of the clock and the delay
   * mechanism, both of which may vary from platform to platform.
   * <p>
   * This is required for test purposes only.
   *
   * @return the total amount of delay (in nanoseconds) that needs to be applied to subsequent work.
   */
  long getPendingNanos() {
    return pendingNanos;
  }

  /**
   * A convenience method for test that allows the pending nanos for this executor to be set
   * explicitly.
   *
   * @param pendingNanos the pending nanos to set.
   */
  void setPendingNanos(long pendingNanos) {
    this.pendingNanos = pendingNanos;
  }
}