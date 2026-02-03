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

import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.interpreted.debugger.breakpoint.BreakpointManager;
import org.finos.legend.pure.runtime.java.interpreted.debugger.execution.ExecutionState;
import org.finos.legend.pure.runtime.java.interpreted.debugger.execution.StepController;
import org.finos.legend.pure.runtime.java.interpreted.debugger.execution.StepMode;

import java.util.concurrent.CopyOnWriteArrayList;

public class DebugSession
{
    private final DebugProfiler profiler;
    private final BreakpointManager breakpointManager;
    private final StepController stepController;
    private final CopyOnWriteArrayList<DebugEventListener> listeners;

    private volatile boolean active;
    private volatile ExecutionState lastPausedState;

    public DebugSession()
    {
        this.profiler = new DebugProfiler(this);
        this.breakpointManager = new BreakpointManager();
        this.stepController = new StepController();
        this.listeners = new CopyOnWriteArrayList<>();
        this.active = false;
    }

    /**
     * Get the profiler to pass to the interpreter.
     */
    public DebugProfiler getProfiler()
    {
        return profiler;
    }

    public BreakpointManager getBreakpointManager()
    {
        return breakpointManager;
    }

    public StepController getStepController()
    {
        return stepController;
    }

    // ==================== Command Methods ====================

    /**
     * Continue execution until next breakpoint or completion.
     */
    public void continueExecution()
    {
        if (!profiler.isPaused())
        {
            throw new IllegalStateException("Not paused");
        }
        stepController.setMode(StepMode.CONTINUE);
        profiler.resume();
    }

    /**
     * Step into the next function call.
     */
    public void stepInto()
    {
        if (!profiler.isPaused())
        {
            throw new IllegalStateException("Not paused");
        }
        stepController.setMode(StepMode.INTO);
        stepController.recordCurrentDepth(lastPausedState.getCallStackDepth());
        profiler.resume();
    }

    /**
     * Step over the current expression.
     */
    public void stepOver()
    {
        if (!profiler.isPaused())
        {
            throw new IllegalStateException("Not paused");
        }
        stepController.setMode(StepMode.OVER);
        stepController.recordCurrentDepth(lastPausedState.getCallStackDepth());
        profiler.resume();
    }

    /**
     * Step out of the current function.
     */
    public void stepOut()
    {
        if (!profiler.isPaused())
        {
            throw new IllegalStateException("Not paused");
        }
        stepController.setMode(StepMode.OUT);
        stepController.recordCurrentDepth(lastPausedState.getCallStackDepth());
        profiler.resume();
    }

    /**
     * Get the current execution state (only valid when paused).
     */
    public ExecutionState getExecutionState()
    {
        if (!profiler.isPaused())
        {
            throw new IllegalStateException("Not paused");
        }
        return lastPausedState;
    }

    /**
     * Check if the debugger is currently paused.
     */
    public boolean isPaused()
    {
        return profiler.isPaused();
    }

    /**
     * Check if the debug session is active.
     */
    public boolean isActive()
    {
        return active;
    }

    // ==================== Event Listeners ====================

    public void addListener(DebugEventListener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(DebugEventListener listener)
    {
        listeners.remove(listener);
    }

    // ==================== Internal Notifications ====================

    void notifyExecutionStarted(CoreInstance function)
    {
        active = true;
        for (DebugEventListener listener : listeners)
        {
            listener.onExecutionStarted(function);
        }
    }

    void notifyExecutionEnded(CoreInstance function)
    {
        active = false;
        for (DebugEventListener listener : listeners)
        {
            listener.onExecutionEnded(function);
        }
    }

    void notifyPaused(CoreInstance expression, String reason, ExecutionState state)
    {
        this.lastPausedState = state;
        for (DebugEventListener listener : listeners)
        {
            listener.onPaused(expression, reason, state);
        }
    }
}
