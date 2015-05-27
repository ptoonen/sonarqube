/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.computation;

import com.google.common.base.Throwables;
import org.apache.commons.io.FileUtils;
import org.sonar.api.config.Settings;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.ZipUtils;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.api.utils.log.Profiler;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.computation.db.AnalysisReportDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.server.activity.Activity;
import org.sonar.server.activity.ActivityService;
import org.sonar.server.computation.component.ComponentTreeBuilders;
import org.sonar.server.computation.language.LanguageRepository;
import org.sonar.server.computation.step.ComputationStep;
import org.sonar.server.computation.step.ComputationSteps;
import org.sonar.server.db.DbClient;
import org.sonar.server.properties.ProjectSettingsFactory;

import javax.annotation.CheckForNull;

import java.io.File;
import java.io.IOException;

import static org.sonar.api.utils.DateUtils.formatDateTimeNullSafe;
import static org.sonar.api.utils.DateUtils.longToDate;
import static org.sonar.core.computation.db.AnalysisReportDto.Status.FAILED;
import static org.sonar.core.computation.db.AnalysisReportDto.Status.SUCCESS;

@ServerSide
public class ComputationService {

  private static final Logger LOG = Loggers.get(ComputationService.class);

  private final DbClient dbClient;
  private final ComputationSteps steps;
  private final ActivityService activityService;
  private final ProjectSettingsFactory projectSettingsFactory;
  private final TempFolder tempFolder;
  private final System2 system;
  private final LanguageRepository languageRepository;

  public ComputationService(DbClient dbClient, ComputationSteps steps, ActivityService activityService,
    ProjectSettingsFactory projectSettingsFactory, TempFolder tempFolder, System2 system,
    LanguageRepository languageRepository) {
    this.dbClient = dbClient;
    this.steps = steps;
    this.activityService = activityService;
    this.projectSettingsFactory = projectSettingsFactory;
    this.tempFolder = tempFolder;
    this.system = system;
    this.languageRepository = languageRepository;
  }

  public void process(ReportQueue.Item item) {
    String projectKey = item.dto.getProjectKey();
    Profiler profiler = Profiler.create(LOG).startDebug(String.format(
      "Analysis of project %s (report %d)", projectKey, item.dto.getId()));

    try {
      File reportDir = extractReportInDir(item);
      BatchReportReader reader = new BatchReportReader(reportDir);
      Settings projectSettings = projectSettingsFactory.newProjectSettings(projectKey);
      ComputationContext context = new ComputationContext(reader, null, projectSettings, dbClient, ComponentTreeBuilders.from(reader), languageRepository);
      for (ComputationStep step : steps.orderedSteps()) {
        Profiler stepProfiler = Profiler.createIfDebug(LOG).startDebug(step.getDescription());
        step.execute(context);
        stepProfiler.stopDebug();
      }
      item.dto.setStatus(SUCCESS);
    } catch (Throwable e) {
      item.dto.setStatus(FAILED);
      throw Throwables.propagate(e);
    } finally {
      item.dto.setFinishedAt(system.now());
      saveActivity(item.dto);
      profiler.stopInfo();
    }
  }

  private File extractReportInDir(ReportQueue.Item item) {
    File dir = tempFolder.newDir();
    try {
      Profiler profiler = Profiler.createIfDebug(LOG).start();
      ZipUtils.unzip(item.zipFile, dir);
      if (profiler.isDebugEnabled()) {
        String message = String.format("Report extracted | size=%s | project=%s",
          FileUtils.byteCountToDisplaySize(FileUtils.sizeOf(dir)), item.dto.getProjectKey());
        profiler.stopDebug(message);
      }
      return dir;
    } catch (IOException e) {
      throw new IllegalStateException(String.format("Fail to unzip %s into %s", item.zipFile, dir), e);
    }
  }

  private void saveActivity(AnalysisReportDto report) {
    ComponentDto project = loadProject(report.getProjectKey());
    Activity activity = new Activity();
    activity.setType(Activity.Type.ANALYSIS_REPORT);
    activity.setAction("LOG_ANALYSIS_REPORT");
    activity
      .setData("key", String.valueOf(report.getId()))
      .setData("projectKey", report.getProjectKey())
      .setData("status", String.valueOf(report.getStatus()))
      .setData("submittedAt", formatDateTimeNullSafe(longToDate(report.getCreatedAt())))
      .setData("startedAt", formatDateTimeNullSafe(longToDate(report.getStartedAt())))
      .setData("finishedAt", formatDateTimeNullSafe(longToDate(report.getFinishedAt())));
    if (project != null) {
      activity
        .setData("projectName", project.name())
        .setData("projectUuid", project.uuid());
    }
    activityService.save(activity);
  }

  @CheckForNull
  private ComponentDto loadProject(String projectKey) {
    DbSession session = dbClient.openSession(false);
    try {
      return dbClient.componentDao().selectNullableByKey(session, projectKey);
    } finally {
      MyBatis.closeQuietly(session);
    }
  }
}
