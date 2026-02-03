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

/**
 * Pure Language Debugger
 *
 * This package provides a complete debugging solution for the Pure language tree-walk interpreter.
 *
 * Key Features:
 * - Line and function breakpoints
 * - Step execution (into, over, out)
 * - Variable inspection
 * - Call stack viewing
 * - Event-driven architecture
 *
 * Basic Usage:
 *
 * <pre>{@code
 * // Create a debug session
 * DebugSession session = new DebugSession();
 *
 * // Add breakpoints
 * session.getBreakpointManager().addLineBreakpoint("/path/to/file.pure", 10);
 * session.getBreakpointManager().addFunctionBreakpoint("myFunction");
 *
 * // Add event listener
 * session.addListener(new DebugEventListener() {
 *     public void onPaused(CoreInstance expr, String reason, ExecutionState state) {
 *         System.out.println("Paused: " + reason);
 *         System.out.println("Call stack:\n" + state.formatCallStack());
 *         System.out.println("Variables:\n" + state.formatVariables());
 *
 *         // Continue execution
 *         session.continueExecution();
 *     }
 *
 *     public void onExecutionStarted(CoreInstance function) {
 *         System.out.println("Started execution");
 *     }
 *
 *     public void onExecutionEnded(CoreInstance function) {
 *         System.out.println("Finished execution");
 *     }
 * });
 *
 * // Execute with debugging enabled
 * FunctionExecutionInterpreted execution = ...;
 * CoreInstance result = execution.start(function, args, session.getProfiler());
 * }</pre>
 *
 * Architecture:
 * - DebugSession: Main controller for debugging operations
 * - DebugProfiler: Hooks into the interpreter via the Profiler interface
 * - BreakpointManager: Manages breakpoint definitions and matching
 * - StepController: Handles step execution logic
 * - ExecutionState: Captures and formats execution state for inspection
 */
package org.finos.legend.pure.runtime.java.interpreted.debugger;
