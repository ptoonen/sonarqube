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
package org.sonar.server.computation.step;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.config.Settings;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.core.persistence.DbTester;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.batch.BatchReportReaderRule;
import org.sonar.server.computation.component.ComponentTreeBuilders;
import org.sonar.server.computation.component.ComputeComponentsRefCache;
import org.sonar.server.computation.component.DumbComponent;
import org.sonar.server.computation.issue.IssueComputation;
import org.sonar.server.computation.language.LanguageRepository;
import org.sonar.server.db.DbClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ParseReportStepTest extends BaseStepTest {

  private static final String PROJECT_KEY = "PROJECT_KEY";

  private static final List<BatchReport.Issue> ISSUES_ON_DELETED_COMPONENT = Arrays.asList(BatchReport.Issue.newBuilder()
    .setUuid("DELETED_ISSUE_UUID")
    .build());

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();
  @Rule
  public BatchReportReaderRule reportReader = new BatchReportReaderRule();

  @ClassRule
  public static DbTester dbTester = new DbTester();

  IssueComputation issueComputation = mock(IssueComputation.class);
  ComputeComponentsRefCache computeComponentsRefCache = new ComputeComponentsRefCache();

  ParseReportStep sut = new ParseReportStep(issueComputation, computeComponentsRefCache, reportReader);

  @Test
  public void extract_report_from_db_and_browse_components() throws Exception {
    computeComponentsRefCache.addComponent(1, new ComputeComponentsRefCache.ComputeComponent(PROJECT_KEY, "PROJECT_UUID"));
    computeComponentsRefCache.addComponent(2, new ComputeComponentsRefCache.ComputeComponent("PROJECT_KEY:file1", "FILE1_UUID"));
    computeComponentsRefCache.addComponent(3, new ComputeComponentsRefCache.ComputeComponent("PROJECT_KEY:file2", "FILE2_UUID"));

    generateReport();

    ComputationContext context = new ComputationContext(reportReader, PROJECT_KEY, new Settings(),
        mock(DbClient.class), ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), mock(LanguageRepository.class));
    sut.execute(context);

    assertThat(context.getReportMetadata().getRootComponentRef()).isEqualTo(1);
    assertThat(context.getReportMetadata().getDeletedComponentsCount()).isEqualTo(1);

    // verify that all components are processed (currently only for issues)
    verify(issueComputation).processComponentIssues(context, Collections.<BatchReport.Issue>emptyList(), "PROJECT_UUID", 1, PROJECT_KEY, "PROJECT_UUID");
    verify(issueComputation).processComponentIssues(context, Collections.<BatchReport.Issue>emptyList(), "FILE1_UUID", 2, PROJECT_KEY, "PROJECT_UUID");
    verify(issueComputation).processComponentIssues(context, Collections.<BatchReport.Issue>emptyList(), "FILE2_UUID", 3, PROJECT_KEY, "PROJECT_UUID");
    verify(issueComputation).processComponentIssues(context, ISSUES_ON_DELETED_COMPONENT, "DELETED_UUID", null, PROJECT_KEY, "PROJECT_UUID");
    verify(issueComputation).afterReportProcessing();
  }

  private void generateReport() throws IOException {
    // project and 2 files
    reportReader.setMetadata(BatchReport.Metadata.newBuilder()
        .setRootComponentRef(1)
        .setProjectKey(PROJECT_KEY)
        .setAnalysisDate(150000000L)
        .setDeletedComponentsCount(1)
        .build());

    reportReader.putComponent(BatchReport.Component.newBuilder()
        .setRef(1)
        .setType(Constants.ComponentType.PROJECT)
        .addChildRef(2)
        .addChildRef(3)
        .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
        .setRef(2)
        .setType(Constants.ComponentType.FILE)
        .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
        .setRef(3)
        .setType(Constants.ComponentType.FILE)
        .build());

    // deleted components
    BatchReport.Issues.Builder issuesBuilder = BatchReport.Issues.newBuilder();
    issuesBuilder.setComponentRef(1);
    issuesBuilder.setComponentUuid("DELETED_UUID");
    issuesBuilder.addAllIssue(ISSUES_ON_DELETED_COMPONENT);
    reportReader.putDeletedIssues(1, issuesBuilder.build());
  }

  @Override
  protected ComputationStep step() {
    return sut;
  }
}
