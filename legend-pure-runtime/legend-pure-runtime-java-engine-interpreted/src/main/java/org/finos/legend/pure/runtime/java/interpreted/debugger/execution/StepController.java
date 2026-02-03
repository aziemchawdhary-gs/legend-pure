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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class StepController
{
    private final AtomicReference<StepMode> mode;
    private final AtomicInteger recordedDepth;
    private final AtomicReference<CoreInstance> lastExpression;

    public StepController()
    {
        this.mode = new AtomicReference<>(StepMode.CONTINUE);
        this.recordedDepth = new AtomicInteger(0);
        this.lastExpression = new AtomicReference<>();
    }

    public void setMode(StepMode mode)
    {
        this.mode.set(mode);
    }

    public StepMode getMode()
    {
        return mode.get();
    }

    public void recordCurrentDepth(int depth)
    {
        this.recordedDepth.set(depth);
    }

    /**
     * Determine if we should pause at this expression based on step mode.
     */
    public boolean shouldPauseAtExpression(CoreInstance expression, CoreInstance parent)
    {
        StepMode currentMode = mode.get();

        if (currentMode == StepMode.CONTINUE)
        {
            return false;  // Only stop at breakpoints
        }

        // For INTO mode, stop at every expression
        if (currentMode == StepMode.INTO)
        {
            // Reset to CONTINUE after pausing
            mode.set(StepMode.CONTINUE);
            return true;
        }

        // For OVER and OUT modes, we need to track call depth
        // This is a simplified check - the actual implementation
        // would need access to the current call stack depth

        if (currentMode == StepMode.OVER || currentMode == StepMode.OUT)
        {
            // This will be checked with actual depth in shouldPauseAtDepth
            return false;
        }

        return false;
    }

    /**
     * Check if we should pause based on current call stack depth.
     * This is called from the debug profiler with actual depth info.
     */
    public boolean shouldPauseAtDepth(int currentDepth)
    {
        StepMode currentMode = mode.get();
        int recorded = recordedDepth.get();

        if (currentMode == StepMode.OVER)
        {
            if (currentDepth <= recorded)
            {
                mode.set(StepMode.CONTINUE);
                return true;
            }
        }
        else if (currentMode == StepMode.OUT)
        {
            if (currentDepth < recorded)
            {
                mode.set(StepMode.CONTINUE);
                return true;
            }
        }

        return false;
    }

    /**
     * Called when an expression finishes execution.
     * Used for step-out detection.
     */
    public void notifyExpressionFinished(CoreInstance expression)
    {
        lastExpression.set(expression);
    }

    /**
     * Reset step state.
     */
    public void reset()
    {
        mode.set(StepMode.CONTINUE);
        recordedDepth.set(0);
        lastExpression.set(null);
    }
}
