// Copyright 2020 Goldman Sachs
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

package org.finos.legend.pure.runtime.java.interpreted.debugger.breakpoint;

import org.finos.legend.pure.m4.coreinstance.SourceInformation;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BreakpointManager
{
    private final ConcurrentHashMap<String, Breakpoint> breakpoints;

    public BreakpointManager()
    {
        this.breakpoints = new ConcurrentHashMap<>();
    }

    /**
     * Add a line breakpoint.
     */
    public LineBreakpoint addLineBreakpoint(String sourceId, int line)
    {
        String id = generateId();
        LineBreakpoint bp = new LineBreakpoint(id, sourceId, line);
        breakpoints.put(id, bp);
        return bp;
    }

    /**
     * Add a function breakpoint.
     */
    public FunctionBreakpoint addFunctionBreakpoint(String functionName)
    {
        String id = generateId();
        FunctionBreakpoint bp = new FunctionBreakpoint(id, functionName);
        breakpoints.put(id, bp);
        return bp;
    }

    /**
     * Remove a breakpoint by ID.
     */
    public boolean removeBreakpoint(String id)
    {
        return breakpoints.remove(id) != null;
    }

    /**
     * Get a breakpoint by ID.
     */
    public Breakpoint getBreakpoint(String id)
    {
        return breakpoints.get(id);
    }

    /**
     * Get all breakpoints.
     */
    public Collection<Breakpoint> getAllBreakpoints()
    {
        return breakpoints.values();
    }

    /**
     * Clear all breakpoints.
     */
    public void clearAll()
    {
        breakpoints.clear();
    }

    /**
     * Check if execution should break at the given location.
     */
    public boolean shouldBreak(SourceInformation sourceInfo, String functionName)
    {
        for (Breakpoint bp : breakpoints.values())
        {
            if (bp.isEnabled() && bp.matches(sourceInfo, functionName))
            {
                bp.incrementHitCount();
                if (bp.shouldBreakOnHit())
                {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateId()
    {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
