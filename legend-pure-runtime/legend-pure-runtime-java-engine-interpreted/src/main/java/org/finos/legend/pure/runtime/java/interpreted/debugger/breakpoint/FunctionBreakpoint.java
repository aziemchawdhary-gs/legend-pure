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

public class FunctionBreakpoint extends Breakpoint
{
    private final String functionName;
    private final boolean matchPartial;

    public FunctionBreakpoint(String id, String functionName)
    {
        this(id, functionName, false);
    }

    public FunctionBreakpoint(String id, String functionName, boolean matchPartial)
    {
        super(id);
        this.functionName = functionName;
        this.matchPartial = matchPartial;
    }

    @Override
    public boolean matches(SourceInformation sourceInfo, String functionName)
    {
        if (functionName == null)
        {
            return false;
        }

        if (matchPartial)
        {
            return functionName.contains(this.functionName);
        }
        else
        {
            return functionName.equals(this.functionName);
        }
    }

    public String getFunctionName()
    {
        return functionName;
    }

    @Override
    public String toString()
    {
        return "FunctionBreakpoint{" + functionName + "}";
    }
}
