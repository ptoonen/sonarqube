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

package org.sonar.server.es.request;

import org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheRequestBuilder;
import org.elasticsearch.common.unit.TimeValue;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.config.Settings;
import org.sonar.core.profiling.Profiling;
import org.sonar.server.es.EsTester;
import org.sonar.server.search.SearchClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class ProxyClearCacheRequestBuilderTest {

  @Rule
  public EsTester esTester = new EsTester();

  @Test
  public void clear_cache() {
    ClearIndicesCacheRequestBuilder requestBuilder = esTester.client().prepareClearCache();
    requestBuilder.get();
  }

  @Test
  public void to_string() {
    assertThat(esTester.client().prepareClearCache().toString()).isEqualTo("ES clear cache request");
    assertThat(esTester.client().prepareClearCache("rules").toString()).isEqualTo("ES clear cache request on indices 'rules'");
    assertThat(esTester.client().prepareClearCache().setFields("key").toString()).isEqualTo("ES clear cache request on fields 'key'");
    assertThat(esTester.client().prepareClearCache().setFilterKeys("rule1").toString()).isEqualTo("ES clear cache request on filterKeys 'rule1'");
    assertThat(esTester.client().prepareClearCache().setFilterCache(true).toString()).isEqualTo("ES clear cache request with filter cache");
    assertThat(esTester.client().prepareClearCache().setFieldDataCache(true).toString()).isEqualTo("ES clear cache request with field data cache");
    assertThat(esTester.client().prepareClearCache().setIdCache(true).toString()).isEqualTo("ES clear cache request with id cache");
  }

  @Test
  public void with_profiling_basic() {
    Profiling profiling = new Profiling(new Settings().setProperty(Profiling.CONFIG_PROFILING_LEVEL, Profiling.Level.BASIC.name()));
    SearchClient searchClient = new SearchClient(new Settings(), profiling);

    ClearIndicesCacheRequestBuilder requestBuilder = esTester.client().prepareClearCache();
    requestBuilder.get();

    // TODO assert profiling
    searchClient.stop();
  }

  @Test
  public void get_with_string_timeout_is_not_yet_implemented() throws Exception {
    try {
      esTester.client().prepareClearCache().get("1");
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("Not yet implemented");
    }
  }

  @Test
  public void get_with_time_value_timeout_is_not_yet_implemented() throws Exception {
    try {
      esTester.client().prepareClearCache().get(TimeValue.timeValueMinutes(1));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("Not yet implemented");
    }
  }

  @Test
  public void execute_should_throw_an_unsupported_operation_exception() throws Exception {
    try {
      esTester.client().prepareClearCache().execute();
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(UnsupportedOperationException.class).hasMessage("execute() should not be called as it's used for asynchronous");
    }
  }

}