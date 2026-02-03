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

package org.finos.legend.pure.runtime.java.interpreted.debugger;

import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.stack.MutableStack;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;
import org.finos.legend.pure.runtime.java.interpreted.VariableContext;
import org.finos.legend.pure.runtime.java.interpreted.debugger.execution.ExecutionState;
import org.finos.legend.pure.runtime.java.interpreted.profiler.Profiler;

import java.util.Stack;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class DebugProfiler implements Profiler
{
    private final DebugSession session;
    private final AtomicReference<CountDownLatch> pauseLatch = new AtomicReference<>();

    // Captured state for inspection while paused
    private volatile CoreInstance currentExpression;
    private volatile VariableContext currentVariableContext;
    private volatile MutableStack<CoreInstance> currentCallStack;
    private volatile Stack<MutableMap<String, CoreInstance>> currentTypeParameters;

    public DebugProfiler(DebugSession session)
    {
        this.session = session;
    }

    @Override
    public void start(CoreInstance coreInstance)
    {
        // Called at the start of top-level function execution
        session.notifyExecutionStarted(coreInstance);
    }

    @Override
    public void end(CoreInstance coreInstance)
    {
        // Called when top-level execution completes
        session.notifyExecutionEnded(coreInstance);
    }

    @Override
    public void startExecutingFunctionExpression(CoreInstance instance, CoreInstance parent)
    {
        // This is called BEFORE each function expression is evaluated
        // Perfect hook point for breakpoints and stepping

        SourceInformation sourceInfo = instance.getSourceInformation();
        String functionName = extractFunctionName(instance);

        boolean shouldPause = false;
        String pauseReason = null;

        // Check for breakpoints
        if (session.getBreakpointManager().shouldBreak(sourceInfo, functionName))
        {
            shouldPause = true;
            pauseReason = "Breakpoint hit";
        }

        // Check for step conditions
        if (session.getStepController().shouldPauseAtExpression(instance, parent))
        {
            shouldPause = true;
            pauseReason = "Step";
        }

        // Check depth-based stepping (step over/out)
        if (currentCallStack != null && session.getStepController().shouldPauseAtDepth(currentCallStack.size()))
        {
            shouldPause = true;
            pauseReason = "Step";
        }

        if (shouldPause)
        {
            pauseExecution(instance, pauseReason);
        }
    }

    @Override
    public void finishedExecutingFunctionExpression(CoreInstance instance)
    {
        // Called AFTER function expression evaluation
        // Useful for step-out detection
        session.getStepController().notifyExpressionFinished(instance);
    }

    /**
     * Pause execution and wait for debugger command.
     * This blocks the interpreter thread.
     */
    private void pauseExecution(CoreInstance expression, String reason)
    {
        this.currentExpression = expression;

        CountDownLatch latch = new CountDownLatch(1);
        pauseLatch.set(latch);

        // Notify listeners that we've paused
        session.notifyPaused(expression, reason, createExecutionState());

        try
        {
            // Block until resume() is called
            latch.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Debug session interrupted", e);
        }
        finally
        {
            pauseLatch.set(null);
        }
    }

    /**
     * Resume execution after a pause.
     * Called from the debug session when a continue/step command is issued.
     */
    public void resume()
    {
        CountDownLatch latch = pauseLatch.get();
        if (latch != null)
        {
            latch.countDown();
        }
    }

    /**
     * Called by the interpreter to provide current execution context.
     * This method allows us to capture state that's not available through Profiler.
     */
    public void captureExecutionContext(
            VariableContext variableContext,
            MutableStack<CoreInstance> callStack,
            Stack<MutableMap<String, CoreInstance>> typeParameters)
    {
        this.currentVariableContext = variableContext;
        this.currentCallStack = callStack;
        this.currentTypeParameters = typeParameters;
    }

    private ExecutionState createExecutionState()
    {
        return new ExecutionState(
            currentExpression,
            currentVariableContext,
            currentCallStack,
            currentTypeParameters
        );
    }

    private String extractFunctionName(CoreInstance functionExpression)
    {
        CoreInstance func = functionExpression.getValueForMetaPropertyToOne("func");
        if (func != null)
        {
            CoreInstance name = func.getValueForMetaPropertyToOne("name");
            if (name != null)
            {
                return name.getName();
            }
        }
        return null;
    }

    public boolean isPaused()
    {
        return pauseLatch.get() != null;
    }

    public CoreInstance getCurrentExpression()
    {
        return currentExpression;
    }
}
