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

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.stack.MutableStack;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;
import org.finos.legend.pure.runtime.java.interpreted.VariableContext;

import java.util.Stack;

public class ExecutionState
{
    private final CoreInstance currentExpression;
    private final VariableContext variableContext;
    private final MutableStack<CoreInstance> callStack;
    private final Stack<MutableMap<String, CoreInstance>> typeParameters;

    private MutableList<CallStackFrame> cachedFrames;
    private MutableList<VariableInfo> cachedVariables;

    public ExecutionState(
            CoreInstance currentExpression,
            VariableContext variableContext,
            MutableStack<CoreInstance> callStack,
            Stack<MutableMap<String, CoreInstance>> typeParameters)
    {
        this.currentExpression = currentExpression;
        this.variableContext = variableContext;
        this.callStack = callStack;
        this.typeParameters = typeParameters;
    }

    public CoreInstance getCurrentExpression()
    {
        return currentExpression;
    }

    public SourceInformation getSourceInfo()
    {
        return currentExpression != null ? currentExpression.getSourceInformation() : null;
    }

    public int getCallStackDepth()
    {
        return callStack != null ? callStack.size() : 0;
    }

    /**
     * Get formatted call stack frames.
     */
    public MutableList<CallStackFrame> getCallStackFrames()
    {
        if (cachedFrames == null)
        {
            cachedFrames = Lists.mutable.empty();
            if (callStack != null)
            {
                // Stack is LIFO, so we iterate from top to bottom
                int frameIndex = 0;
                for (CoreInstance expr : callStack.toList().reverseThis())
                {
                    cachedFrames.add(new CallStackFrame(frameIndex++, expr));
                }
            }
        }
        return cachedFrames;
    }

    /**
     * Get variables visible in the current scope.
     */
    public MutableList<VariableInfo> getVariables()
    {
        if (cachedVariables == null)
        {
            cachedVariables = Lists.mutable.empty();
            if (variableContext != null)
            {
                collectVariables(variableContext, true, cachedVariables);
            }
        }
        return cachedVariables;
    }

    private void collectVariables(VariableContext ctx, boolean isLocal,
                                   MutableList<VariableInfo> result)
    {
        MutableSet<String> localNames = ctx.getLocalVariableNames();
        for (String name : localNames)
        {
            CoreInstance value = ctx.getLocalValue(name);
            result.add(new VariableInfo(name, value, isLocal));
        }

        VariableContext parent = ctx.getParent();
        if (parent != null)
        {
            collectVariables(parent, false, result);
        }
    }

    /**
     * Get variables in a specific stack frame.
     */
    public MutableList<VariableInfo> getVariablesInFrame(int frameIndex)
    {
        // This would require storing VariableContext per frame
        // For now, return current context variables
        return getVariables();
    }

    /**
     * Get a specific variable by name.
     */
    public VariableInfo getVariable(String name)
    {
        if (variableContext != null)
        {
            CoreInstance value = variableContext.getValue(name);
            if (value != null)
            {
                return new VariableInfo(name, value,
                    variableContext.getLocalValue(name) != null);
            }
        }
        return null;
    }

    /**
     * Format the call stack as a string.
     */
    public String formatCallStack()
    {
        StringBuilder sb = new StringBuilder();
        for (CallStackFrame frame : getCallStackFrames())
        {
            sb.append(frame.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Format variables as a string.
     */
    public String formatVariables()
    {
        StringBuilder sb = new StringBuilder();
        for (VariableInfo var : getVariables())
        {
            sb.append(var.toString()).append("\n");
        }
        return sb.toString();
    }
}
