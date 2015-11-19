/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.dataflow.admin.controller;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.dataflow.admin.repository.DuplicateStreamException;
import org.springframework.cloud.dataflow.admin.repository.StreamDefinitionRepository;
import org.springframework.cloud.dataflow.artifact.registry.ArtifactRegistration;
import org.springframework.cloud.dataflow.artifact.registry.ArtifactRegistry;
import org.springframework.cloud.dataflow.core.ArtifactCoordinates;
import org.springframework.cloud.dataflow.core.ArtifactType;
import org.springframework.cloud.dataflow.core.BindingProperties;
import org.springframework.cloud.dataflow.core.ModuleDefinition;
import org.springframework.cloud.dataflow.core.ModuleDeploymentId;
import org.springframework.cloud.dataflow.core.ModuleDeploymentRequest;
import org.springframework.cloud.dataflow.core.StreamDefinition;
import org.springframework.cloud.dataflow.module.DeploymentState;
import org.springframework.cloud.dataflow.module.ModuleStatus;
import org.springframework.cloud.dataflow.module.deployer.ModuleDeployer;
import org.springframework.cloud.dataflow.rest.resource.StreamDefinitionResource;
import org.springframework.cloud.dataflow.rest.util.DeploymentPropertiesUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.ExposesResourceFor;
import org.springframework.hateoas.PagedResources;
import org.springframework.hateoas.mvc.ResourceAssemblerSupport;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for operations on {@link StreamDefinition}. This
 * includes CRUD and deployment operations.
 * @author Mark Fisher
 * @author Patrick Peralta
 * @author Ilayaperumal Gopinathan
 */
@RestController
@RequestMapping("/streams")
@ExposesResourceFor(StreamDefinitionResource.class)
public class StreamController {

	private static final Logger logger = LoggerFactory.getLogger(StreamController.class);

	/**
	 * The repository this controller will use for stream CRUD operations.
	 */
	private final StreamDefinitionRepository repository;

	/**
	 * The artifact registry this controller will use to look up modules and libraries.
	 */
	private final ArtifactRegistry registry;

	/**
	 * The deployer this controller will use to deploy stream modules.
	 */
	private final ModuleDeployer deployer;

	/**
	 * Assembler for {@link StreamDefinitionResource} objects.
	 */
	private final Assembler streamAssembler = new Assembler();

	private static final String DEFAULT_PARTITION_KEY_EXPRESSION = "payload";

	/**
	 * Create a {@code StreamController} that delegates
	 * <ul>
	 *     <li>CRUD operations to the provided {@link StreamDefinitionRepository}</li>
	 *     <li>module coordinate retrieval to the provided {@link ArtifactRegistry}</li>
	 *     <li>deployment operations to the provided {@link ModuleDeployer}</li>
	 * </ul>
	 *
	 * @param repository  the repository this controller will use for stream CRUD operations
	 * @param registry    artifact registry this controller will use to look up modules
	 * @param deployer    the deployer this controller will use to deploy stream modules
	 */
	@Autowired
	public StreamController(StreamDefinitionRepository repository, ArtifactRegistry registry,
			@Qualifier("processModuleDeployer") ModuleDeployer deployer) {
		Assert.notNull(repository, "repository must not be null");
		Assert.notNull(registry, "registry must not be null");
		Assert.notNull(deployer, "deployer must not be null");
		this.repository = repository;
		this.registry = registry;
		this.deployer = deployer;
	}

	/**
	 * Return a page-able list of {@link StreamDefinitionResource} defined streams.
	 *
	 * @param pageable   page-able collection of {@code StreamDefinitionResource}.
	 * @param assembler  assembler for {@link StreamDefinition}
	 * @return list of stream definitions
	 */
	@RequestMapping(value = "/definitions", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public PagedResources<StreamDefinitionResource> list(Pageable pageable,
			PagedResourcesAssembler<StreamDefinition> assembler) {
		return assembler.toResource(repository.findAll(pageable), streamAssembler);
	}

	/**
	 * Create a new stream.
	 *
	 * @param name    stream name
	 * @param dsl     DSL definition for stream
	 * @param deploy  if {@code true}, the stream is deployed upon creation
	 */
	@RequestMapping(value = "/definitions", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public void save(@RequestParam("name") String name,
			@RequestParam("definition") String dsl,
			@RequestParam(value = "deploy", defaultValue = "true")
			boolean deploy) {
		if (this.repository.exists(name)) {
			throw new DuplicateStreamException(
					String.format("Cannot create stream %s because another one has already " +
							"been created with the same name", name));
		}

		StreamDefinition stream = new StreamDefinition(name, dsl);
		stream = this.repository.save(stream);
		if (deploy) {
			deployStream(stream, null);
		}
	}

	/**
	 * Request removal of an existing stream definition.
	 *
	 * @param name the name of an existing stream definition (required)
	 */
	@RequestMapping(value = "/definitions/{name}", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.OK)
	public void delete(@PathVariable("name") String name) throws Exception {
		undeploy(name);
		this.repository.delete(name);
	}

	/**
	 * Request removal of all stream definitions.
	 */
	@RequestMapping(value = "/definitions", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.OK)
	public void deleteAll() throws Exception {
		undeployAll();
		this.repository.deleteAll();
	}

	/**
	 * Request un-deployment of an existing stream.
	 *
	 * @param name the name of an existing stream (required)
	 */
	@RequestMapping(value = "/deployments/{name}", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.OK)
	public void undeploy(@PathVariable("name") String name) throws Exception {
		StreamDefinition stream = this.repository.findOne(name);
		Assert.notNull(stream, String.format("no stream defined: %s", name));
		undeployStream(stream);
	}

	/**
	 * Request un-deployment of all streams.
	 */
	@RequestMapping(value = "/deployments", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.OK)
	public void undeployAll() throws Exception {
		for (StreamDefinition stream : this.repository.findAll()) {
			this.undeployStream(stream);
		}
	}

	/**
	 * Request deployment of an existing stream definition. The name must be included in the path.
	 *
	 * @param name the name of an existing stream definition (required)
	 * @param properties the deployment properties for the stream as a comma-delimited list of key=value pairs
	 */
	@RequestMapping(value = "/deployments/{name}", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public void deploy(@PathVariable("name") String name,
			@RequestParam(required = false) String properties) throws Exception {
		StreamDefinition stream = this.repository.findOne(name);
		Assert.notNull(stream, String.format("no stream defined: %s", name));
		deployStream(stream, DeploymentPropertiesUtils.parse(properties));
	}

	/**
	 * Deploy a stream as defined by its {@link StreamDefinition} and optional deployment properties.
	 *
	 * @param stream the stream to deploy
	 * @param streamDeploymentProperties the deployment properties for the stream
	 */
	private void deployStream(StreamDefinition stream, Map<String, String> streamDeploymentProperties) {
		if (streamDeploymentProperties == null) {
			streamDeploymentProperties = Collections.emptyMap();
		}
		Iterator<ModuleDefinition> iterator = stream.getDeploymentOrderIterator();
		int nextModuleCount = 0;
		boolean isDownStreamModulePartitioned = false;
		while (iterator.hasNext()) {
			ModuleDefinition currentModule = iterator.next();
			ArtifactType type = determineModuleType(currentModule);
			ArtifactRegistration registration = this.registry.find(currentModule.getName(), type);
			if (registration == null) {
				throw new IllegalArgumentException(String.format(
						"Module %s of type %s not found in registry", currentModule.getName(), type));
			}
			ArtifactCoordinates coordinates = registration.getCoordinates();
			Map<String, String> moduleDeploymentProperties = extractModuleDeploymentProperties(currentModule, streamDeploymentProperties);
			boolean upstreamModuleSupportsPartition = upstreamModuleHasPartitionInfo(stream, currentModule, streamDeploymentProperties);
			// consumer module partition properties
			if (isPartitionedConsumer(currentModule, moduleDeploymentProperties, upstreamModuleSupportsPartition)) {
				updateConsumerPartitionProperties(moduleDeploymentProperties);
			}
			// producer module partition properties
			if (isDownStreamModulePartitioned) {
				updateProducerPartitionProperties(moduleDeploymentProperties, nextModuleCount);
			}
			nextModuleCount = getModuleCount(moduleDeploymentProperties);
			isDownStreamModulePartitioned = isPartitionedConsumer(currentModule, moduleDeploymentProperties,
					upstreamModuleSupportsPartition);

			currentModule = postProcessLibraryProperties(currentModule);

			this.deployer.deploy(new ModuleDeploymentRequest(currentModule, coordinates, moduleDeploymentProperties));
		}
	}

	/**
	 * Looks at parameters of a module that represent maven coordinates and, if a simple name has been used,
	 * resolve it from the {@link ArtifactRegistry}.
	 */
	private ModuleDefinition postProcessLibraryProperties(ModuleDefinition module) {
		String includes = module.getParameters().get("includes");
		if (includes == null) {
			return module;
		}
		String[] libs = StringUtils.delimitedListToStringArray(includes, ",", " \t");
		for (int i = 0; i < libs.length; i++) {
			ArtifactCoordinates coordinates;
			try {
				coordinates = ArtifactCoordinates.parse(libs[i]);
			}
			catch (IllegalArgumentException e) {
				ArtifactRegistration registration = registry.find(libs[i], ArtifactType.library);
				if (registration == null) {
					throw new IllegalArgumentException("'" + libs[i] + "' could not be parsed as maven coordinates and is not a registered library");
				}
				coordinates = registration.getCoordinates();
			}
			libs[i] = coordinates.toString();
		}
		return ModuleDefinition.Builder.from(module)
				.setParameter("includes", StringUtils.arrayToCommaDelimitedString(libs))
				.build();
	}

	/**
	 * Return the {@link ArtifactType} for a {@link ModuleDefinition} in the context
	 * of a defined stream.
	 *
	 * @param moduleDefinition the module for which to determine the type
	 * @return {@link ArtifactType} for the given module
	 */
	private ArtifactType determineModuleType(ModuleDefinition moduleDefinition) {
		// Parser has already taken care of source/sink named channels, etc
		boolean hasOutput = moduleDefinition.getParameters().containsKey(BindingProperties.OUTPUT_BINDING_KEY);
		boolean hasInput = moduleDefinition.getParameters().containsKey(BindingProperties.INPUT_BINDING_KEY);
		if (hasInput && hasOutput) {
			return ArtifactType.processor;
		}
		else if (hasInput) {
			return ArtifactType.sink;
		}
		else if (hasOutput) {
			return ArtifactType.source;
		}
		else {
			throw new IllegalStateException(moduleDefinition + " had neither input nor output set");
		}
	}

	/**
	 * Extract and return a map of properties for a specific module within the
	 * deployment properties of a stream.
	 *
	 * @param module module for which to return a map of properties
	 * @param streamDeploymentProperties deployment properties for the stream that the module is defined in
	 * @return map of properties for a module
	 */
	private Map<String, String> extractModuleDeploymentProperties(ModuleDefinition module,
			Map<String, String> streamDeploymentProperties) {
		Map<String, String> moduleDeploymentProperties = new HashMap<>();
		String wildCardPrefix = "module.*.";
		// first check for wild card prefix
		for (Map.Entry<String, String> entry : streamDeploymentProperties.entrySet()) {
			if (entry.getKey().startsWith(wildCardPrefix)) {
				moduleDeploymentProperties.put(entry.getKey().substring(wildCardPrefix.length()), entry.getValue());
			}
		}
		String modulePrefix = String.format("module.%s.", module.getLabel());
		for (Map.Entry<String, String> entry : streamDeploymentProperties.entrySet()) {
			if (entry.getKey().startsWith(modulePrefix)) {
				moduleDeploymentProperties.put(entry.getKey().substring(modulePrefix.length()), entry.getValue());
			}
		}
		return moduleDeploymentProperties;
	}

	/**
	 * Return {@code true} if the upstream module (the module that appears before
	 * the provided module) contains partition related properties.
	 *
	 * @param stream        stream for the module
	 * @param currentModule module for which to determine if the upstream module
	 *                      has partition properties
	 * @param streamDeploymentProperties deployment properties for the stream
	 * @return true if the upstream module has partition properties
	 */
	private boolean upstreamModuleHasPartitionInfo(StreamDefinition stream, ModuleDefinition currentModule,
			Map<String, String> streamDeploymentProperties) {
		Iterator<ModuleDefinition> iterator = stream.getDeploymentOrderIterator();
		while (iterator.hasNext()) {
			ModuleDefinition module = iterator.next();
			if (module.equals(currentModule) && iterator.hasNext()) {
				ModuleDefinition prevModule = iterator.next();
				Map<String, String> moduleDeploymentProperties = extractModuleDeploymentProperties(prevModule, streamDeploymentProperties);
				return moduleDeploymentProperties.containsKey(BindingProperties.PARTITION_KEY_EXPRESSION) ||
						moduleDeploymentProperties.containsKey(BindingProperties.PARTITION_KEY_EXTRACTOR_CLASS);
			}
		}
		return false;
	}

	/**
	 * Return {@code true} if the provided module is a consumer of partitioned data.
	 * This is determined either by the deployment properties for the module
	 * or whether the previous (upstream) module is publishing partitioned data.
	 *
	 * @param module module for which to determine if it is consuming partitioned data
	 * @param moduleDeploymentProperties deployment properties for the module
	 * @param upstreamModuleSupportsPartition if true, previous (upstream) module
	 * in the stream publishes partitioned data
	 * @return true if this module consumes partitioned data
	 */
	private boolean isPartitionedConsumer(ModuleDefinition module,
			Map<String, String> moduleDeploymentProperties,
			boolean upstreamModuleSupportsPartition) {
		return upstreamModuleSupportsPartition ||
				(module.getParameters().containsKey(BindingProperties.INPUT_BINDING_KEY) &&
						moduleDeploymentProperties.containsKey(BindingProperties.PARTITIONED_PROPERTY) &&
						moduleDeploymentProperties.get(BindingProperties.PARTITIONED_PROPERTY).equalsIgnoreCase("true"));
	}

	/**
	 * Add module properties for consuming partitioned data to the provided properties.
	 *
	 * @param properties properties to update
	 */
	private void updateConsumerPartitionProperties(Map<String, String> properties) {
		properties.put(BindingProperties.INPUT_PARTITIONED, "true");
		if (properties.containsKey(BindingProperties.COUNT_PROPERTY)) {
			properties.put(BindingProperties.INSTANCE_COUNT, properties.get(BindingProperties.COUNT_PROPERTY));
		}
	}

	/**
	 * Add module properties for producing partitioned data to the provided properties.
	 *
	 * @param properties properties to update
	 * @param nextModuleCount the number of module instances for the next (downstream) module in the stream
	 */
	private void updateProducerPartitionProperties(Map<String, String> properties, int nextModuleCount) {
		properties.put(BindingProperties.OUTPUT_PARTITION_COUNT, String.valueOf(nextModuleCount));
		if (properties.containsKey(BindingProperties.PARTITION_KEY_EXPRESSION)) {
			properties.put(BindingProperties.OUTPUT_PARTITION_KEY_EXPRESSION,
					properties.get(BindingProperties.PARTITION_KEY_EXPRESSION));
		}
		else {
			properties.put(BindingProperties.OUTPUT_PARTITION_KEY_EXPRESSION, DEFAULT_PARTITION_KEY_EXPRESSION);
		}
		if (properties.containsKey(BindingProperties.PARTITION_KEY_EXTRACTOR_CLASS)) {
			properties.put(BindingProperties.OUTPUT_PARTITION_KEY_EXTRACTOR_CLASS,
					properties.get(BindingProperties.PARTITION_KEY_EXTRACTOR_CLASS));
		}
		if (properties.containsKey(BindingProperties.PARTITION_SELECTOR_CLASS)) {
			properties.put(BindingProperties.OUTPUT_PARTITION_SELECTOR_CLASS,
					properties.get(BindingProperties.PARTITION_SELECTOR_CLASS));
		}
		if (properties.containsKey(BindingProperties.PARTITION_SELECTOR_EXPRESSION)) {
			properties.put(BindingProperties.OUTPUT_PARTITION_SELECTOR_EXPRESSION,
					properties.get(BindingProperties.PARTITION_SELECTOR_EXPRESSION));
		}
	}

	/**
	 * Return the module count indicated in the provided properties.
	 *
	 * @param properties properties for the module for which to determine the count
	 * @return module count indicated in the provided properties;
	 * if the properties do not contain a count a value of {@code 1} is returned
	 */
	private int getModuleCount(Map<String, String> properties) {
		return (properties.containsKey(BindingProperties.COUNT_PROPERTY)) ?
				Integer.valueOf(properties.get(BindingProperties.COUNT_PROPERTY)) : 1;
	}

	/**
	 * Undeploy the given stream.
	 *
	 * @param stream stream to undeploy
	 */
	private void undeployStream(StreamDefinition stream) {
		for (ModuleDefinition module : stream.getModuleDefinitions()) {
			ModuleDeploymentId id = ModuleDeploymentId.fromModuleDefinition(module);
			ModuleStatus status = this.deployer.status(id);
			if (!EnumSet.of(DeploymentState.unknown, DeploymentState.undeployed)
					.contains(status.getState())) {
				this.deployer.undeploy(id);
			}
		}
	}

	/**
	 * Return a string that describes the state of the given stream.
	 *
	 * @param name name of stream to determine state for
	 * @return stream state
	 * @see DeploymentState
	 */
	private String calculateStreamState(String name) {
		Set<DeploymentState> moduleStates = EnumSet.noneOf(DeploymentState.class);
		StreamDefinition stream = repository.findOne(name);
		for (ModuleDefinition module : stream.getModuleDefinitions()) {
			ModuleStatus status = deployer.status(ModuleDeploymentId.fromModuleDefinition(module));
			moduleStates.add(status.getState());
		}

		logger.debug("Module states for stream {}: {}", name, moduleStates);
		return aggregateState(moduleStates).toString();
	}

	/**
	 * Aggregate the set of module states into a single state for a stream.
	 *
	 * @param states set of states for modules of a stream
	 * @return stream state based on module states
	 */
	static DeploymentState aggregateState(Set<DeploymentState> states) {
		if (states.size() == 1) {
			DeploymentState state = states.iterator().next();

			// a stream which is known to the stream repository
			// but unknown to deployers is undeployed
			return state == DeploymentState.unknown ? DeploymentState.undeployed : state;
		}
		if (states.isEmpty() || states.contains(DeploymentState.error)) {
			return DeploymentState.error;
		}
		if (states.contains(DeploymentState.failed)) {
			return DeploymentState.failed;
		}
		if (states.contains(DeploymentState.deploying)) {
			return DeploymentState.deploying;
		}

		return DeploymentState.partial;
	}

	/**
	 * {@link org.springframework.hateoas.ResourceAssembler} implementation
	 * that converts {@link StreamDefinition}s to {@link StreamDefinitionResource}s.
	 */
	class Assembler extends ResourceAssemblerSupport<StreamDefinition, StreamDefinitionResource> {

		public Assembler() {
			super(StreamController.class, StreamDefinitionResource.class);
		}

		@Override
		public StreamDefinitionResource toResource(StreamDefinition stream) {
			return createResourceWithId(stream.getName(), stream);
		}

		@Override
		public StreamDefinitionResource instantiateResource(StreamDefinition stream) {
			StreamDefinitionResource resource = new StreamDefinitionResource(stream.getName(), stream.getDslText());
			resource.setStatus(calculateStreamState(stream.getName()));
			return resource;
		}
	}

}