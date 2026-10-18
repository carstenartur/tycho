/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.test.target;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.tycho.test.AbstractTychoIntegrationTest;
import org.eclipse.tycho.test.util.HttpServer;
import org.eclipse.tycho.test.util.TargetDefinitionUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class P2MavenMetadataHandlingTest extends AbstractTychoIntegrationTest {

	private static final String MAVEN_GROUP_ID = "tycho-its-project.target.p2-maven-metadata";
	private static final String AVAILABLE_JAR = "/tycho-its-project/target/p2-maven-metadata/available/1.0.0/available-1.0.0.jar";
	private static final String MISSING_JAR = "/tycho-its-project/target/p2-maven-metadata/missing/1.0.0/missing-1.0.0.jar";

	private HttpServer server;
	private Verifier verifier;

	@Before
	public void prepareRepositories() throws Exception {
		verifier = getVerifier("target.p2-maven-metadata", false);
		// A previous test run must not hide validation requests behind Maven's cache.
		verifier.deleteArtifacts(MAVEN_GROUP_ID, "available", "1.0.0");
		verifier.deleteArtifacts(MAVEN_GROUP_ID, "missing", "1.0.0");
		Path mavenRepository = Path.of(verifier.getBasedir(), "maven-repository");
		Path jar = mavenRepository.resolve(AVAILABLE_JAR.substring(1));
		Files.createDirectories(jar.getParent());
		Manifest manifest = new Manifest();
		Attributes attributes = manifest.getMainAttributes();
		attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		attributes.putValue("Bundle-ManifestVersion", "2");
		attributes.putValue("Bundle-SymbolicName", "tpmm.available");
		attributes.putValue("Bundle-Version", "1.0.0");
		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			// The artifact only needs a manifest; no application classes are required.
		}
		String checksum = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar)));
		Files.writeString(jar.resolveSibling("available-1.0.0.jar.sha1"), checksum, StandardCharsets.US_ASCII);
		server = HttpServer.startServer();
		String repositoryUrl = server.addServer("maven", mavenRepository.toFile());
		configurePom(model -> model.getRepositories().get(0).setUrl(repositoryUrl));
		verifier.addCliOption("-Dtycho.p2.dump.model=true");
		TargetDefinitionUtil.setRepositoryURLs(new File(verifier.getBasedir(), "platform.target"),
				new File(verifier.getBasedir(), "p2").toURI().toString());
	}

	@After
	public void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	@Test
	public void validatesMavenCoordinatesByDefault() throws Exception {
		assertValidationBehavior();
	}

	@Test
	public void validatesMavenCoordinatesWhenConfigured() throws Exception {
		configureHandling("validate");
		assertValidationBehavior();
	}

	@Test
	public void injectsMavenCoordinatesWithoutValidationRequests() throws Exception {
		configureHandling("inject");
		List<Dependency> dependencies = buildAndReadDependencies();
		assertDependency(dependencies, "available", MAVEN_GROUP_ID, "jar", "compile");
		assertDependency(dependencies, "missing", MAVEN_GROUP_ID, "jar", "compile");
		assertTrue("Injection must not validate Maven artifacts: " + server.getAccessedUrls("maven"),
				server.getAccessedUrls("maven").isEmpty());
	}

	@Test
	public void ignoresMavenCoordinatesWithoutValidationRequests() throws Exception {
		configureHandling("ignore");
		List<Dependency> dependencies = buildAndReadDependencies();
		assertDependency(dependencies, "tpmm.available", "p2.eclipse.plugin", "eclipse-plugin", "system");
		assertDependency(dependencies, "tpmm.missing", "p2.eclipse.plugin", "eclipse-plugin", "system");
		assertTrue("Ignoring Maven metadata must not validate Maven artifacts: " + server.getAccessedUrls("maven"),
				server.getAccessedUrls("maven").isEmpty());
	}

	@Test
	public void rejectsInvalidHandlingBeforeContactingMavenRepository() throws Exception {
		configureHandling("invalid-mode");
		assertThrows(VerificationException.class, () -> verifier.executeGoal("validate"));
		verifier.verifyTextInLog("Illegal value of <p2MavenMetadataHandling>");
		verifier.verifyTextInLog("invalid-mode");
		assertTrue(server.getAccessedUrls("maven").toString(), server.getAccessedUrls("maven").isEmpty());
	}

	private void assertValidationBehavior() throws Exception {
		List<Dependency> dependencies = buildAndReadDependencies();
		assertDependency(dependencies, "available", MAVEN_GROUP_ID, "jar", "compile");
		assertDependency(dependencies, "tpmm.missing", "p2.eclipse.plugin", "eclipse-plugin", "system");
		List<String> requests = server.getAccessedUrls("maven");
		assertTrue("The available Maven artifact must be validated: " + requests, requests.contains(AVAILABLE_JAR));
		assertTrue("Unavailable Maven coordinates must be validated before falling back to p2: " + requests,
				requests.contains(MISSING_JAR));
	}

	private List<Dependency> buildAndReadDependencies() throws Exception {
		// Eager resolution exposes model injection without a Mojo resolving Maven dependencies afterwards.
		verifier.executeGoal("validate");
		verifier.verifyErrorFreeLog();
		try (Reader reader = Files.newBufferedReader(Path.of(verifier.getBasedir(), "pom-model-classic.xml"))) {
			List<Dependency> dependencies = new MavenXpp3Reader().read(reader).getDependencies();
			assertEquals("Both target bundles must remain in the Maven model", 2, dependencies.size());
			return dependencies;
		}
	}

	private void configureHandling(String mode) throws Exception {
		configurePom(model -> {
			Plugin configurationPlugin = model.getBuild().getPlugins().stream()
					.filter(plugin -> "target-platform-configuration".equals(plugin.getArtifactId())).findFirst()
					.orElseThrow();
			Xpp3Dom configuration = (Xpp3Dom) configurationPlugin.getConfiguration();
			Xpp3Dom handling = new Xpp3Dom("p2MavenMetadataHandling");
			handling.setValue(mode);
			configuration.addChild(handling);
		});
	}

	private void configurePom(Consumer<Model> configure) throws Exception {
		Path pom = Path.of(verifier.getBasedir(), "pom.xml");
		Model model;
		try (Reader reader = Files.newBufferedReader(pom)) {
			model = new MavenXpp3Reader().read(reader);
		}
		configure.accept(model);
		try (Writer writer = Files.newBufferedWriter(pom)) {
			new MavenXpp3Writer().write(writer, model);
		}
	}

	private static void assertDependency(List<Dependency> dependencies, String artifactId, String groupId,
			String type, String scope) {
		Dependency dependency = dependencies.stream().filter(candidate -> artifactId.equals(candidate.getArtifactId()))
				.findFirst().orElseThrow(() -> new AssertionError("Missing dependency " + artifactId + ": " + dependencies));
		assertEquals(groupId, dependency.getGroupId());
		assertEquals("1.0.0", dependency.getVersion());
		assertEquals(type, dependency.getType());
		assertEquals(scope, dependency.getScope());
		if ("system".equals(scope)) {
			assertTrue("p2 dependencies must retain a system path", new File(dependency.getSystemPath()).isAbsolute());
		}
	}
}
