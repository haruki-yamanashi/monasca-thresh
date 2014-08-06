/*
 * Copyright (c) 2014 Hewlett-Packard Development Company, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monasca.thresh.domain.model;

import com.hpcloud.mon.common.model.alarm.AlarmState;
import com.hpcloud.util.stats.SlidingWindowStats;
import com.hpcloud.util.time.TimeResolution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates statistics for a specific SubAlarm.
 */
public class SubAlarmStats {
  private static final Logger logger = LoggerFactory.getLogger(SubAlarmStats.class);
  /** Number of slots for future periods that we should collect metrics for. */
  private static final int FUTURE_SLOTS = 2;
  /** Helps determine how many empty window observations before transitioning to UNDETERMINED. */
  private static final int UNDETERMINED_COEFFICIENT = 2;

  private final int slotWidth;
  private SubAlarm subAlarm;
  private final SlidingWindowStats stats;
  /** The number of times we can observe an empty window before transitioning to UNDETERMINED state. */
  final int emptyWindowObservationThreshold;
  private int emptyWindowObservations;

  public SubAlarmStats(SubAlarm subAlarm, long viewEndTimestamp) {
    this(subAlarm, TimeResolution.MINUTES, viewEndTimestamp);
  }

  public SubAlarmStats(SubAlarm subAlarm, TimeResolution timeResolution, long viewEndTimestamp) {
    slotWidth = subAlarm.getExpression().getPeriod();
    this.subAlarm = subAlarm;
    this.subAlarm.setNoState(true);
    this.stats =
        new SlidingWindowStats(subAlarm.getExpression().getFunction().toStatistic(),
            timeResolution, slotWidth, subAlarm.getExpression().getPeriods(), FUTURE_SLOTS,
            viewEndTimestamp);
    int period = subAlarm.getExpression().getPeriod();
    int periodMinutes = period < 60 ? 1 : period / 60; // Assumes the period is in seconds so we
                                                       // convert to minutes
    emptyWindowObservationThreshold =
        periodMinutes * subAlarm.getExpression().getPeriods() * UNDETERMINED_COEFFICIENT;
    emptyWindowObservations = 0;
  }

  /**
   * Evaluates the {@link #subAlarm} for the current stats window, updating the sub-alarm's state if
   * necessary and sliding the window to the {@code slideToTimestamp}.
   *
   * @return true if the alarm's state changed, else false.
   */
  public boolean evaluateAndSlideWindow(long slideToTimestamp) {
    try {
      return evaluate();
    } catch (Exception e) {
      logger.error("Failed to evaluate {}", this, e);
      return false;
    } finally {
      slideWindow(slideToTimestamp);
    }
  }

  /**
   * Just slide the window. Either slideWindow or evaluateAndSlideWindow should be called for each
   * time period, but never both
   *
   * @param slideToTimestamp
   */
  public void slideWindow(long slideToTimestamp) {
    stats.slideViewTo(slideToTimestamp);
  }

  /**
   * Returns the stats.
   */
  public SlidingWindowStats getStats() {
    return stats;
  }

  /**
   * Returns the SubAlarm.
   */
  public SubAlarm getSubAlarm() {
    return subAlarm;
  }

  @Override
  public String toString() {
    return String
        .format(
            "SubAlarmStats [subAlarm=%s, stats=%s, emptyWindowObservations=%s, emptyWindowObservationThreshold=%s]",
            subAlarm, stats, emptyWindowObservations, emptyWindowObservationThreshold);
  }

  /**
   * @throws IllegalStateException if the {@code timestamp} is outside of the {@link #stats} window
   */
  boolean evaluate() {
    double[] values = stats.getViewValues();
    boolean thresholdExceeded = false;
    boolean hasEmptyWindows = false;
    for (double value : values) {
      if (Double.isNaN(value)) {
        hasEmptyWindows = true;
      } else {
        emptyWindowObservations = 0;

        // Check if value is OK
        if (!subAlarm.getExpression().getOperator()
            .evaluate(value, subAlarm.getExpression().getThreshold())) {
          if (!shouldSendStateChange(AlarmState.OK)) {
            return false;
          }
          setSubAlarmState(AlarmState.OK);
          return true;
        } else
          thresholdExceeded = true;
      }
    }

    if (thresholdExceeded && !hasEmptyWindows) {
      if (!shouldSendStateChange(AlarmState.ALARM)) {
        return false;
      }
      setSubAlarmState(AlarmState.ALARM);
      return true;
    }

    // Window is empty at this point
    emptyWindowObservations++;

    if ((emptyWindowObservations >= emptyWindowObservationThreshold)
        && shouldSendStateChange(AlarmState.UNDETERMINED) && !subAlarm.isSporadicMetric()) {
      setSubAlarmState(AlarmState.UNDETERMINED);
      return true;
    }

    return false;
  }

  private boolean shouldSendStateChange(AlarmState newState) {
    return !subAlarm.getState().equals(newState) || subAlarm.isNoState();
  }

  private void setSubAlarmState(AlarmState newState) {
    subAlarm.setState(newState);
    subAlarm.setNoState(false);
  }

  /**
   * This MUST only be used for compatible SubAlarms, i.e. where
   * this.subAlarm.isCompatible(subAlarm) is true
   *
   * @param subAlarm
   */
  public void updateSubAlarm(final SubAlarm subAlarm) {
    this.subAlarm = subAlarm;
  }
}