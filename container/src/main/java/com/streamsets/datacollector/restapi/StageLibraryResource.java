/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.restapi;

import com.google.common.annotations.VisibleForTesting;
import com.streamsets.datacollector.classpath.ClasspathValidatorResult;
import com.streamsets.datacollector.cluster.TarFileCreator;
import com.streamsets.datacollector.config.ConnectionDefinition;
import com.streamsets.datacollector.config.ServiceDefinition;
import com.streamsets.datacollector.config.StageDefinition;
import com.streamsets.datacollector.config.StageLibraryDefinition;
import com.streamsets.datacollector.definition.ConcreteELDefinitionExtractor;
import com.streamsets.datacollector.el.RuntimeEL;
import com.streamsets.datacollector.execution.alerts.DataRuleEvaluator;
import com.streamsets.datacollector.main.BuildInfo;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.restapi.bean.BeanHelper;
import com.streamsets.datacollector.restapi.bean.ConnectionDefinitionJson;
import com.streamsets.datacollector.restapi.bean.ConnectionsJson;
import com.streamsets.datacollector.restapi.bean.DefinitionsJson;
import com.streamsets.datacollector.restapi.bean.PipelineDefinitionJson;
import com.streamsets.datacollector.restapi.bean.PipelineFragmentDefinitionJson;
import com.streamsets.datacollector.restapi.bean.PipelineRulesDefinitionJson;
import com.streamsets.datacollector.restapi.bean.StageDefinitionJson;
import com.streamsets.datacollector.restapi.bean.StageLibraryExtrasJson;
import com.streamsets.datacollector.restapi.bean.StageLibraryInfoJson;
import com.streamsets.datacollector.stagelibrary.StageLibraryTask;
import com.streamsets.datacollector.stagelibrary.StageLibraryUtil;
import com.streamsets.datacollector.util.AuthzRole;
import com.streamsets.datacollector.util.RestException;
import com.streamsets.datacollector.util.Version;
import com.streamsets.pipeline.api.HideStage;
import com.streamsets.pipeline.api.impl.Utils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/v1")
@Api(value = "definitions")
@DenyAll
@RequiresCredentialsDeployed
public class StageLibraryResource {
  private static final String DEFAULT_ICON_FILE = "PipelineDefinition-bundle.properties";
  private static final String PNG_MEDIA_TYPE = "image/png";
  private static final String SVG_MEDIA_TYPE = "image/svg+xml";
  private static final String EMPTY_FILE = ".empty";
  private static final String STAGE_LIB_JARS_DIR = "lib";
  private static final String STAGE_LIB_CONF_DIR = "etc";

  @VisibleForTesting
  static final String STAGES = "stages";
  @VisibleForTesting
  static final String PIPELINE = "pipeline";
  @VisibleForTesting
  static final String RULES_EL_METADATA = "rulesElMetadata";
  @VisibleForTesting
  static final String EL_CONSTANT_DEFS = "elConstantDefinitions";
  @VisibleForTesting
  static final String EL_FUNCTION_DEFS = "elFunctionDefinitions";

  private final StageLibraryTask stageLibrary;
  private final BuildInfo buildInfo;
  private final RuntimeInfo runtimeInfo;
  private final Version sdcVersion;

  @Inject
  public StageLibraryResource(StageLibraryTask stageLibrary, BuildInfo buildInfo, RuntimeInfo runtimeInfo) {
    this.stageLibrary = stageLibrary;
    this.buildInfo = buildInfo;
    this.runtimeInfo = runtimeInfo;
    this.sdcVersion = new Version(buildInfo.getVersion());
  }

  @GET
  @Path("/definitions")
  @ApiOperation(value = "Returns pipeline & stage configuration definitions", response = DefinitionsJson.class,
      authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getDefinitions(
      @QueryParam("hideStage") final HideStage.Type hideStage,
      @QueryParam("schemaVersion") final String schemaVersion
  ) {
    DefinitionsJson definitions = getDefinitionsJson(stageLibrary, hideStage, schemaVersion);
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(definitions).build();
  }

  public static DefinitionsJson getDefinitionsJson(
      StageLibraryTask stageLibrary,
      HideStage.Type hideStage,
      String schemaVersion
  ) {
    DefinitionsJson definitions = new DefinitionsJson();

    // Populate the definitions with all the stage definitions
    List<StageDefinition> stageDefinitions = stageLibrary.getStages();

    // Filter based on the hideStage if specified
    if (hideStage != null) {
      stageDefinitions = stageDefinitions
          .stream()
          .filter(stageDefinition -> stageDefinition.getHideStage().contains(hideStage))
          .collect(Collectors.toList());
    }

    List<StageDefinitionJson> stages = new ArrayList<>(stageDefinitions.size());
    stages.addAll(BeanHelper.wrapStageDefinitions(stageDefinitions));
    definitions.setStages(stages);

    // Populate the definitions with the PipelineDefinition
    List<PipelineDefinitionJson> pipeline = new ArrayList<>(1);
    pipeline.add(BeanHelper.wrapPipelineDefinition(stageLibrary.getPipeline()));
    definitions.setPipeline(pipeline);

    // Populate the definitions with the PipelineFragmentDefinition
    List<PipelineFragmentDefinitionJson> pipelineFragment = new ArrayList<>(1);
    pipelineFragment.add(BeanHelper.wrapPipelineFragmentDefinition(stageLibrary.getPipelineFragment()));
    definitions.setPipelineFragment(pipelineFragment);

    // Populate service definitions
    List<ServiceDefinition> serviceDefinitions = stageLibrary.getServiceDefinitions();
    definitions.setServices(BeanHelper.wrapServiceDefinitions(serviceDefinitions));

    //Populate the definitions with the PipelineRulesDefinition
    List<PipelineRulesDefinitionJson> pipelineRules = new ArrayList<>(1);
    pipelineRules.add(BeanHelper.wrapPipelineRulesDefinition(stageLibrary.getPipelineRules()));
    definitions.setPipelineRules(pipelineRules);

    definitions.setRulesElMetadata(DataRuleEvaluator.getELDefinitions());

    Map<String, Object> map = new HashMap<>();
    map.put(EL_FUNCTION_DEFS,
        BeanHelper.wrapElFunctionDefinitionsIdx(ConcreteELDefinitionExtractor.get().getElFunctionsCatalog()));
    map.put(EL_CONSTANT_DEFS,
        BeanHelper.wrapElConstantDefinitionsIdx(ConcreteELDefinitionExtractor.get().getELConstantsCatalog()));
    definitions.setElCatalog(map);

    definitions.setRuntimeConfigs(RuntimeEL.getRuntimeConfKeys());

    definitions.setLegacyStageLibs(stageLibrary.getLegacyStageLibs());

    definitions.setEventDefinitions(stageLibrary.getEventDefinitions());

    if (schemaVersion != null && schemaVersion.equals("2")) {
      // We package the same stage in multiple libraries like the “Hadoop FS” stage is packaged in stage
      // library “Azure,” “HDP 3.1.0” and “CDP 7.1” in the case local dist build and packaged ten times
      // inside the “streamsets-datacollector-all-3.22.0-SNAPSHOT.tgz”.
      // By moving data structure from List<StageConfiguration> to List<StageDefinitionMinimalJson> and
      // Map<String, StageConfiguration>, we can reduce payload size
      definitions.setSchemaVersion(schemaVersion);
      definitions.setStageDefinitionMinimalList(stageLibrary.getStageDefinitionMinimalList());
      definitions.setStageDefinitionMap(generateStageDefinitionMap(definitions.getStages()));
      definitions.setStages(Collections.emptyList());
    }

    return definitions;
  }

  private static Map<String, StageDefinitionJson> generateStageDefinitionMap(
      List<StageDefinitionJson> stageDefinitionJsonList
  ) {
    Map<String, StageDefinitionJson> stageDefinitionMap = new HashMap<>();
    for (StageDefinitionJson stageDefinitionJson: stageDefinitionJsonList) {
      String key = stageDefinitionJson.getName() + "::" + stageDefinitionJson.getVersion();
      if (!stageDefinitionMap.containsKey(key)) {
        stageDefinitionMap.put(key, stageDefinitionJson);
      }
    }
    return stageDefinitionMap;
  }

  @GET
  @Path("/definitions/stages/{library}/{stageName}/icon")
  @ApiOperation(value = "Return stage icon for library and stage name", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @Produces({SVG_MEDIA_TYPE, PNG_MEDIA_TYPE})
  @PermitAll
  public Response getIcon(@PathParam("library") String library, @PathParam("stageName") String name) {
    StageDefinition stage = Utils.checkNotNull(stageLibrary.getStage(library, name, false),
        Utils.formatL("Could not find stage library: {}, name: {}", library, name));
    String iconFile = DEFAULT_ICON_FILE;
    String responseType = SVG_MEDIA_TYPE;

    if(stage.getIcon() != null && !stage.getIcon().isEmpty()) {
      iconFile = stage.getIcon();
    }

    final InputStream resourceAsStream = stage.getStageClassLoader().getResourceAsStream(iconFile);

    if(iconFile.endsWith(".png"))
      responseType = PNG_MEDIA_TYPE;

    return Response.ok().type(responseType).entity(resourceAsStream).build();
  }

  @GET
  @Path("/stageLibraries/list")
  @ApiOperation(value = "Return list of libraries", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response getLibraries() {
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(stageLibrary.getRepositoryManifestList()).build();
  }

  @GET
  @Path("/loadedStageLibraries")
  @ApiOperation(
      value = "Return list of loaded stage library id and label",
      response = Object.class,
      authorizations = @Authorization(value = "basic"),
      hidden = true
  )
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response getLoadedStageLibraries() {
    List<StageLibraryInfoJson> stageLibraryInfoJsonList = stageLibrary.getLoadedStageLibraries()
        .stream()
        .map(s -> new StageLibraryInfoJson(s.getName(), s.getLabel()))
        .collect(Collectors.toList());
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(stageLibraryInfoJsonList).build();
  }

  @POST
  @Path("/stageLibraries/install")
  @ApiOperation(value = "Install Stage libraries", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response installLibraries(
      @QueryParam("withStageLibVersion") boolean withStageLibVersion,
      List<String> libraryIdList
  ) throws Exception {
    String runtimeDir = runtimeInfo.getRuntimeDir();
    String version = buildInfo.getVersion();

    // Find Stage Lib location to each stage library that we should install
    Map<String, String> libraryUrlList = StageLibraryUtil.getLibraryUrlList(
        stageLibrary.getRepositoryManifestList(),
        sdcVersion,
        withStageLibVersion,
        libraryIdList
    );

    StageLibraryUtil.installStageLibs(
        runtimeDir,
        version,
        libraryUrlList,
        libId -> validateStageLibPresence(runtimeDir, libId)
    );

    return Response.ok().build();
  }

  private void validateStageLibPresence(String runtimeDir, String libraryId) throws Exception {
    // We currently don't support re-installing libraries, they have to be explicitly uninstalled first
    Optional<StageLibraryDefinition> installedLibrary = stageLibrary.getLoadedStageLibraries().stream()
        .filter(lib -> libraryId.equals(lib.getName()))
        .findFirst();
    // In case that the library was installed, but SDC wasn't rebooted
    File libraryDirectory = new File(runtimeDir + StageLibraryUtil.STREAMSETS_LIBS_PATH + libraryId);
    if(installedLibrary.isPresent() || libraryDirectory.exists()) {
      throw new RestException(RestErrors.REST_1002, libraryId, installedLibrary.isPresent() ? installedLibrary.get().getVersion() : "Unknown");
    }
  }

  @POST
  @Path("/stageLibraries/uninstall")
  @ApiOperation(value = "Uninstall Stage libraries", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response uninstallLibraries(
      List<String> libraryList
  ) throws IOException, RestException {
    String runtimeDir = runtimeInfo.getRuntimeDir();
    for (String libraryId : libraryList) {
      if (!libraryId.matches("[a-zA-Z0-9_-]+")) {
        throw new RestException(RestErrors.REST_1005, libraryId);
      }

      File libraryDirectory = new File(runtimeDir + StageLibraryUtil.STREAMSETS_LIBS_PATH + libraryId);
      if (libraryDirectory.exists()) {
        FileUtils.deleteDirectory(libraryDirectory);
      }
    }
    return Response.ok().build();
  }

  @GET
  @Path("/stageLibraries/extras/list")
  @ApiOperation(value = "Return list of additional drivers", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response getExtras(
      @QueryParam("libraryId") String libraryId
  ) throws RestException {
    String libsExtraDir = runtimeInfo.getLibsExtraDir();
    if (StringUtils.isEmpty(libsExtraDir)) {
      throw new RestException(RestErrors.REST_1004);
    }

    List<StageLibraryExtrasJson> extrasList = new ArrayList<>();
    List<StageDefinition> stageDefinitions = stageLibrary.getStages();
    Map<String, Boolean> installedLibrariesMap = new HashMap<>();
    for (StageDefinition stageDefinition: stageDefinitions) {
      if (!installedLibrariesMap.containsKey(stageDefinition.getLibrary()) &&
          (StringUtils.isEmpty(libraryId) || stageDefinition.getLibrary().equals(libraryId))) {
        installedLibrariesMap.put(stageDefinition.getLibrary(), true);
        File stageLibExtraDir = new File(libsExtraDir, stageDefinition.getLibrary());
        if (stageLibExtraDir.exists()) {
          File extraJarsDir = new File(stageLibExtraDir, STAGE_LIB_JARS_DIR);
          addExtras(extraJarsDir, stageDefinition.getLibrary(), extrasList, false);
          File extraEtc = new File(stageLibExtraDir, STAGE_LIB_CONF_DIR);
          addExtras(extraEtc, stageDefinition.getLibrary(), extrasList, false);
        }
      }
    }
    return Response.ok()
        .type(MediaType.APPLICATION_JSON)
        .entity(extrasList)
        .build();
  }

  private void addExtras(
      File extraJarsDir,
      String libraryId,
      List<StageLibraryExtrasJson> extrasList,
      boolean nested
  ) {
    if (extraJarsDir != null && extraJarsDir.exists()) {
      File[] files = extraJarsDir.listFiles();
      if (files != null ) {
        for ( File f : files) {
          if (f.isDirectory() && nested) {
            addExtras(f, libraryId, extrasList, nested);
          } else if (!EMPTY_FILE.equals(f.getName())) {
            extrasList.add(new StageLibraryExtrasJson(f.getAbsolutePath(), libraryId, f.getName()));
          }
        }
      }
    }
  }

  @POST
  @Path("/stageLibraries/extras/{library}/upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  @ApiOperation(value = "Install additional drivers", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response installExtras(
      @PathParam("library") String library,
      @FormDataParam("file") InputStream uploadedInputStream,
      @FormDataParam("file") FormDataContentDisposition fileDetail
  ) throws IOException, RestException {
    String libsExtraDir = runtimeInfo.getLibsExtraDir();
    if (StringUtils.isEmpty(libsExtraDir)) {
      throw new RestException(RestErrors.REST_1004);
    }

    File additionalLibraryFile = new File(
        libsExtraDir + "/"	+ library + "/" + STAGE_LIB_JARS_DIR,
        fileDetail.getFileName()
    );
    File parent = additionalLibraryFile.getParentFile();
    if (!parent.exists()) {
      if (!parent.mkdirs()) {
        throw new RestException(RestErrors.REST_1003, parent.getName());
      }
    }
    saveFile(uploadedInputStream, additionalLibraryFile);
    return Response.ok().build();
  }

  private void saveFile(InputStream uploadedInputStream, File additionalLibraryFile) throws IOException {
    try (OutputStream outputStream = new FileOutputStream(additionalLibraryFile)) {
      IOUtils.copy(uploadedInputStream, outputStream);
    }
  }

  @POST
  @Path("/stageLibraries/extras/delete")
  @ApiOperation(value = "Delete additional drivers", response = Object.class,
      authorizations = @Authorization(value = "basic"))
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response deleteExtras(
      List<StageLibraryExtrasJson> extrasList
  ) throws IOException, RestException {
    String libsExtraDir = runtimeInfo.getLibsExtraDir();
    if (StringUtils.isEmpty(libsExtraDir)) {
      throw new RestException(RestErrors.REST_1004);
    }
    for (StageLibraryExtrasJson extrasJson : extrasList) {
      File additionalLibraryFile = new File(libsExtraDir + "/"	+
          extrasJson.getLibraryId() + "/" + STAGE_LIB_JARS_DIR, extrasJson.getFileName());
      if (additionalLibraryFile.exists()) {
        FileUtils.forceDelete(additionalLibraryFile);
      }
    }
    return Response.ok().build();
  }

  @GET
  @Path("/externalResources/download")
  @ApiOperation(
      value = "Download the external resources directory",
      response = Object.class,
      authorizations = @Authorization(value = "basic")
  )
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response downloadExternalLibraries() throws IOException, RestException {
    if (StringUtils.isEmpty(runtimeInfo.getLibsExtraDir())) {
      throw new RestException(RestErrors.REST_1004);
    }
    File externalResourcesDir = new File(runtimeInfo.getExternalResourcesDir());
    String stagingDir = Files.createTempDirectory("externalResources").toFile().getAbsolutePath();
    File resourcesTarGz = new File(stagingDir, "externalResources.tar.gz");
    TarFileCreator.createTarGz(externalResourcesDir, resourcesTarGz);
    StreamingOutput streamingOutput = output -> {
      byte[] data = Files.readAllBytes(resourcesTarGz.toPath());
      output.write(data);
      output.flush();
    };
    return Response.ok(streamingOutput)
        .header("Content-Disposition", "attachment; filename=\"externalResources.tar.gz\"")
        .build();
  }

  @GET
  @Path("/userStageLibraries/list")
  @ApiOperation(
      value = "Return list of user stage libraries information",
      response = Object.class,
      authorizations = @Authorization(value = "basic")
  )
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response getUserStageLibrariesNames() {
    List<StageLibraryExtrasJson> extrasList = new ArrayList<>();
    File userLibsDir = new File(runtimeInfo.getUserLibsDir());
    if (userLibsDir.exists()) {
      addExtras(userLibsDir, null, extrasList, false);
    }
    return Response.ok()
        .type(MediaType.APPLICATION_JSON)
        .entity(extrasList)
        .build();
  }

  @GET
  @Path("/resources/list")
  @ApiOperation(
      value = "Return list of resource files",
      response = Object.class,
      authorizations = @Authorization(value = "basic")
  )
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response getResourcesFileNames() {
    List<StageLibraryExtrasJson> extrasList = new ArrayList<>();
    File resourcesDir = new File(runtimeInfo.getResourcesDir());
    if (resourcesDir.exists()) {
      addExtras(resourcesDir, null, extrasList, true);
    }
    return Response.ok()
        .type(MediaType.APPLICATION_JSON)
        .entity(extrasList)
        .build();
  }

  @POST
  @Path("/resources/upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @ApiOperation(
      value = "Upload resources",
      response = Object.class,
      authorizations = @Authorization(value = "basic")
  )
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response uploadResourcesFile(
      @FormDataParam("file") InputStream uploadedInputStream,
      @FormDataParam("file") FormDataContentDisposition fileDetail
  ) throws IOException, RestException {
    String resourcesDir = runtimeInfo.getResourcesDir();
    if (StringUtils.isEmpty(resourcesDir)) {
      throw new RestException(RestErrors.REST_1004);
    }
    File resourceFile = new File(resourcesDir, fileDetail.getFileName());
    File parent = resourceFile.getParentFile();
    if (!parent.exists()) {
      if (!parent.mkdirs()) {
        throw new RestException(RestErrors.REST_1003, parent.getName());
      }
    }
    saveFile(uploadedInputStream, resourceFile);
    return Response.ok().build();
  }

  @POST
  @Path("/resources/delete")
  @ApiOperation(
      value = "Delete resources file",
      response = Object.class,
      authorizations = @Authorization(value = "basic")
  )
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  public Response deleteResourcesFile(
      List<StageLibraryExtrasJson> extrasList
  ) throws IOException, RestException {
    String resourcesDir = runtimeInfo.getResourcesDir();
    if (StringUtils.isEmpty(resourcesDir)) {
      throw new RestException(RestErrors.REST_1004);
    }
    for (StageLibraryExtrasJson extrasJson : extrasList) {
      File resourceFile = new File(resourcesDir, extrasJson.getFileName());
      if (resourceFile.exists()) {
        FileUtils.forceDelete(resourceFile);
      }
    }
    return Response.ok().build();
  }

  @GET
  @Path("/stageLibraries/classpathHealth")
  @ApiOperation(
      value = "Validate health of classpath of all loaded stages.",
      response = Object.class,
      authorizations = @Authorization(value = "basic")
  )
  @RolesAllowed({AuthzRole.ADMIN, AuthzRole.ADMIN_REMOTE})
  @Produces(MediaType.APPLICATION_JSON)
  public Response classpathHealth() {
    List<ClasspathValidatorResult> results = stageLibrary.validateStageLibClasspath();
    return Response.ok().entity(results).build();
  }

  @GET
  @Path("/definitions/connections")
  @ApiOperation(value = "Returns connection definitions", response = ConnectionsJson.class,
      authorizations = @Authorization(value = "basic"))
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getConnections() {
    Collection<ConnectionDefinition> connectionDefs = stageLibrary.getConnections();
    List<ConnectionDefinitionJson> definitionsJson =
        connectionDefs.stream().map(connection -> new ConnectionDefinitionJson(connection,
            stageLibrary.getConnectionVerifiers(connection.getType()))).collect(Collectors.toList());
    ConnectionsJson connectionDefinitions = new ConnectionsJson(definitionsJson);
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(connectionDefinitions).build();
  }
}
