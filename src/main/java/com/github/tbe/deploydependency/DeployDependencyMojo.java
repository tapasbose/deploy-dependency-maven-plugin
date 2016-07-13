package com.github.tbe.deploydependency;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelCache;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.ArtifactDescriptorUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.impl.VersionRangeResolver;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.resolution.VersionResult;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;

import com.github.tbe.deploydependency.model.DeployableArtifact;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@Mojo(name = "deploy-dependency", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.DEPLOY, requiresOnline = true, threadSafe = true)
public class DeployDependencyMojo extends AbstractMojo {

	private static final String POM_EXTENSION = "pom";

	@Component
	private ModelBuilder modelBuilder;
	
	@Component
	private RepositorySystem repositorySystem;
	
	@Component
	private RemoteRepositoryManager remoteRepositoryManager;

    @Component
    private VersionRangeResolver versionRangeResolver;

    @Component
    private ArtifactResolver artifactResolver;

	@Component
	private RepositoryLayoutProvider repositoryLayoutProvider;

	@Component
	private TransporterProvider transporterProvider;

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
	private RepositorySystemSession repositorySystemSession;

	@Parameter(property = "repositoryUniqueVersion", defaultValue = "true")
	private boolean repositoryUniqueVersion;

	@Parameter(property = "snapshotRepositoryUniqueVersion", defaultValue = "true")
	private boolean snapshotRepositoryUniqueVersion;

	@Parameter(property = "skipExisting", defaultValue = "false")
	private boolean skipExisting;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {				
		try {
			RemoteRepository releaseRepository = getReleaseRepository();
			RemoteRepository snapshotRepository = getSnapshotRepository();

			if (releaseRepository != null || snapshotRepository != null) {
				RepositoryLayout releaseRepositoryLayout = getRepositoryLayout(releaseRepository);
				RepositoryLayout snapshotRepositoryLayout = getRepositoryLayout(snapshotRepository);

				Transporter releaseRepositoryTransporter = getTransporter(releaseRepository);
				Transporter snapshotRepositoryTransporter = getTransporter(snapshotRepository);

				Artifact projectArtifact = new DefaultArtifact(project.getGroupId(), project.getArtifactId(), project.getPackaging(), project.getVersion());

				Set<DeployableArtifact> artifacts = Sets.<DeployableArtifact> newConcurrentHashSet();
				listArtifacts(Sets.<RemoteRepository> newHashSet(toRemoteRepository(repositorySystemSession.getLocalRepository())), Collections.<RemoteRepository> emptySet(), projectArtifact, artifacts, true);

				for (DeployableArtifact deployableArtifact : artifacts) {
					Artifact artifact = deployableArtifact.getArtifact();
					Artifact pomArtifact = deployableArtifact.getPomArtifact();
					
					try {
						boolean snapshot = artifact.isSnapshot();
						RemoteRepository deploymentRepository = not(snapshot) ? releaseRepository : snapshotRepository;

						if (deploymentRepository != null) {
							boolean artifatExistsInRepository = false;

							if (skipExisting) {
								RepositoryLayout repositoryLayout = not(snapshot) ? releaseRepositoryLayout : snapshotRepositoryLayout;
								Transporter transporter = not(snapshot) ? releaseRepositoryTransporter : snapshotRepositoryTransporter;

								if (repositoryLayout != null && transporter != null) {
									try {
										URI location = repositoryLayout.getLocation(artifact, false);
										transporter.peek(new PeekTask(location));

										artifatExistsInRepository = true;
										getLog().info(String.format("Artifact %1$s exists in repository: %2$s.", artifact, deploymentRepository.getId()));
									} catch (Exception cause) {
										getLog().info(String.format("Artifact %1$s doesn't exist in repository: %2$s.", artifact, deploymentRepository.getId()));
									}
								}
							}

							if (not(artifatExistsInRepository) && artifact.getFile() != null && artifact.getFile().exists()) {
								getLog().info(String.format("Uploading Artifact %1$s to repository: %2$s.", artifact, deploymentRepository.getId()));

								DeployRequest deployRequest = new DeployRequest();
								deployRequest.addArtifact(artifact);

								if (!POM_EXTENSION.equals(artifact.getExtension())) {
									if (pomArtifact != null && pomArtifact.getFile() != null && pomArtifact.getFile().exists()) {
										deployRequest.addArtifact(pomArtifact);
									}
								}

								deployRequest.setRepository(deploymentRepository);
								repositorySystem.deploy(repositorySystemSession, deployRequest);

								getLog().info(String.format("Artifact %1$s uploaded to repository: %2$s.", artifact, deploymentRepository.getId()));
							}
						}
					} catch (Exception cause) {
						if (getLog().isDebugEnabled()) {
							getLog().error(cause.getMessage(), cause);
						} else {
							getLog().warn(cause.getMessage());
						}
					}
				}
			}
		} catch (Exception cause) {
			getLog().error(cause.getMessage(), cause);
			throw new MojoExecutionException(cause.getMessage(), cause);
		}
	}

	public boolean isRepositoryUniqueVersion() {
		return repositoryUniqueVersion;
	}

	public boolean isSnapshotRepositoryUniqueVersion() {
		return snapshotRepositoryUniqueVersion;
	}

	public boolean isSkipExisting() {
		return skipExisting;
	}

	private ArtifactDescriptorResult getArtifactDescriptorResult(Artifact artifact, Set<RemoteRepository> repositories) {
		ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
		descriptorRequest.setArtifact(artifact);
		descriptorRequest.setRepositories(Lists.<RemoteRepository> newArrayList(repositories));

		ArtifactDescriptorResult descriptorResult = null;

		try {
			descriptorResult = repositorySystem.readArtifactDescriptor(repositorySystemSession, descriptorRequest);
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}

		return descriptorResult;
	}

	private void listArtifacts(Set<RemoteRepository> initialRepositories, Set<RemoteRepository> artifactRepositories, Artifact parentArtifact, Set<DeployableArtifact> artifacts, boolean isPojectArtifact) {
		try {
			Set<RemoteRepository> repositories = Sets.<RemoteRepository> newHashSet(initialRepositories);
			ArtifactDescriptorResult descriptorResult = null;

			if (isPojectArtifact) {
				descriptorResult = getArtifactDescriptorResult(parentArtifact, repositories);
				initialRepositories.addAll(safe(descriptorResult.getRepositories()));
			} else {
				repositories.addAll(safe(artifactRepositories));
				descriptorResult = getArtifactDescriptorResult(parentArtifact, repositories);

				addArtifact(repositories, parentArtifact, artifacts);
			}
			
			if (descriptorResult != null) {
				repositories.addAll(safe(descriptorResult.getRepositories()));

				if (!safe(descriptorResult.getRelocations()).isEmpty()) {
					Artifact artifact = descriptorResult.getArtifact();
					
					if(addArtifact(repositories, artifact, artifacts)) {
						listArtifacts(initialRepositories, repositories, artifact, artifacts, false);
					}				
				}

				List<Dependency> dependencies = safe(descriptorResult.getDependencies());

				if (!dependencies.isEmpty()) {
					for (Dependency dependency : dependencies) {
						Artifact artifact = dependency.getArtifact();

						if (addArtifact(repositories, artifact, artifacts)) {
							listArtifacts(initialRepositories, repositories, artifact, artifacts, false);
						}
					}
				}
			}
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}
	}
	
	private void listArtifacts(Set<RemoteRepository> initialRepositories, Set<RemoteRepository> artifactRepositories, Artifact parentArtifact, Set<DeployableArtifact> artifacts) {
		try {
			Set<RemoteRepository> repositories = Sets.<RemoteRepository> newHashSet(initialRepositories);
			ArtifactDescriptorResult descriptorResult = getArtifactDescriptorResult(parentArtifact, repositories);
			addArtifact(repositories, parentArtifact, artifacts);
			
			if (descriptorResult != null) {
				repositories.addAll(safe(descriptorResult.getRepositories()));

				if (!safe(descriptorResult.getRelocations()).isEmpty()) {
					Artifact artifact = descriptorResult.getArtifact();
					
					if(addArtifact(repositories, artifact, artifacts)) {
						listArtifacts(initialRepositories, repositories, artifact, artifacts);
					}				
				}

				List<Dependency> dependencies = safe(descriptorResult.getDependencies());

				if (!dependencies.isEmpty()) {
					for (Dependency dependency : dependencies) {
						Artifact artifact = dependency.getArtifact();

						if (addArtifact(repositories, artifact, artifacts)) {
							listArtifacts(initialRepositories, repositories, artifact, artifacts);
						}
					}
				}
			}
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}		
	}

	private boolean addArtifact(Set<RemoteRepository> repositories, Artifact artifact, Set<DeployableArtifact> artifacts) {
		boolean added = false;

		try {
			artifact = resolveArtifact(artifact, repositories);
			Artifact pomArtifact = getPomArtifact(repositories, artifact);
			
			DeployableArtifact deployableArtifact = new DeployableArtifact(artifact, pomArtifact);
			
			added = artifacts.add(deployableArtifact);
			
			if(added) {
				if(pomArtifact != null && pomArtifact.getFile() != null) {
					ModelBuildingResult modelBuildingResult = getModelBuildingResult(repositories, pomArtifact.getFile());
					
					if(modelBuildingResult != null) {
						List<String> modelIdentifiers = modelBuildingResult.getModelIds();
						
						for(int i = 1; i < safe(modelIdentifiers).size(); i++) {
							String modelIdentifier = modelIdentifiers.get(i);
							
							if(StringUtils.isNotBlank(modelIdentifier)) {
								Model model = modelBuildingResult.getRawModel(modelIdentifier);
								
								if(model != null) {
									String groupId = model.getGroupId();
									
									if(StringUtils.isBlank(groupId)) {
										groupId = artifact.getGroupId();
									}
									
									String version = model.getVersion();
									
									if(StringUtils.isBlank(version)) {
										version = artifact.getVersion();
									}
									
									if(StringUtils.isNotBlank(groupId) && StringUtils.isNotBlank(version)) {
										Artifact parentArtifact = new DefaultArtifact(groupId, model.getArtifactId(), model.getPackaging(), version);									
										listArtifacts(repositories, Collections.<RemoteRepository>emptySet(), parentArtifact, artifacts);
									}																		
								}
							}
						}						
					}
				}
			}			
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}

		return added;
	}

	private Artifact resolveArtifact(Artifact artifact, Set<RemoteRepository> repositories) throws ArtifactResolutionException {
		ArtifactRequest artifactRequest = new ArtifactRequest(artifact, Lists.<RemoteRepository> newArrayList(repositories), null);
		ArtifactResult artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, artifactRequest);
		return artifactResult.getArtifact();
	}

	private Artifact getPomArtifact(Set<RemoteRepository> repositories, Artifact artifact) {
		Artifact pomArtifact = ArtifactDescriptorUtils.toPomArtifact(artifact);

		String version = null;
		
		try {
			version = resolveVersion(artifact, repositories);
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}
		
		if(StringUtils.isNotBlank(version)) {
			pomArtifact = pomArtifact.setVersion(version);
		}
		
		try {			
			pomArtifact = resolveArtifact(pomArtifact, repositories);
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}

		return pomArtifact;
	}
	
	private String resolveVersion(Artifact artifact, Set<RemoteRepository> repositories) throws VersionResolutionException {
		VersionRequest versionRequest = new VersionRequest(artifact, Lists.<RemoteRepository>newArrayList(repositories), null);
        VersionResult versionResult = repositorySystem.resolveVersion(repositorySystemSession, versionRequest);
        return versionResult.getVersion();
	}
	
	private ModelBuildingResult getModelBuildingResult(Set<RemoteRepository> repositories, File pomFile) {
		ModelBuildingResult modelBuildingResult = null;
		
		try {
			Properties properties = new Properties();
			properties.putAll(repositorySystemSession.getUserProperties());
			properties.putAll(repositorySystemSession.getSystemProperties());
			
			 ModelBuildingRequest modelBuildingRequest = new DefaultModelBuildingRequest();
             modelBuildingRequest.setValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL );
             modelBuildingRequest.setProcessPlugins( false );
             modelBuildingRequest.setTwoPhaseBuilding( false );
             modelBuildingRequest.setSystemProperties( properties );
             
             ModelCache modelCache = getModelCache();
             
             if(modelCache != null) {
            	 modelBuildingRequest.setModelCache(modelCache);
             }
             
             ModelResolver modelResolver = getModelResolver(Lists.<RemoteRepository>newArrayList(repositories));
             
             if(modelResolver != null) {
            	 modelBuildingRequest.setModelResolver(modelResolver);
             }
             
             modelBuildingRequest.setPomFile(pomFile);
             
             modelBuildingResult = modelBuilder.build(modelBuildingRequest);
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}
		
		return modelBuildingResult;
	}
	
	private ModelCache getModelCache() {
		ModelCache modelCache = null;
		
		try {
			Class<?> clazz = Class.forName("org.apache.maven.repository.internal.DefaultModelCache");
			final Method method = clazz.getMethod("newInstance", RepositorySystemSession.class);
			modelCache = AccessController.doPrivileged(new PrivilegedAction<ModelCache>() {

				@Override
				public ModelCache run() {
					method.setAccessible(true);
					
					try {
						return ModelCache.class.cast(method.invoke(null, repositorySystemSession));
					} catch (Exception cause) {
						if (getLog().isDebugEnabled()) {
							getLog().error(cause.getMessage(), cause);
						} else {
							getLog().warn(cause.getMessage());
						}
					}
					
					return null;
				}
			});
			
			method.setAccessible(false);
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}
		
		return modelCache;
	}
	
	private ModelResolver getModelResolver(final List<RemoteRepository> repositories) {
		ModelResolver modelResolver = null;
		
		try {
			Class<?> clazz = Class.forName("org.apache.maven.repository.internal.DefaultModelResolver");
			final Constructor<?> constructor = clazz.getConstructor(RepositorySystemSession.class, RequestTrace.class, String.class, ArtifactResolver.class, VersionRangeResolver.class, RemoteRepositoryManager.class, List.class);
			modelResolver = AccessController.doPrivileged(new PrivilegedAction<ModelResolver>() {

				@Override
				public ModelResolver run() {
					constructor.setAccessible(true);
					
					try {
						return ModelResolver.class.cast(constructor.newInstance(repositorySystemSession, null, null, artifactResolver, versionRangeResolver, remoteRepositoryManager, repositories));
					} catch (Exception cause) {
						if (getLog().isDebugEnabled()) {
							getLog().error(cause.getMessage(), cause);
						} else {
							getLog().warn(cause.getMessage());
						}
					}
					
					return null;
				}
			});
			
			constructor.setAccessible(false);
		} catch (Exception cause) {
			if (getLog().isDebugEnabled()) {
				getLog().error(cause.getMessage(), cause);
			} else {
				getLog().warn(cause.getMessage());
			}
		}
		
		return modelResolver;
	}

	private RemoteRepository getReleaseRepository() {
		RemoteRepository repository = null;
		DistributionManagement distributionManagement = project.getDistributionManagement();

		if (distributionManagement != null) {
			repository = toRemoteRepository(distributionManagement.getRepository());
		}

		return repository;
	}

	private RemoteRepository getSnapshotRepository() {
		RemoteRepository repository = null;
		DistributionManagement distributionManagement = project.getDistributionManagement();

		if (distributionManagement != null) {
			repository = toRemoteRepository(distributionManagement.getSnapshotRepository());
		}

		return repository;
	}

	private RemoteRepository toRemoteRepository(DeploymentRepository deploymentRepository) {
		RemoteRepository repository = null;

		if (deploymentRepository != null) {
			RemoteRepository.Builder builder = new RemoteRepository.Builder(deploymentRepository.getId(), deploymentRepository.getLayout(), deploymentRepository.getUrl());
			builder.setSnapshotPolicy(getRepositoryPolicy(deploymentRepository.getSnapshots()));
			builder.setReleasePolicy(getRepositoryPolicy(deploymentRepository.getReleases()));
			builder.setRepositoryManager(true);

			repository = builder.build();
			
			AuthenticationSelector authenticationSelector = repositorySystemSession.getAuthenticationSelector();
			Authentication authentication = authenticationSelector.getAuthentication(repository);
			
			builder = new RemoteRepository.Builder(repository);
			builder.setAuthentication(authentication);
			
			repository = builder.build();
		}

		return repository;
	}

	private RemoteRepository toRemoteRepository(LocalRepository localRepository) {
		RemoteRepository repository = new RemoteRepository.Builder(localRepository.getId(), "default", "file://" + localRepository.getBasedir()).build();
		return repository;
	}

	private RepositoryPolicy getRepositoryPolicy(org.apache.maven.model.RepositoryPolicy policy) {
		RepositoryPolicy result = null;

		if (policy != null) {
			result = new RepositoryPolicy(policy.isEnabled(), policy.getUpdatePolicy(), policy.getChecksumPolicy());
		}

		return result;
	}

	private RepositoryLayout getRepositoryLayout(RemoteRepository repository) {
		RepositoryLayout repositoryLayout = null;

		if (repository != null) {
			try {
				repositoryLayout = repositoryLayoutProvider.newRepositoryLayout(repositorySystemSession, repository);
			} catch (Exception cause) {
				if (getLog().isDebugEnabled()) {
					getLog().error(cause.getMessage(), cause);
				} else {
					getLog().warn(cause.getMessage());
				}
			}
		}

		return repositoryLayout;
	}

	private Transporter getTransporter(RemoteRepository repository) {
		Transporter transporter = null;

		if (repository != null) {
			try {
				transporter = transporterProvider.newTransporter(repositorySystemSession, repository);
			} catch (Exception cause) {
				if (getLog().isDebugEnabled()) {
					getLog().error(cause.getMessage(), cause);
				} else {
					getLog().warn(cause.getMessage());
				}
			}
		}

		return transporter;
	}

	private <T> List<T> safe(List<T> collection) {
		return collection != null ? collection : Collections.<T> emptyList();
	}

	private <T> Set<T> safe(Set<T> collection) {
		return collection != null ? collection : Collections.<T> emptySet();
	}
	
	private boolean not(boolean value) {
		return !value;
	}
}