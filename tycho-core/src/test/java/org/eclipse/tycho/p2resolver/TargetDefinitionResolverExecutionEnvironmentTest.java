/*******************************************************************************
 * Copyright (c) 2012, 2020 SAP SE and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP SE - initial API and implementation
 *    Christoph Läubrich - adjust API
 *******************************************************************************/
package org.eclipse.tycho.p2resolver;

import static org.eclipse.tycho.p2resolver.TargetDefinitionResolverTest.defaultEnvironments;
import static org.eclipse.tycho.p2resolver.TargetDefinitionResolverTest.definitionWith;
import static org.eclipse.tycho.test.util.InstallableUnitMatchers.unit;
import static org.eclipse.tycho.test.util.InstallableUnitMatchers.unitWithId;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.VersionedId;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.tycho.core.ee.impl.StandardEEResolutionHints;
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentStub;
import org.eclipse.tycho.core.resolver.shared.IncludeSourceMode;
import org.eclipse.tycho.core.resolver.shared.ReferencedRepositoryMode;
import org.eclipse.tycho.core.shared.MavenContext;
import org.eclipse.tycho.p2resolver.TargetDefinitionResolverIncludeModeTest.PlannerLocationStub;
import org.eclipse.tycho.p2resolver.TargetDefinitionResolverTest.RepositoryStub;
import org.eclipse.tycho.targetplatform.TargetDefinition;
import org.eclipse.tycho.targetplatform.TargetDefinition.Repository;
import org.eclipse.tycho.test.util.LogVerifier;
import org.eclipse.tycho.test.util.MockMavenContext;
import org.eclipse.tycho.testing.TychoPlexusTestCase;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TargetDefinitionResolverExecutionEnvironmentTest extends TychoPlexusTestCase {

    @Rule
    public final LogVerifier logVerifier = new LogVerifier();

    private TargetDefinitionResolver subject;
    @Rule
    public final TemporaryFolder tempManager = new TemporaryFolder();

    private TargetDefinitionResolver targetResolverForEE(String executionEnvironmentName, String... systemPackages)
            throws ProvisionException, IOException {
        MavenContext mavenCtx = new MockMavenContext(tempManager.newFolder("localRepo"), logVerifier.getLogger());
        return new TargetDefinitionResolver(defaultEnvironments(),
                new StandardEEResolutionHints(new ExecutionEnvironmentStub(executionEnvironmentName, systemPackages)),
                IncludeSourceMode.honor, ReferencedRepositoryMode.ignore, mavenCtx, null,
                new DefaultTargetDefinitionVariableResolver(mavenCtx, logVerifier.getLogger()));
    }

    @Test
    public void testRestrictedExecutionEnvironment() throws Exception {
        subject = targetResolverForEE("CDC-1.0/Foundation-1.0");

        TargetDefinition definition = definitionWith(new AlternatePackageProviderLocationStub());
        Collection<IInstallableUnit> units = subject.resolveContent(definition, lookup(IProvisioningAgent.class))
                .query(QueryUtil.ALL_UNITS, null).toUnmodifiableSet();

        // expect that resolver included a bundle providing org.w3c.dom (here javax.xml)...
        assertThat(units, hasItem(unit("javax.xml", "0.0.1.SNAPSHOT")));
        // ... and did not match the import against the "a.jre" IU also in the repository
        assertThat(units, not(hasItem(unitWithId("a.jre"))));
    }

    @Test
    public void testAutoGeneratedExecutionEnvironment() throws Exception {
        subject = targetResolverForEE("JavaSE-1.7", "org.w3c.dom");

        TargetDefinition definition = definitionWith(new AlternatePackageProviderLocationStub());
        Collection<IInstallableUnit> units = subject.resolveContent(definition, lookup(IProvisioningAgent.class))
                .query(QueryUtil.ALL_UNITS, null).toUnmodifiableSet();

        // expect that resolver did not included a bundle providing org.w3c.dom...
        assertThat(units, not(hasItem(unit("javax.xml", "0.0.1.SNAPSHOT"))));
        // ... but instead included the configured 'a.jre' IU (which is not contained in the repository)
        assertThat(units, hasItem(unit("a.jre.javase", "1.7.0")));

        // other "a.jre" IUs from the repository shall be filtered out
        assertThat(units, not(hasItem(unitWithId("a.jre"))));
    }

    /**
     * Location with a seed that requires the package org.w3c.dom. In the repository, there is both
     * a bundle and a fake 'a.jre' IU that could match that import.
     */
    static class AlternatePackageProviderLocationStub extends PlannerLocationStub {

        public AlternatePackageProviderLocationStub() {
            super(null, new VersionedId("dom-client", "0.0.1.SNAPSHOT"));
        }

        @Override
        public List<? extends Repository> getRepositories() {
            return Collections.singletonList(new RepositoryStub("repositories/", "javax.xml"));
        }

    }

}
