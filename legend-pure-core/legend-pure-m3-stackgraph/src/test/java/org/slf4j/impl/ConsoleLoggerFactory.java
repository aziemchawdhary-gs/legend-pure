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
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>INTERNAL — experimental stack-graphs work (legend-pure-m3-stackgraph test scope only). No module
 * outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>See {@link StaticLoggerBinder}'s javadoc for why this dependency-free binding exists. One {@link
 * ConsoleLogger} per logger name, cached so repeated {@code LoggerFactory.getLogger(...)} calls for the same
 * class (e.g. {@code ShadowAttachingRunListener}'s own {@code LOGGER} field, initialized once as a {@code
 * static final}) don't allocate repeatedly.</p>
 */
final class ConsoleLoggerFactory implements ILoggerFactory
{
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name)
    {
        return this.loggers.computeIfAbsent(name, ConsoleLogger::new);
    }
}
