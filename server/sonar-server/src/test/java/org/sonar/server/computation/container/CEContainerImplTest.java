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
package org.sonar.server.computation.container;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Set;
import org.junit.Test;
import org.picocontainer.ComponentAdapter;
import org.reflections.Reflections;
import org.sonar.core.platform.ComponentContainer;
import org.sonar.server.computation.ReportQueue;
import org.sonar.server.computation.step.ComputationStep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class CEContainerImplTest {
  @Test(expected = NullPointerException.class)
  public void constructor_fails_fast_on_null_container() {
    new CEContainerImpl(null, mock(ReportQueue.Item.class));
  }
  @Test(expected = NullPointerException.class)
  public void constructor_fails_fast_on_null_item() {
    new CEContainerImpl(new ComponentContainer(), null);
  }

  @Test
  public void ce_container_is_not_child_of_specified_container() {
    ComponentContainer parent = new ComponentContainer();
    CEContainerImpl ceContainer = new CEContainerImpl(parent, mock(ReportQueue.Item.class));

    assertThat(parent.getChild()).isNull();
    assertThat(parent.getPicoContainer().removeChildContainer(ceContainer.getPicoContainer())).isFalse();
  }

  @Test
  public void all_steps_from_package_step_are_present_in_container() {
    CEContainerImpl ceContainer = new CEContainerImpl(new ComponentContainer(), mock(ReportQueue.Item.class));

    Set<String> stepsCanonicalNames = retrieveStepPackageStepsCanonicalNames();

    Set<String> typesInContainer = Sets.newHashSet(
      Iterables.transform(
        Iterables.filter(
          Iterables.transform(
            ceContainer.getPicoContainer().getComponentAdapters(),
            ComponentAdapterToImplementationClass.INSTANCE),
          IsComputationStep.INSTANCE),
        ClassToCanonicalName.INSTANCE));

    assertThat(typesInContainer).isEqualTo(stepsCanonicalNames);
  }

  /**
   * Compute set of canonical names of classes implementing ComputationStep in package step using reflection.
   */
  private Set<String> retrieveStepPackageStepsCanonicalNames() {
    Reflections reflections = new Reflections("org.sonar.server.computation.step");

    return Sets.newHashSet(Iterables.transform(reflections.getSubTypesOf(ComputationStep.class), ClassToCanonicalName.INSTANCE));
  }

  private enum ClassToCanonicalName implements Function<Class<?>, String> {
    INSTANCE;

    @Override
    public String apply(Class<?> input) {
      return input.getCanonicalName();
    }
  }

  private enum ComponentAdapterToImplementationClass implements Function<ComponentAdapter<?>, Class<?>> {
    INSTANCE;

    @Override
    public Class<?> apply(ComponentAdapter<?> input) {
      return input.getComponentImplementation();
    }
  }

  private enum IsComputationStep implements Predicate<Class<?>> {
    INSTANCE;

    @Override
    public boolean apply(Class<?> input) {
      return ComputationStep.class.isAssignableFrom(input);
    }
  }
}
