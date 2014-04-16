/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.cli.command;

import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListenableFuture;

import com.spotify.helios.cli.JobStatusTable;
import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.JobStatus;
import com.spotify.helios.common.descriptors.TaskStatus;

import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Predicates.containsPattern;
import static com.spotify.helios.cli.Output.jobStatusTable;
import static com.spotify.helios.cli.Utils.allAsMap;
import static net.sourceforge.argparse4j.impl.Arguments.storeTrue;

public class JobStatusCommand extends ControlCommand {

  private final Argument jobArg;
  private final Argument hostArg;
  private final Argument fullArg;

  public JobStatusCommand(final Subparser parser) {
    super(parser);

    parser.help("show job status");

    jobArg = parser.addArgument("-j", "--job")
        .help("Job filter");

    hostArg = parser.addArgument("--host")
        .setDefault("")
        .help("Host pattern");

    fullArg = parser.addArgument("-f")
        .action(storeTrue())
        .help("Print full job and container id's.");
  }

  @Override
  int run(Namespace options, HeliosClient client, PrintStream out, final boolean json)
      throws ExecutionException, InterruptedException {
    final String jobIdString = options.getString(jobArg.getDest());
    final String hostPattern = options.getString(hostArg.getDest());
    final boolean full = options.getBoolean(fullArg.getDest());

    final Set<JobId> jobIds;
    if (Strings.isNullOrEmpty(jobIdString)) {
      jobIds = client.jobs().get().keySet();
    } else {
      // TODO (dano): complain if there were no matching jobs?
      jobIds = client.jobs(jobIdString).get().keySet();
    }

    // TODO (dano): it would sure be nice to be able to report container/task uptime

    final Map<JobId, ListenableFuture<JobStatus>> futures = Maps.newTreeMap();
    for (final JobId jobId : jobIds) {
      futures.put(jobId, client.jobStatus(jobId));
    }
    final Map<JobId, JobStatus> statuses = Maps.newTreeMap();
    statuses.putAll(allAsMap(futures));

    if (json) {
      out.println(Json.asPrettyStringUnchecked(statuses));
      return 0;
    }

    final JobStatusTable table = jobStatusTable(out, full);

    for (final JobId jobId : Ordering.natural().sortedCopy(jobIds)) {
      final JobStatus jobStatus = statuses.get(jobId);

      // Merge hosts without any status into the set of hosts with a reported task status
      final Map<String, TaskStatus> taskStatuses = Maps.newTreeMap();
      taskStatuses.putAll(jobStatus.getTaskStatuses());
      for (final String host : jobStatus.getDeployedHosts()) {
        if (!taskStatuses.containsKey(host)) {
          taskStatuses.put(host, null);
        }
      }

      final FluentIterable<String> matchingHosts = FluentIterable
          .from(taskStatuses.keySet())
          .filter(containsPattern(hostPattern));

      for (final String host : matchingHosts) {
        final Map<String, Deployment> deployments = jobStatus.getDeployments();
        final TaskStatus ts = taskStatuses.get(host);
        final Deployment deployment = (deployments == null) ? null : deployments.get(host);
        table.task(jobId, host, ts, deployment);
      }
    }

    table.print();

    return 0;
  }
}
