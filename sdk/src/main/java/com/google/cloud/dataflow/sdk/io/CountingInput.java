/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.io;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.dataflow.sdk.io.CountingSource.NowTimestampFn;
import com.google.cloud.dataflow.sdk.io.Read.Unbounded;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.values.PBegin;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollection.IsBounded;
import com.google.common.base.Optional;

import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * A {@link PTransform} that produces longs. When used to produce a
 * {@link IsBounded#BOUNDED bounded} {@link PCollection}, {@link CountingInput} starts at {@code 0}
 * and counts up to a specified maximum. When used to produce an
 * {@link IsBounded#UNBOUNDED unbounded} {@link PCollection}, it counts up to {@link Long#MAX_VALUE}
 * and then never produces more output. (In practice, this limit should never be reached.)
 *
 * <p>The bounded {@link CountingInput} is implemented based on {@link OffsetBasedSource} and
 * {@link OffsetBasedSource.OffsetBasedReader}, so it performs efficient initial splitting and it
 * supports dynamic work rebalancing.
 *
 * <p>To produce a bounded {@code PCollection<Long>}, use {@link CountingInput#upTo(long)}:
 *
 * <pre>{@code
 * Pipeline p = ...
 * PTransform<PBegin, PCollection<Long>> producer = CountingInput.upTo(1000);
 * PCollection<Long> bounded = p.apply(producer);
 * }</pre>
 *
 * <p>To produce an unbounded {@code PCollection<Long>}, use {@link CountingInput#unbounded()},
 * calling {@link UnboundedCountingInput#withTimestampFn(SerializableFunction)} to provide values
 * with timestamps other than {@link Instant#now}.
 *
 * <pre>{@code
 * Pipeline p = ...
 *
 * // To create an unbounded producer that uses processing time as the element timestamp.
 * PCollection<Long> unbounded = p.apply(CountingInput.unbounded());
 * // Or, to create an unbounded source that uses a provided function to set the element timestamp.
 * PCollection<Long> unboundedWithTimestamps =
 *     p.apply(CountingInput.unbounded().withTimestampFn(someFn));
 * }</pre>
 */
public class CountingInput {
  /**
   * Creates a {@link BoundedCountingInput} that will produce the specified number of elements,
   * from {@code 0} to {@code numElements - 1}.
   */
  public static BoundedCountingInput upTo(long numElements) {
    checkArgument(numElements > 0, "numElements (%s) must be greater than 0", numElements);
    return new BoundedCountingInput(numElements);
  }

  /**
   * Creates an {@link UnboundedCountingInput} that will produce numbers starting from {@code 0} up
   * to {@link Long#MAX_VALUE}.
   *
   * <p>After {@link Long#MAX_VALUE}, the transform never produces more output. (In practice, this
   * limit should never be reached.)
   *
   * <p>Elements in the resulting {@link PCollection PCollection&lt;Long&gt;} will by default have
   * timestamps corresponding to processing time at element generation, provided by
   * {@link Instant#now}. Use the transform returned by
   * {@link UnboundedCountingInput#withTimestampFn(SerializableFunction)} to control the output
   * timestamps.
   */
  public static UnboundedCountingInput unbounded() {
    return new UnboundedCountingInput(
        new NowTimestampFn(), Optional.<Long>absent(), Optional.<Duration>absent());
  }

  /**
   * A {@link PTransform} that will produce a specified number of {@link Long Longs} starting from
   * 0.
   */
  public static class BoundedCountingInput extends PTransform<PBegin, PCollection<Long>> {
    private final long numElements;

    private BoundedCountingInput(long numElements) {
      this.numElements = numElements;
    }

    @SuppressWarnings("deprecation")
    @Override
    public PCollection<Long> apply(PBegin begin) {
      return begin.apply(Read.from(CountingSource.upTo(numElements)));
    }
  }

  /**
   * A {@link PTransform} that will produce numbers starting from {@code 0} up to
   * {@link Long#MAX_VALUE}.
   *
   * <p>After {@link Long#MAX_VALUE}, the transform never produces more output. (In practice, this
   * limit should never be reached.)
   *
   * <p>Elements in the resulting {@link PCollection PCollection&lt;Long&gt;} will by default have
   * timestamps corresponding to processing time at element generation, provided by
   * {@link Instant#now}. Use the transform returned by
   * {@link UnboundedCountingInput#withTimestampFn(SerializableFunction)} to control the output
   * timestamps.
   */
  public static class UnboundedCountingInput extends PTransform<PBegin, PCollection<Long>> {
    private final SerializableFunction<Long, Instant> timestampFn;
    private final Optional<Long> maxNumRecords;
    private final Optional<Duration> maxReadTime;

    private UnboundedCountingInput(
        SerializableFunction<Long, Instant> timestampFn,
        Optional<Long> maxNumRecords,
        Optional<Duration> maxReadTime) {
      this.timestampFn = timestampFn;
      this.maxNumRecords = maxNumRecords;
      this.maxReadTime = maxReadTime;
    }

    /**
     * Returns an {@link UnboundedCountingInput} like this one, but where output elements have the
     * timestamp specified by the timestampFn.
     *
     * <p>Note that the timestamps produced by {@code timestampFn} may not decrease.
     */
    public UnboundedCountingInput withTimestampFn(SerializableFunction<Long, Instant> timestampFn) {
      return new UnboundedCountingInput(timestampFn, maxNumRecords, maxReadTime);
    }

    /**
     * Returns an {@link UnboundedCountingInput} like this one, but that will read at most the
     * specified number of elements.
     *
     * <p>A bounded amount of elements will be produced by the result transform, and the result
     * {@link PCollection} will be {@link IsBounded#BOUNDED bounded}.
     */
    public UnboundedCountingInput withMaxNumRecords(long maxRecords) {
      checkArgument(
          maxRecords > 0, "MaxRecords must be a positive (nonzero) value. Got %s", maxRecords);
      return new UnboundedCountingInput(timestampFn, Optional.of(maxRecords), maxReadTime);
    }

    /**
     * Returns an {@link UnboundedCountingInput} like this one, but that will read for at most the
     * specified amount of time.
     *
     * <p>A bounded amount of elements will be produced by the result transform, and the result
     * {@link PCollection} will be {@link IsBounded#BOUNDED bounded}.
     */
    public UnboundedCountingInput withMaxReadTime(Duration readTime) {
      checkNotNull(readTime, "ReadTime cannot be null");
      return new UnboundedCountingInput(timestampFn, maxNumRecords, Optional.of(readTime));
    }

    @SuppressWarnings("deprecation")
    @Override
    public PCollection<Long> apply(PBegin begin) {
      Unbounded<Long> read = Read.from(CountingSource.unboundedWithTimestampFn(timestampFn));
      if (!maxNumRecords.isPresent() && !maxReadTime.isPresent()) {
        return begin.apply(read);
      } else if (maxNumRecords.isPresent() && !maxReadTime.isPresent()) {
        return begin.apply(read.withMaxNumRecords(maxNumRecords.get()));
      } else if (!maxNumRecords.isPresent() && maxReadTime.isPresent()) {
        return begin.apply(read.withMaxReadTime(maxReadTime.get()));
      } else {
        return begin.apply(
            read.withMaxReadTime(maxReadTime.get()).withMaxNumRecords(maxNumRecords.get()));
      }
    }
  }
}
