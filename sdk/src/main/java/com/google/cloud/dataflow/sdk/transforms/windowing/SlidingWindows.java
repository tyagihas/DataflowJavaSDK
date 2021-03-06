/*
 * Copyright (C) 2015 Google Inc.
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

package com.google.cloud.dataflow.sdk.transforms.windowing;

import com.google.cloud.dataflow.sdk.coders.Coder;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link WindowFn} that windows values into possibly overlapping fixed-size
 * timestamp-based windows.
 *
 * <p>For example, in order to window data into 10 minute windows that
 * update every minute:
 * <pre> {@code
 * PCollection<Integer> items = ...;
 * PCollection<Integer> windowedItems = items.apply(
 *   Window.<Integer>into(SlidingWindows.of(Duration.standardMinutes(10))));
 * } </pre>
 */
public class SlidingWindows extends NonMergingWindowFn<Object, IntervalWindow> {

  /**
   * Amount of time between generated windows.
   */
  private final Duration period;

  /**
   * Size of the generated windows.
   */
  private final Duration size;

  /**
   * Offset of the generated windows.
   * Windows start at time N * start + offset, where 0 is the epoch.
   */
  private final Duration offset;

  /**
   * Assigns timestamps into half-open intervals of the form
   * [N * period, N * period + size), where 0 is the epoch.
   *
   * <p>If {@link SlidingWindows#every} is not called, the period defaults
   * to the largest time unit smaller than the given duration.  For example,
   * specifying a size of 5 seconds will result in a default period of 1 second.
   */
  public static SlidingWindows of(Duration size) {
    return new SlidingWindows(getDefaultPeriod(size), size, Duration.ZERO);
  }

  /**
   * Returns a new {@code SlidingWindows} with the original size, that assigns
   * timestamps into half-open intervals of the form
   * [N * period, N * period + size), where 0 is the epoch.
   */
  public SlidingWindows every(Duration period) {
    return new SlidingWindows(period, size, offset);
  }

  /**
   * Assigns timestamps into half-open intervals of the form
   * [N * period + offset, N * period + offset + size).
   *
   * @throws IllegalArgumentException if offset is not in [0, period)
   */
  public SlidingWindows withOffset(Duration offset) {
    return new SlidingWindows(period, size, offset);
  }

  private SlidingWindows(Duration period, Duration size, Duration offset) {
    if (offset.isShorterThan(Duration.ZERO)
        || !offset.isShorterThan(period)
        || !size.isLongerThan(Duration.ZERO)) {
      throw new IllegalArgumentException(
          "SlidingWindows WindowingStrategies must have 0 <= offset < period and 0 < size");
    }
    this.period = period;
    this.size = size;
    this.offset = offset;
  }

  @Override
  public Coder<IntervalWindow> windowCoder() {
    return IntervalWindow.getCoder();
  }

  @Override
  public Collection<IntervalWindow> assignWindows(AssignContext c) {
    List<IntervalWindow> windows =
        new ArrayList<>((int) (size.getMillis() / period.getMillis()));
    Instant timestamp = c.timestamp();
    long lastStart = lastStartFor(timestamp);
    for (long start = lastStart;
         start > timestamp.minus(size).getMillis();
         start -= period.getMillis()) {
      windows.add(new IntervalWindow(new Instant(start), size));
    }
    return windows;
  }

  /**
   * Return the earliest window that contains the end of the main-input window.
   */
  @Override
  public IntervalWindow getSideInputWindow(final BoundedWindow window) {
    if (window instanceof GlobalWindow) {
      throw new IllegalArgumentException(
          "Attempted to get side input window for GlobalWindow from non-global WindowFn");
    }
    long lastStart = lastStartFor(window.maxTimestamp().minus(size));
    return new IntervalWindow(new Instant(lastStart + period.getMillis()), size);
  }

  @Override
  public boolean isCompatible(WindowFn<?, ?> other) {
    if (other instanceof SlidingWindows) {
      SlidingWindows that = (SlidingWindows) other;
      return period.equals(that.period)
        && size.equals(that.size)
        && offset.equals(that.offset);
    } else {
      return false;
    }
  }

  /**
   * Return the last start of a sliding window that contains the timestamp.
   */
  private long lastStartFor(Instant timestamp) {
    return timestamp.getMillis()
        - timestamp.plus(period).minus(offset).getMillis() % period.getMillis();
  }

  static Duration getDefaultPeriod(Duration size) {
    if (size.isLongerThan(Duration.standardHours(1))) {
      return Duration.standardHours(1);
    }
    if (size.isLongerThan(Duration.standardMinutes(1))) {
      return Duration.standardMinutes(1);
    }
    if (size.isLongerThan(Duration.standardSeconds(1))) {
      return Duration.standardSeconds(1);
    }
    return Duration.millis(1);
  }

  public Duration getPeriod() {
    return period;
  }

  public Duration getSize() {
    return size;
  }

  public Duration getOffset() {
    return offset;
  }

  /**
   * Ensure that later sliding windows have an output time that is past the end of earlier windows.
   *
   * <p>If this is the earliest sliding window containing {@code inputTimestamp}, that's fine.
   * Otherwise, we pick the earliest time that doesn't overlap with earlier windows.
   */
  @Override
  public Instant getOutputTime(Instant inputTimestamp, IntervalWindow window) {
    Instant startOfLastSegment = window.maxTimestamp().minus(period);
    return startOfLastSegment.isBefore(inputTimestamp)
        ? inputTimestamp
        : startOfLastSegment.plus(1);
  }
}
