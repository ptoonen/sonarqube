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
package org.sonar.batch.index;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.SonarIndex;
import org.sonar.api.batch.bootstrap.ProjectDefinition;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.measure.MetricFinder;
import org.sonar.api.design.Dependency;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.MeasuresFilter;
import org.sonar.api.measures.MeasuresFilters;
import org.sonar.api.resources.Directory;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Resource;
import org.sonar.api.resources.ResourceUtils;
import org.sonar.api.resources.Scopes;
import org.sonar.api.rules.Rule;
import org.sonar.api.rules.Violation;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.SonarException;
import org.sonar.batch.DefaultProjectTree;
import org.sonar.batch.issue.ModuleIssues;
import org.sonar.batch.scan.measure.MeasureCache;
import org.sonar.core.component.ComponentKeys;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultIndex extends SonarIndex {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultIndex.class);

  private static final List<Metric> INTERNAL_METRICS = Arrays.<Metric>asList(
    // Computed by CpdSensor
    CoreMetrics.DUPLICATED_FILES,
    CoreMetrics.DUPLICATED_LINES,
    CoreMetrics.DUPLICATED_BLOCKS,
    // Computed by LinesSensor
    CoreMetrics.LINES
    );

  private static final List<String> DEPRECATED_METRICS_KEYS = Arrays.<String>asList(
    CoreMetrics.DEPENDENCY_MATRIX_KEY,
    CoreMetrics.DIRECTORY_CYCLES_KEY,
    CoreMetrics.DIRECTORY_EDGES_WEIGHT_KEY,
    CoreMetrics.DIRECTORY_FEEDBACK_EDGES_KEY,
    CoreMetrics.DIRECTORY_TANGLE_INDEX_KEY,
    CoreMetrics.DIRECTORY_TANGLES_KEY,
    CoreMetrics.FILE_CYCLES_KEY,
    CoreMetrics.FILE_EDGES_WEIGHT_KEY,
    CoreMetrics.FILE_FEEDBACK_EDGES_KEY,
    CoreMetrics.FILE_TANGLE_INDEX_KEY,
    CoreMetrics.FILE_TANGLES_KEY,
    CoreMetrics.DUPLICATIONS_DATA_KEY
    );

  private final BatchComponentCache resourceCache;
  private final MetricFinder metricFinder;
  private final MeasureCache measureCache;
  // caches
  private Project currentProject;
  private Map<Resource, Bucket> buckets = Maps.newLinkedHashMap();
  private DefaultProjectTree projectTree;
  private ModuleIssues moduleIssues;

  public DefaultIndex(BatchComponentCache resourceCache, DefaultProjectTree projectTree, MetricFinder metricFinder, MeasureCache measureCache) {
    this.resourceCache = resourceCache;
    this.projectTree = projectTree;
    this.metricFinder = metricFinder;
    this.measureCache = measureCache;
  }

  public void start() {
    Project rootProject = projectTree.getRootProject();
    if (StringUtils.isNotBlank(rootProject.getKey())) {
      doStart(rootProject);
    }
  }

  void doStart(Project rootProject) {
    Bucket bucket = new Bucket(rootProject);
    addBucket(rootProject, bucket);
    resourceCache.add(rootProject, null);
    currentProject = rootProject;

    for (Project module : rootProject.getModules()) {
      addModule(rootProject, module);
    }
  }

  private void addBucket(Resource resource, Bucket bucket) {
    buckets.put(resource, bucket);
  }

  private void addModule(Project parent, Project module) {
    ProjectDefinition parentDefinition = projectTree.getProjectDefinition(parent);
    java.io.File parentBaseDir = parentDefinition.getBaseDir();
    ProjectDefinition moduleDefinition = projectTree.getProjectDefinition(module);
    java.io.File moduleBaseDir = moduleDefinition.getBaseDir();
    module.setPath(new PathResolver().relativePath(parentBaseDir, moduleBaseDir));
    addResource(module);
    for (Project submodule : module.getModules()) {
      addModule(module, submodule);
    }
  }

  @Override
  public Project getProject() {
    return currentProject;
  }

  public void setCurrentProject(Project project, ModuleIssues moduleIssues) {
    this.currentProject = project;

    // the following components depend on the current module, so they need to be reloaded.
    this.moduleIssues = moduleIssues;
  }

  /**
   * Keep only project stuff
   */
  public void clear() {
    Iterator<Map.Entry<Resource, Bucket>> it = buckets.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Resource, Bucket> entry = it.next();
      Resource resource = entry.getKey();
      if (!ResourceUtils.isSet(resource)) {
        entry.getValue().clear();
        it.remove();
      }

    }
  }

  @CheckForNull
  @Override
  public Measure getMeasure(Resource resource, org.sonar.api.batch.measure.Metric<?> metric) {
    return getMeasures(resource, MeasuresFilters.metric(metric));
  }

  @CheckForNull
  @Override
  public <M> M getMeasures(Resource resource, MeasuresFilter<M> filter) {
    // Reload resource so that effective key is populated
    Resource indexedResource = getResource(resource);
    if (indexedResource == null) {
      return null;
    }
    Iterable<Measure> unfiltered;
    if (filter instanceof MeasuresFilters.MetricFilter) {
      // optimization
      unfiltered = measureCache.byMetric(indexedResource, ((MeasuresFilters.MetricFilter<M>) filter).filterOnMetricKey());
    } else {
      unfiltered = measureCache.byResource(indexedResource);
    }
    Collection<Measure> all = new ArrayList<>();
    if (unfiltered != null) {
      for (Measure measure : unfiltered) {
        all.add(measure);
      }
    }
    return filter.filter(all);
  }

  @Override
  public Measure addMeasure(Resource resource, Measure measure) {
    Bucket bucket = getBucket(resource);
    if (bucket != null) {
      if (DEPRECATED_METRICS_KEYS.contains(measure.getMetricKey())) {
        // Ignore deprecated metrics
        return null;
      }
      org.sonar.api.batch.measure.Metric metric = metricFinder.findByKey(measure.getMetricKey());
      if (metric == null) {
        throw new SonarException("Unknown metric: " + measure.getMetricKey());
      }
      if (!measure.isFromCore() && INTERNAL_METRICS.contains(metric)) {
        LOG.debug("Metric " + metric.key() + " is an internal metric computed by SonarQube. Provided value is ignored.");
        return measure;
      }
      if (measureCache.contains(resource, measure)) {
        throw new SonarException("Can not add the same measure twice on " + resource + ": " + measure);
      }
      measureCache.put(resource, measure);
    }
    return measure;
  }

  @Override
  public Dependency addDependency(Dependency dependency) {
    return dependency;
  }

  @Override
  public Set<Resource> getResources() {
    return buckets.keySet();
  }

  //
  //
  //
  // VIOLATIONS
  //
  //
  //

  @Override
  public void addViolation(Violation violation, boolean force) {
    Resource resource = violation.getResource();
    if (resource == null) {
      violation.setResource(currentProject);
    } else if (!Scopes.isHigherThanOrEquals(resource, Scopes.FILE)) {
      throw new IllegalArgumentException("Violations are only supported on files, directories and project");
    }

    Rule rule = violation.getRule();
    if (rule == null) {
      LOG.warn("Rule is null. Ignoring violation {}", violation);
      return;
    }

    Bucket bucket = getBucket(resource);
    if (bucket == null) {
      LOG.warn("Resource is not indexed. Ignoring violation {}", violation);
      return;
    }

    // keep a limitation (bug?) of deprecated violations api : severity is always
    // set by sonar. The severity set by plugins is overridden.
    // This is not the case with issue api.
    violation.setSeverity(null);

    violation.setResource(bucket.getResource());
    moduleIssues.initAndAddViolation(violation);
  }

  @Override
  public String getSource(Resource reference) {
    Resource resource = getResource(reference);
    if (resource instanceof File) {
      File file = (File) resource;
      Project module = currentProject;
      ProjectDefinition def = projectTree.getProjectDefinition(module);
      try {
        return FileUtils.readFileToString(new java.io.File(def.getBaseDir(), file.getPath()));
      } catch (IOException e) {
        throw new IllegalStateException("Unable to read file content " + reference, e);
      }
    }
    return null;
  }

  /**
   * Does nothing if the resource is already registered.
   */
  @Override
  public Resource addResource(Resource resource) {
    Bucket bucket = doIndex(resource);
    return bucket != null ? bucket.getResource() : null;
  }

  @Override
  @CheckForNull
  public <R extends Resource> R getResource(@Nullable R reference) {
    Bucket bucket = getBucket(reference);
    if (bucket != null) {
      return (R) bucket.getResource();
    }
    return null;
  }

  @Override
  public List<Resource> getChildren(Resource resource) {
    List<Resource> children = Lists.newLinkedList();
    Bucket bucket = getBucket(resource);
    if (bucket != null) {
      for (Bucket childBucket : bucket.getChildren()) {
        children.add(childBucket.getResource());
      }
    }
    return children;
  }

  @Override
  public Resource getParent(Resource resource) {
    Bucket bucket = getBucket(resource);
    if (bucket != null && bucket.getParent() != null) {
      return bucket.getParent().getResource();
    }
    return null;
  }

  @Override
  public boolean index(Resource resource) {
    Bucket bucket = doIndex(resource);
    return bucket != null;
  }

  private Bucket doIndex(Resource resource) {
    if (resource.getParent() != null) {
      doIndex(resource.getParent());
    }
    return doIndex(resource, resource.getParent());
  }

  @Override
  public boolean index(Resource resource, Resource parentReference) {
    Bucket bucket = doIndex(resource, parentReference);
    return bucket != null;
  }

  private Bucket doIndex(Resource resource, @Nullable Resource parentReference) {
    Bucket bucket = getBucket(resource);
    if (bucket != null) {
      return bucket;
    }

    if (StringUtils.isBlank(resource.getKey())) {
      LOG.warn("Unable to index a resource without key " + resource);
      return null;
    }

    Resource parent = null;
    if (!ResourceUtils.isLibrary(resource)) {
      // a library has no parent
      parent = (Resource) ObjectUtils.defaultIfNull(parentReference, currentProject);
    }

    Bucket parentBucket = getBucket(parent);
    if (parentBucket == null && parent != null) {
      LOG.warn("Resource ignored, parent is not indexed: " + resource);
      return null;
    }

    if (ResourceUtils.isProject(resource) || /* For technical projects */ResourceUtils.isRootProject(resource)) {
      resource.setEffectiveKey(resource.getKey());
    } else {
      resource.setEffectiveKey(ComponentKeys.createEffectiveKey(currentProject, resource));
    }
    bucket = new Bucket(resource).setParent(parentBucket);
    addBucket(resource, bucket);

    Resource parentResource = parentBucket != null ? parentBucket.getResource() : null;
    resourceCache.add(resource, parentResource);

    return bucket;
  }

  @Override
  public boolean isExcluded(@Nullable Resource reference) {
    return false;
  }

  @Override
  public boolean isIndexed(@Nullable Resource reference, boolean acceptExcluded) {
    return getBucket(reference) != null;
  }

  private Bucket getBucket(@Nullable Resource reference) {
    if (reference == null) {
      return null;
    }
    if (StringUtils.isNotBlank(reference.getKey())) {
      return buckets.get(reference);
    }
    String relativePathFromSourceDir = null;
    boolean isTest = false;
    boolean isDir = false;
    if (reference instanceof File) {
      File referenceFile = (File) reference;
      isTest = Qualifiers.UNIT_TEST_FILE.equals(referenceFile.getQualifier());
      relativePathFromSourceDir = referenceFile.relativePathFromSourceDir();
    } else if (reference instanceof Directory) {
      isDir = true;
      Directory referenceDir = (Directory) reference;
      relativePathFromSourceDir = referenceDir.relativePathFromSourceDir();
      if (Directory.ROOT.equals(relativePathFromSourceDir)) {
        relativePathFromSourceDir = "";
      }
    }
    if (relativePathFromSourceDir != null) {
      // Resolve using deprecated key
      List<java.io.File> dirs;
      if (isTest) {
        dirs = getProject().getFileSystem().getTestDirs();
      } else {
        dirs = getProject().getFileSystem().getSourceDirs();
      }
      for (java.io.File src : dirs) {
        java.io.File abs = new java.io.File(src, relativePathFromSourceDir);
        Bucket b = getBucket(isDir ? Directory.fromIOFile(abs, getProject()) : File.fromIOFile(abs, getProject()));
        if (b != null) {
          return b;
        }
      }

    }
    return null;
  }

}
