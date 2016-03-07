/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.hive.policy;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import gobblin.annotation.Alpha;
import gobblin.configuration.State;
import gobblin.hive.HiveTable;
import gobblin.hive.spec.HiveSpec;
import gobblin.hive.spec.SimpleHiveSpec;


/**
 * A {@link gobblin.hive.policy.HiveRegistrationPolicy} for registering snapshots.
 *
 * @author ziliu
 */
@Alpha
public class HiveSnapshotRegistrationPolicy extends HiveRegistrationPolicyBase {

  public static final String SNAPSHOT_PATH_PATTERN = "snapshot.path.pattern";

  protected final FileSystem fs;
  protected final Optional<Pattern> snapshotPathPattern;

  protected HiveSnapshotRegistrationPolicy(State props) throws IOException {
    super(props);
    if (props.contains(HiveRegistrationPolicyBase.HIVE_FS_URI)) {
      this.fs = FileSystem.get(URI.create(props.getProp(HiveRegistrationPolicyBase.HIVE_FS_URI)), new Configuration());
    } else {
      this.fs = FileSystem.get(new Configuration());
    }
    this.snapshotPathPattern = props.contains(SNAPSHOT_PATH_PATTERN)
        ? Optional.of(Pattern.compile(props.getProp(SNAPSHOT_PATH_PATTERN))) : Optional.<Pattern> absent();
  }

  /**
   * @param path The root directory of snapshots. This directory may contain zero or more snapshots.
   */
  @Override
  public Collection<HiveSpec> getHiveSpecs(Path path) throws IOException {
    HiveTable table = getTable(path);

    if (table == null) {
      return ImmutableList.<HiveSpec> of();
    } else {
      return ImmutableList
          .<HiveSpec> of(new SimpleHiveSpec.Builder<>(path).withTable(table).withPartition(getPartition(path)).build());
    }
  }

  /**
   * Get a {@link HiveTable} using the latest snapshot (returned by {@link #getLatestSnapshot(Path)}.
   */
  @Override
  protected HiveTable getTable(Path path) throws IOException {
    Path latestSnapshot = getLatestSnapshot(path);
    if (latestSnapshot == null) {
      return null;
    }

    return super.getTable(latestSnapshot);
  }

  /**
   * Get the latest snapshot in the given {@link Path}.
   *
   * <p>
   *   The lastest snapshot is a sub-directory of the input {@link Path} that has the largest folder
   *   name alphabetically. If property {@link #SNAPSHOT_PATH_PATTERN} is set, only those sub-directories
   *   whose full path matches the given pattern are considered.
   * </p>
   */
  protected Path getLatestSnapshot(Path path) throws IOException {
    FileStatus statuses[] = this.fs.listStatus(path, new PathFilter() {

      @Override
      public boolean accept(Path p) {
        try {
          if (!HiveSnapshotRegistrationPolicy.this.fs.isDirectory(p)) {
            return false;
          }
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }

        return !HiveSnapshotRegistrationPolicy.this.snapshotPathPattern.isPresent()
            || HiveSnapshotRegistrationPolicy.this.snapshotPathPattern.get().matcher(p.toString()).matches();
      }
    });

    if (statuses.length == 0) {
      return null;
    }

    Arrays.sort(statuses, new Comparator<FileStatus>() {

      @Override
      public int compare(FileStatus o1, FileStatus o2) {
        return o2.getPath().getName().compareTo(o1.getPath().getName());
      }

    });

    return statuses[0].getPath();
  }
}
