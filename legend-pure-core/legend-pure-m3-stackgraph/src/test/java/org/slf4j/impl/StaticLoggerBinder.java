// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * <p>INTERNAL — experimental stack-graphs work (legend-pure-m3-stackgraph test scope only). No module
 * outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Task 10 post-review addition. Without an SLF4J binding on the classpath, SLF4J silently falls back to
 * its no-op logger (see every corpus run's "SLF4J: Defaulting to no-operation (NOP) logger implementation"
 * line), which meant {@code ShadowAttachingRunListener}'s attach/no-attach {@code INFO}/{@code WARN} lines —
 * the only audit trail that a shadow actually attached to a given test class's runtime — were never visible
 * in any corpus run's output. The obvious fix (add an {@code slf4j-simple} test dependency) is blocked by
 * the root pom's {@code bannedDependencies} enforcer rule, which whitelists only {@code slf4j-api} and
 * {@code jcl-over-slf4j} from the {@code org.slf4j} group — no concrete logging binding is permitted
 * anywhere in this repository. This package (the exact {@code org.slf4j.impl} package + class name SLF4J
 * 1.7.x's static binder lookup requires) is a from-scratch, dependency-free binding instead: it satisfies
 * the same need (visible attach/no-attach lines in corpus console output) without adding any artifact the
 * enforcer rule would need to know about — this class and {@link ConsoleLoggerFactory}/{@link ConsoleLogger}
 * are ordinary test-scope source files owned by this module, built only from the already-permitted
 * {@code slf4j-api} (whose {@code org.slf4j.helpers} package ships exactly the {@link
 * org.slf4j.helpers.MarkerIgnoringBase}/{@link org.slf4j.helpers.MessageFormatter} building blocks this kind
 * of minimal binding is meant to be built from).</p>
 */
public final class StaticLoggerBinder implements LoggerFactoryBinder
{
    /** Required by the SLF4J 1.7.x static-binder convention; informational only. */
    public static final String REQUESTED_API_VERSION = "1.7";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private final ILoggerFactory loggerFactory = new ConsoleLoggerFactory();

    private StaticLoggerBinder()
    {
    }

    /**
     * @return the singleton instance — the exact static accessor SLF4J 1.7.x's {@code LoggerFactory} looks
     *         for by reflection on this exact class.
     */
    public static StaticLoggerBinder getSingleton()
    {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory()
    {
        return this.loggerFactory;
    }

    @Override
    public String getLoggerFactoryClassStr()
    {
        return ConsoleLoggerFactory.class.getName();
    }
}
