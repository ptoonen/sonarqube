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

import java.io.File;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.batch.protocol.output.BatchReportWriter;
import org.sonar.core.persistence.DbTester;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.component.ComponentTreeBuilders;
import org.sonar.server.computation.component.DumbComponent;
import org.sonar.server.db.DbClient;

public class ValidateProjectStepTest {

  private static final String PROJECT_KEY = "PROJECT_KEY";

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @ClassRule
  public static DbTester dbTester = new DbTester();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  File reportDir;

  DbClient dbClient;

  Settings settings;

  ValidateProjectStep sut;

  @Before
  public void setUp() throws Exception {
    dbTester.truncateTables();
    dbClient = new DbClient(dbTester.database(), dbTester.myBatis(), new ComponentDao());
    reportDir = temp.newFolder();
    settings = new Settings();

    sut = new ValidateProjectStep(dbClient, settings);
  }

  @Test
  public void not_fail_if_provisioning_enforced_and_project_exists() throws Exception {
    dbTester.prepareDbUnit(getClass(), "project.xml");
    settings.appendProperty(CoreProperties.CORE_PREVENT_AUTOMATIC_PROJECT_CREATION, "true");

    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .setProjectKey(PROJECT_KEY)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .build());

    sut.execute(new ComputationContext(new BatchReportReader(reportDir), null, null, null, ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), null));
  }

  @Test
  public void fail_if_provisioning_enforced_and_project_does_not_exists() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Unable to scan non-existing project '" + PROJECT_KEY + "'");

    settings.appendProperty(CoreProperties.CORE_PREVENT_AUTOMATIC_PROJECT_CREATION, "true");

    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .setProjectKey(PROJECT_KEY)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .build());

    sut.execute(new ComputationContext(new BatchReportReader(reportDir), null, null, null, ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), null));
  }

  @Test
  public void fail_if_provisioning_not_enforced_and_project_does_not_exists() throws Exception {
    settings.appendProperty(CoreProperties.CORE_PREVENT_AUTOMATIC_PROJECT_CREATION, "false");

    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .setProjectKey(PROJECT_KEY)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .build());

    sut.execute(new ComputationContext(new BatchReportReader(reportDir), null, null, null, ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), null));
  }

  @Test
  public void not_fail_on_valid_branch() throws Exception {
    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .setBranch("origin/master")
      .setProjectKey(PROJECT_KEY)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .build());

    sut.execute(new ComputationContext(new BatchReportReader(reportDir), null, null, null, ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), null));
  }

  @Test
  public void fail_on_invalid_branch() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Validation of project failed:\n" +
      "  o \"bran#ch\" is not a valid branch name. Allowed characters are alphanumeric, '-', '_', '.' and '/'.");

    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .setBranch("bran#ch")
      .setProjectKey(PROJECT_KEY)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .build());

    sut.execute(new ComputationContext(new BatchReportReader(reportDir), null, null, null, ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), null));
  }

  @Test
  public void fail_on_invalid_key() throws Exception {
    String invalidProjectKey = "Project\\Key";

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Validation of project failed:\n" +
      "  o \"Project\\Key\" is not a valid project or module key. Allowed characters are alphanumeric, '-', '_', '.' and ':', with at least one non-digit.\n" +
      "  o \"Module$Key\" is not a valid project or module key. Allowed characters are alphanumeric, '-', '_', '.' and ':', with at least one non-digit");

    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .setProjectKey(invalidProjectKey)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(invalidProjectKey)
      .addChildRef(2)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.MODULE)
      .setKey("Module$Key")
      .build());

    sut.execute(new ComputationContext(new BatchReportReader(reportDir), null, null, null, ComponentTreeBuilders.from(DumbComponent.DUMB_PROJECT), null));
  }
}
