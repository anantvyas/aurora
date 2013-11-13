/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.aurora.scheduler.state;

import java.util.Map;
import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ShardUpdateResult;
import com.twitter.aurora.gen.UpdateResult;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.ScheduleException;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager.TaskDescriptionException;
import com.twitter.aurora.scheduler.configuration.SanitizedConfiguration;
import com.twitter.aurora.scheduler.storage.entities.IAssignedTask;
import com.twitter.aurora.scheduler.storage.entities.IJobKey;
import com.twitter.aurora.scheduler.storage.entities.ITaskConfig;

/**
 * Scheduling core, stores scheduler state and makes decisions about which tasks to schedule when
 * a resource offer is made.
 *
 * When a job is submitted to the scheduler core, it will store the job configuration and offer
 * the job to all configured scheduler modules, which are responsible for triggering execution of
 * the job.  Until a job is triggered by a scheduler module, it is retained in the scheduler core
 * in the PENDING state.
 */
public interface SchedulerCore {

  /**
   * Creates a new job, whose tasks will become candidates for scheduling.
   *
   * @param sanitizedConfiguration The configuration of the job to create tasks for.
   * @throws ScheduleException If there was an error scheduling a cron job.
   * @throws TaskDescriptionException If an invalid task description was given.
   */
  void createJob(SanitizedConfiguration sanitizedConfiguration)
      throws ScheduleException, TaskDescriptionException;

  /**
   * Adds new instances specified by the instances set.
   * <p>
   * Provided instance IDs should be disjoint from the instance IDs active in the job.
   *
   * @param jobKey IJobKey identifying the parent job.
   * @param instanceIds Set of instance IDs to be added to the job.
   * @param config ITaskConfig to use with new instances.
   * @throws ScheduleException If any of the existing instance IDs already exist.
   */
  void addInstances(IJobKey jobKey, ImmutableSet<Integer> instanceIds, ITaskConfig config)
      throws ScheduleException;

  /**
   * Validates the new job configuration passes resource filters.
   *
   * @param sanitizedConfiguration Job configuration to validate.
   * @throws ScheduleException If job resources do not pass filters.
   */
  void validateJobResources(SanitizedConfiguration sanitizedConfiguration) throws ScheduleException;

  /**
   * Starts a cron job immediately.
   *
   * @param jobKey Job key.
   * @throws ScheduleException If the specified job does not exist, or is not a cron job.
   * @throws TaskDescriptionException If the parsing of the job failed.
   */
  void startCronJob(IJobKey jobKey) throws ScheduleException, TaskDescriptionException;

  /**
   * Registers an update for a job.
   *
   * @param sanitizedConfiguration Updated job configuration.
   * @throws ScheduleException If there was an error in scheduling an update when no active tasks
   *                           are found for a job or an update for the job is already in progress.
   * @return A unique update token if an update must be coordinated through
   *         {@link #updateShards(IJobKey, String, Set, String)}and
   *         {@link #finishUpdate(IJobKey, String, Optional, UpdateResult)}, or an absent value if
   * the update was completed in-place and no further action is necessary.
   */
  Optional<String> initiateJobUpdate(SanitizedConfiguration sanitizedConfiguration)
      throws ScheduleException;

  /**
   * Initiates an update on shards within a job.
   * Requires that startUpdate was called for the job first.
   *
   * @param jobKey Job being updated.
   * @param invokingUser Name of the invoking user for auditing purposes.
   * @param shards Shards to be updated.
   * @param updateToken A unique string identifying the update, must be provided from
   *                    {@link #initiateJobUpdate(SanitizedConfiguration)}.
   * @throws ScheduleException If there was an error in updating the state to UPDATING.
   * @return The action taken on each of the shards.
   */
  Map<Integer, ShardUpdateResult> updateShards(
      IJobKey jobKey,
      String invokingUser,
      Set<Integer> shards,
      String updateToken) throws ScheduleException;

  /**
   * Initiates a rollback of the specified shards.
   * Requires that startUpdate was called for the job first.
   *
   * @param jobKey Job being updated.
   * @param invokingUser Name of the invoking user for auditing purposes.
   * @param shards Shards to be updated.
   * @param updateToken A unique string identifying the update, must be provided from
   *                    {@link #initiateJobUpdate(SanitizedConfiguration)}
   * @throws ScheduleException If there was an error in updating the state to ROLLBACK.
   * @return The action taken on each of the shards.
   */
  Map<Integer, ShardUpdateResult> rollbackShards(
      IJobKey jobKey,
      String invokingUser,
      Set<Integer> shards,
      String updateToken) throws ScheduleException;

  /**
   * Completes an update.
   *
   * @param jobKey The job being updated.
   * @param invokingUser Name of the invoking user for auditing purposes.
   * @param updateToken The update token provided from
   *                    {@link #initiateJobUpdate(SanitizedConfiguration)},
   *                    or not present if the update is being forcibly terminated.
   * @param result {@code true} if the update was successful, {@code false} otherwise.
   * @throws ScheduleException If an update for the job does not exist or if the update token is
   *                           invalid.
   */
  void finishUpdate(
      IJobKey jobKey,
      String invokingUser,
      Optional<String> updateToken,
      UpdateResult result) throws ScheduleException;

  /**
   * Assigns a new state to tasks.
   *
   * @param query Builder for a query to identify tasks
   * @param status The new state of the tasks.
   * @param message Additional information about the state transition.
   */
  void setTaskStatus(Query.Builder query, ScheduleStatus status, Optional<String> message);

  /**
   * Kills a specific set of tasks.
   *
   * @param query Builder for a query to identify tasks
   * @param user Name of the user performing the kill.
   * @throws ScheduleException If a problem occurs with the kill request.
   */
  void killTasks(Query.Builder query, String user) throws ScheduleException;

  /**
   * Initiates a restart of shards within an active job.
   *
   * @param jobKey Key of job to be restarted.
   * @param shards Shards to be restarted.
   * @param requestingUser User performing the restart action.
   * @throws ScheduleException If there are no matching active shards.
   */
  void restartShards(IJobKey jobKey, Set<Integer> shards, String requestingUser)
      throws ScheduleException;

  /**
   * Preempts a task in favor of another.
   *
   * @param task Task being preempted.
   * @param preemptingTask Task we are preempting in favor of.
   * @throws ScheduleException If a problem occurs while trying to perform the preemption.
   */
  void preemptTask(IAssignedTask task, IAssignedTask preemptingTask) throws ScheduleException;

  /**
   * Indicates to the scheduler that tasks were deleted on the assigned host.
   *
   * @param taskIds IDs of tasks that were deleted.
   */
  void tasksDeleted(Set<String> taskIds);
}