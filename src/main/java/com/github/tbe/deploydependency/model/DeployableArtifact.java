package com.github.tbe.deploydependency.model;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.eclipse.aether.artifact.Artifact;

public class DeployableArtifact {

	private Artifact artifact;
	private Artifact pomArtifact;	

	public DeployableArtifact(Artifact artifact, Artifact pomArtifact) {
		this.artifact = artifact;
		this.pomArtifact = pomArtifact;
	}

	public Artifact getArtifact() {
		return artifact;
	}

	public Artifact getPomArtifact() {
		return pomArtifact;
	}

	@Override
	public int hashCode() {
		HashCodeBuilder builder = new HashCodeBuilder();
		builder.append(this.artifact);
		builder.append(this.pomArtifact);

		return builder.hashCode();
	}

	@Override
	public boolean equals(Object object) {
		boolean equal = false;

		if (object != null && object.getClass().equals(getClass())) {
			DeployableArtifact that = DeployableArtifact.class.cast(object);

			EqualsBuilder builder = new EqualsBuilder();
			builder.append(this.artifact, that.artifact);
			builder.append(this.pomArtifact, that.pomArtifact);

			equal = builder.isEquals();
		}

		return equal;
	}
}
