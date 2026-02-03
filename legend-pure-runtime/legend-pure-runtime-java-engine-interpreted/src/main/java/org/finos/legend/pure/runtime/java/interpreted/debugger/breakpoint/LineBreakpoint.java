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

public class LineBreakpoint extends Breakpoint
{
    private final String sourceId;
    private final int line;

    public LineBreakpoint(String id, String sourceId, int line)
    {
        super(id);
        this.sourceId = sourceId;
        this.line = line;
    }

    @Override
    public boolean matches(SourceInformation sourceInfo, String functionName)
    {
        if (sourceInfo == null)
        {
            return false;
        }

        // Check if source file matches
        if (!sourceId.equals(sourceInfo.getSourceId()))
        {
            return false;
        }

        // Check if line is within the expression range
        return line >= sourceInfo.getStartLine() && line <= sourceInfo.getEndLine();
    }

    public String getSourceId()
    {
        return sourceId;
    }

    public int getLine()
    {
        return line;
    }

    @Override
    public String toString()
    {
        return "LineBreakpoint{" + sourceId + ":" + line + "}";
    }
}
