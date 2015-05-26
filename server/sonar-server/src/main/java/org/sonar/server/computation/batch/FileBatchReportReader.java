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
package org.sonar.server.computation.batch;

import com.google.common.base.Throwables;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import javax.annotation.CheckForNull;
import org.apache.commons.io.FileUtils;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.server.util.CloseableIterator;

public class FileBatchReportReader implements BatchReportReader {
  private final org.sonar.batch.protocol.output.BatchReportReader delegate;

  public FileBatchReportReader(org.sonar.batch.protocol.output.BatchReportReader delegate) {
    this.delegate = delegate;
  }

  @Override
  public BatchReport.Metadata readMetadata() {
    return delegate.readMetadata();
  }

  @Override
  public List<BatchReport.Measure> readComponentMeasures(int componentRef) {
    return delegate.readComponentMeasures(componentRef);
  }

  @Override
  @CheckForNull
  public BatchReport.Changesets readChangesets(int componentRef) {
    return delegate.readChangesets(componentRef);
  }

  @Override
  public BatchReport.Component readComponent(int componentRef) {
    return delegate.readComponent(componentRef);
  }

  @Override
  public List<BatchReport.Issue> readComponentIssues(int componentRef) {
    return delegate.readComponentIssues(componentRef);
  }

  @Override
  public BatchReport.Issues readDeletedComponentIssues(int deletedComponentRef) {
    return delegate.readDeletedComponentIssues(deletedComponentRef);
  }

  @Override
  public List<BatchReport.Duplication> readComponentDuplications(int componentRef) {
    return delegate.readComponentDuplications(componentRef);
  }

  @Override
  public List<BatchReport.Symbols.Symbol> readComponentSymbols(int componentRef) {
    return delegate.readComponentSymbols(componentRef);
  }

  @Override
  @CheckForNull
  public CloseableIterator<BatchReport.SyntaxHighlighting> readComponentSyntaxHighlighting(int fileRef) {
    File file = delegate.readComponentSyntaxHighlighting(fileRef);
    if (file == null) {
      return CloseableIterator.emptyCloseableIterator();
    }

    try {
      return new ParserCloseableIterator<>(BatchReport.SyntaxHighlighting.PARSER, FileUtils.openInputStream(file));
    } catch (IOException e) {
      Throwables.propagate(e);
      // actually never reached
      return CloseableIterator.emptyCloseableIterator();
    }
  }

  @Override
  public CloseableIterator<BatchReport.Coverage> readComponentCoverage(int fileRef) {
    File file = delegate.readComponentCoverage(fileRef);
    if (file == null) {
      return CloseableIterator.emptyCloseableIterator();
    }

    try {
      return new ParserCloseableIterator<>(BatchReport.Coverage.PARSER, FileUtils.openInputStream(file));
    } catch (IOException e) {
      Throwables.propagate(e);
      // actually never reached
      return CloseableIterator.emptyCloseableIterator();
    }
  }

  @Override
  public File readFileSource(int fileRef) {
    return delegate.readFileSource(fileRef);
  }

  @Override
  @CheckForNull
  public File readTests(int testFileRef) {
    return delegate.readTests(testFileRef);
  }

  @Override
  public CloseableIterator<BatchReport.CoverageDetail> readCoverageDetails(int testFileRef) {
    File file = delegate.readCoverageDetails(testFileRef);
    if (file == null) {
      return CloseableIterator.emptyCloseableIterator();
    }

    try {
      return new ParserCloseableIterator<>(BatchReport.CoverageDetail.PARSER, FileUtils.openInputStream(file));
    } catch (IOException e) {
      Throwables.propagate(e);
      // actually never reached
      return CloseableIterator.emptyCloseableIterator();
    }
  }

  private static class ParserCloseableIterator<T> extends CloseableIterator<T> {
    private final Parser<T> parser;
    private final FileInputStream fileInputStream;

    public ParserCloseableIterator(Parser<T> parser, FileInputStream fileInputStream) {
      this.parser = parser;
      this.fileInputStream = fileInputStream;
    }

    @Override
    protected T doNext() {
      try {
        return parser.parseDelimitedFrom(fileInputStream);
      } catch (InvalidProtocolBufferException e) {
        Throwables.propagate(e);
        // actually never reached
        return null;
      }
    }

    @Override
    protected void doClose() throws Exception {
      fileInputStream.close();
    }
  }
}
