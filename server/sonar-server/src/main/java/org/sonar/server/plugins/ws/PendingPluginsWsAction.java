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
package org.sonar.server.plugins.ws;

import org.sonar.api.platform.PluginMetadata;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.core.plugins.DefaultPluginMetadata;
import org.sonar.server.plugins.PluginDownloader;
import org.sonar.server.plugins.ServerPluginJarsInstaller;

import java.util.Collection;
import java.util.List;

import static com.google.common.collect.ImmutableSortedSet.copyOf;
import static com.google.common.io.Resources.getResource;
import static org.sonar.server.plugins.ws.InstalledPluginsWsAction.NAME_KEY_PLUGIN_METADATA_COMPARATOR;

/**
 * Implementation of the {@code pending} action for the Plugins WebService.
 */
public class PendingPluginsWsAction implements PluginsWsAction {

  private static final String ARRAY_INSTALLING = "installing";
  private static final String ARRAY_REMOVING = "removing";
  private static final String OBJECT_ARTIFACT = "artifact";
  private static final String PROPERTY_NAME = "name";

  private final PluginDownloader pluginDownloader;
  private final ServerPluginJarsInstaller serverPluginJarsInstaller;

  public PendingPluginsWsAction(PluginDownloader pluginDownloader, ServerPluginJarsInstaller serverPluginJarsInstaller) {
    this.pluginDownloader = pluginDownloader;
    this.serverPluginJarsInstaller = serverPluginJarsInstaller;
  }

  @Override
  public void define(WebService.NewController controller) {
    controller.createAction("pending")
        .setDescription("Get the list of plugins which will either be installed or removed at the next startup of the SonarQube instance, sorted by archive name")
        .setSince("5.2")
        .setHandler(this)
        .setResponseExample(getResource(this.getClass(), "example-pending_plugins.json"));
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    JsonWriter jsonWriter = response.newJsonWriter();

    jsonWriter.beginObject();

    writeInstalling(jsonWriter);

    writeRemoving(jsonWriter);

    jsonWriter.endObject();
    jsonWriter.close();
  }

  private void writeInstalling(JsonWriter jsonWriter) {
    jsonWriter.name(ARRAY_INSTALLING);
    jsonWriter.beginArray();
    List<DefaultPluginMetadata> plugins = pluginDownloader.getDownloadedPlugins();
    for (PluginMetadata pluginMetadata : copyOf(NAME_KEY_PLUGIN_METADATA_COMPARATOR, plugins)) {
      writePlugin(jsonWriter, pluginMetadata);
    }
    jsonWriter.endArray();
  }

  private void writeRemoving(JsonWriter jsonWriter) {
    jsonWriter.name(ARRAY_REMOVING);
    jsonWriter.beginArray();
    Collection<DefaultPluginMetadata> plugins = serverPluginJarsInstaller.getUninstalledPlugins();
    for (PluginMetadata pluginMetadata : copyOf(NAME_KEY_PLUGIN_METADATA_COMPARATOR, plugins)) {
      writePlugin(jsonWriter, pluginMetadata);
    }
    jsonWriter.endArray();
  }

  private void writePlugin(JsonWriter jsonWriter, PluginMetadata pluginMetadata) {
    jsonWriter.beginObject();

    writeMetadata(jsonWriter, pluginMetadata);

    jsonWriter.prop("homepageUrl", pluginMetadata.getHomepage());
    jsonWriter.prop("issueTrackerUrl", pluginMetadata.getIssueTrackerUrl());

    writeArchive(jsonWriter, pluginMetadata);

    jsonWriter.endObject();
  }

  private void writeMetadata(JsonWriter jsonWriter, PluginMetadata pluginMetadata) {
    jsonWriter.prop("key", pluginMetadata.getKey());
    jsonWriter.prop("name", pluginMetadata.getName());
    jsonWriter.prop("description", pluginMetadata.getDescription());
    jsonWriter.prop("version", pluginMetadata.getVersion());
    jsonWriter.prop("license", pluginMetadata.getLicense());
    jsonWriter.prop("organizationName", pluginMetadata.getOrganization());
    jsonWriter.prop("organizationUrl", pluginMetadata.getOrganizationUrl());
  }

  private void writeArchive(JsonWriter jsonWriter, PluginMetadata pluginMetadata) {
    if (pluginMetadata.getFile() == null) {
      return;
    }

    jsonWriter.name(OBJECT_ARTIFACT);
    jsonWriter.beginObject();
    jsonWriter.prop(PROPERTY_NAME, pluginMetadata.getFile().getName());
    jsonWriter.endObject();
  }
}