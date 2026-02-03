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
import org.finos.legend.pure.runtime.java.interpreted.debugger.execution.ExecutionState;

/**
 * Interface for receiving debug events.
 * Implement this to build a debug UI or client.
 */
public interface DebugEventListener
{
    /**
     * Called when execution starts.
     */
    void onExecutionStarted(CoreInstance function);

    /**
     * Called when execution completes normally.
     */
    void onExecutionEnded(CoreInstance function);

    /**
     * Called when execution pauses (breakpoint or step).
     */
    void onPaused(CoreInstance expression, String reason, ExecutionState state);

    /**
     * Called when execution resumes.
     */
    default void onResumed() {}

    /**
     * Called on execution error.
     */
    default void onError(Exception error, ExecutionState state) {}

    /**
     * Called when a breakpoint is added.
     */
    default void onBreakpointAdded(String breakpointId) {}

    /**
     * Called when a breakpoint is removed.
     */
    default void onBreakpointRemoved(String breakpointId) {}
}
