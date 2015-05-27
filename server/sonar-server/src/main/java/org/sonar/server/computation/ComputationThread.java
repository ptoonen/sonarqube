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

import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.server.computation.container.CEContainer;
import org.sonar.server.computation.container.CEContainerFactory;
import org.sonar.server.platform.Platform;

/**
 * This thread pops a report from the queue and integrate it.
 */
public class ComputationThread implements Runnable {

  private static final Logger LOG = Loggers.get(ComputationThread.class);

  private final ReportQueue queue;
  private final Platform platform;
  private final CEContainerFactory containerFactory;

  public ComputationThread(ReportQueue queue, Platform platform, CEContainerFactory containerFactory) {
    this.queue = queue;
    this.platform = platform;
    this.containerFactory = containerFactory;
  }

  @Override
  public void run() {
    ReportQueue.Item item = null;
    try {
      item = queue.pop();
    } catch (Exception e) {
      LOG.error("Failed to pop the queue of analysis reports", e);
    }
    if (item == null) {
      return;
    }

    CEContainer ceContainer = containerFactory.create(platform.getContainer(), item);
    try {
      ceContainer.process();
    } catch (Throwable e) {
      LOG.error(String.format(
        "Failed to process analysis report %d of project %s", item.dto.getId(), item.dto.getProjectKey()), e);
    } finally {
      ceContainer.cleanup();

      removeSilentlyFromQueue(item);
    }
  }

  private void removeSilentlyFromQueue(ReportQueue.Item item) {
    try {
      queue.remove(item);
    } catch (Exception e) {
      LOG.error(String.format("Failed to remove analysis report %d from queue", item.dto.getId()), e);
    }
  }
}
