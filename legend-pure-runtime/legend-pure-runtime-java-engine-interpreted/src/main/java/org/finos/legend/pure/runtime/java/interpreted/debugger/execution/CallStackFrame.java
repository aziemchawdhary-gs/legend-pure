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

package org.finos.legend.pure.runtime.java.interpreted.debugger.execution;

import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;

public class CallStackFrame
{
    private final String functionName;
    private final SourceInformation sourceInfo;
    private final CoreInstance functionExpression;
    private final int frameIndex;

    public CallStackFrame(int frameIndex, CoreInstance functionExpression)
    {
        this.frameIndex = frameIndex;
        this.functionExpression = functionExpression;
        this.sourceInfo = functionExpression.getSourceInformation();
        this.functionName = extractFunctionName(functionExpression);
    }

    private String extractFunctionName(CoreInstance expr)
    {
        CoreInstance func = expr.getValueForMetaPropertyToOne("func");
        if (func != null)
        {
            CoreInstance name = func.getValueForMetaPropertyToOne("name");
            if (name != null)
            {
                return name.getName();
            }
            // Try functionName property for lambdas
            CoreInstance funcName = func.getValueForMetaPropertyToOne("functionName");
            if (funcName != null)
            {
                return funcName.getName();
            }
        }
        return "<anonymous>";
    }

    public String getFunctionName()
    {
        return functionName;
    }

    public SourceInformation getSourceInfo()
    {
        return sourceInfo;
    }

    public CoreInstance getFunctionExpression()
    {
        return functionExpression;
    }

    public int getFrameIndex()
    {
        return frameIndex;
    }

    public String getLocationString()
    {
        if (sourceInfo == null)
        {
            return functionName + " (unknown location)";
        }
        return functionName + " at " + sourceInfo.getSourceId() +
               ":" + sourceInfo.getLine();
    }

    @Override
    public String toString()
    {
        return "#" + frameIndex + " " + getLocationString();
    }
}
