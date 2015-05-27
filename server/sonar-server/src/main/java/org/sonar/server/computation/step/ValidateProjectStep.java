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

import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.ComponentKeys;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.db.DbClient;

/**
 * Validate project and modules. It will fail in the following cases :
 * <ol>
 * <li>property {@link org.sonar.api.CoreProperties#CORE_PREVENT_AUTOMATIC_PROJECT_CREATION} is set to true and project does not exists</li>
 * <li>project or module key is not valid</li>
 * <li>branch is not valid</li>
 * <li>module key does not already exists in another project (same module key cannot exists in different projects)</li>
 * <li>module key is not used as a project key</li>
 * </ol>
 */
public class ValidateProjectStep implements ComputationStep {

  private final DbClient dbClient;
  private final Settings settings;

  public ValidateProjectStep(DbClient dbClient, Settings settings) {
    this.dbClient = dbClient;
    this.settings = settings;
  }

  @Override
  public void execute(ComputationContext context) {
    int rootComponentRef = context.getReportMetadata().getRootComponentRef();
    DbSession session = dbClient.openSession(false);
    List<String> validationMessages = new ArrayList<>();
    ValidateProjectContext validateProjectContext = new ValidateProjectContext(context, session, validationMessages);
    try {
      validateProject(validateProjectContext, context.getReportMetadata().getProjectKey());
      String branch = context.getReportMetadata().hasBranch() ? context.getReportMetadata().getBranch() : null;
      validateBranch(validationMessages, branch);
      recursivelyProcessComponent(validateProjectContext, rootComponentRef);

      if (!validationMessages.isEmpty()) {
        throw new IllegalArgumentException("Validation of project failed:\n  o " + Joiner.on("\n  o ").join(validationMessages));
      }
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private void recursivelyProcessComponent(ValidateProjectContext validateProjectContext, int componentRef) {
    BatchReportReader reportReader = validateProjectContext.context.getReportReader();
    BatchReport.Component component = reportReader.readComponent(componentRef);
    String moduleKey = ComponentKeys.createKey(component.getKey(), validateProjectContext.context.getReportMetadata().getBranch());
    if (component.getType().equals(Constants.ComponentType.MODULE)) {
      validateModule(validateProjectContext, moduleKey);
    }

    for (Integer childRef : component.getChildRefList()) {
      recursivelyProcessComponent(validateProjectContext, childRef);
    }
  }

  private void validateProject(ValidateProjectContext validateProjectContext, String projectKey) {
    ComponentDto project = dbClient.componentDao().selectNullableByKey(validateProjectContext.session, projectKey);
    if (project == null && settings.getBoolean(CoreProperties.CORE_PREVENT_AUTOMATIC_PROJECT_CREATION)) {
      validateProjectContext.validationMessages.add(String.format("Unable to scan non-existing project '%s'", projectKey));
    }
    validateKey(validateProjectContext.validationMessages, projectKey);
  }

  private void validateModule(ValidateProjectContext validateProjectContext, String moduleKey){
    validateKey(validateProjectContext.validationMessages, moduleKey);
  }

  private static void validateKey(List<String> validationMessages, String moduleKey){
    if (!ComponentKeys.isValidModuleKey(moduleKey)) {
      validationMessages.add(String.format("\"%s\" is not a valid project or module key. "
        + "Allowed characters are alphanumeric, '-', '_', '.' and ':', with at least one non-digit.", moduleKey));
    }
  }

  private static void validateBranch(List<String> validationMessages, @Nullable String branch) {
    if (StringUtils.isNotEmpty(branch) && !ComponentKeys.isValidBranch(branch)) {
      validationMessages.add(String.format("\"%s\" is not a valid branch name. "
        + "Allowed characters are alphanumeric, '-', '_', '.' and '/'.", branch));
    }
  }

  @Override
  public String getDescription() {
    return "Validate project and modules";
  }

  private static class ValidateProjectContext {
    private final ComputationContext context;
    private final DbSession session;
    private final List<String> validationMessages;

    public ValidateProjectContext(ComputationContext context, DbSession session, List<String> validationMessages) {
      this.context = context;
      this.session = session;
      this.validationMessages = validationMessages;
    }
  }

}
