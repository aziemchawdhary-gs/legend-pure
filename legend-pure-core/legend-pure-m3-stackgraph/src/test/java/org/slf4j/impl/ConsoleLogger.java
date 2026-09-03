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

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/**
 * <p>INTERNAL — experimental stack-graphs work (legend-pure-m3-stackgraph test scope only). No module
 * outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>See {@link StaticLoggerBinder}'s javadoc for why this dependency-free binding exists. {@code trace}/
 * {@code debug} are disabled no-ops — this binding exists solely to surface {@code
 * ShadowAttachingRunListener}'s attach/no-attach {@code INFO}/{@code WARN} audit-trail lines (and any
 * incidental {@code ERROR}) in corpus console output, not to be a general-purpose logging binding, so
 * keeping the noisier levels off is deliberate. {@code info}/{@code warn}/{@code error} all print through
 * {@link #log}, which reuses {@link MessageFormatter#arrayFormat} — the same {@code {}}-placeholder/trailing
 * -{@link Throwable} handling {@code slf4j-simple} itself is built on — so the existing {@code LOGGER.warn(
 * "...{}: {}", description, e.toString(), e)}-style calls already in {@code ShadowAttachingRunListener}
 * behave identically to how they would under a real binding.</p>
 */
final class ConsoleLogger extends MarkerIgnoringBase
{
    ConsoleLogger(String name)
    {
        this.name = name;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return false;
    }

    @Override
    public void trace(String msg)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void trace(String format, Object arg)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void trace(String format, Object arg1, Object arg2)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void trace(String format, Object... arguments)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void trace(String msg, Throwable t)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public boolean isDebugEnabled()
    {
        return false;
    }

    @Override
    public void debug(String msg)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void debug(String format, Object arg)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void debug(String format, Object arg1, Object arg2)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void debug(String format, Object... arguments)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public void debug(String msg, Throwable t)
    {
        // Disabled — see class javadoc.
    }

    @Override
    public boolean isInfoEnabled()
    {
        return true;
    }

    @Override
    public void info(String msg)
    {
        log("INFO", msg);
    }

    @Override
    public void info(String format, Object arg)
    {
        log("INFO", format, arg);
    }

    @Override
    public void info(String format, Object arg1, Object arg2)
    {
        log("INFO", format, arg1, arg2);
    }

    @Override
    public void info(String format, Object... arguments)
    {
        log("INFO", format, arguments);
    }

    @Override
    public void info(String msg, Throwable t)
    {
        print("INFO", msg, t);
    }

    @Override
    public boolean isWarnEnabled()
    {
        return true;
    }

    @Override
    public void warn(String msg)
    {
        log("WARN", msg);
    }

    @Override
    public void warn(String format, Object arg)
    {
        log("WARN", format, arg);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2)
    {
        log("WARN", format, arg1, arg2);
    }

    @Override
    public void warn(String format, Object... arguments)
    {
        log("WARN", format, arguments);
    }

    @Override
    public void warn(String msg, Throwable t)
    {
        print("WARN", msg, t);
    }

    @Override
    public boolean isErrorEnabled()
    {
        return true;
    }

    @Override
    public void error(String msg)
    {
        log("ERROR", msg);
    }

    @Override
    public void error(String format, Object arg)
    {
        log("ERROR", format, arg);
    }

    @Override
    public void error(String format, Object arg1, Object arg2)
    {
        log("ERROR", format, arg1, arg2);
    }

    @Override
    public void error(String format, Object... arguments)
    {
        log("ERROR", format, arguments);
    }

    @Override
    public void error(String msg, Throwable t)
    {
        print("ERROR", msg, t);
    }

    private void log(String level, String format, Object... args)
    {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, args);
        print(level, tuple.getMessage(), tuple.getThrowable());
    }

    private void print(String level, String message, Throwable throwable)
    {
        System.out.println("[" + level + "] " + this.name + " - " + message);
        if (throwable != null)
        {
            throwable.printStackTrace(System.out);
        }
    }
}
