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

public abstract class Breakpoint
{
    private final String id;
    private boolean enabled;
    private String condition;  // Optional: Pure expression to evaluate
    private int hitCount;
    private int hitCountTarget;  // Break only after N hits

    protected Breakpoint(String id)
    {
        this.id = id;
        this.enabled = true;
        this.hitCount = 0;
        this.hitCountTarget = 0;
    }

    public abstract boolean matches(SourceInformation sourceInfo, String functionName);

    public String getId()
    {
        return id;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getCondition()
    {
        return condition;
    }

    public void setCondition(String condition)
    {
        this.condition = condition;
    }

    public int getHitCount()
    {
        return hitCount;
    }

    public void incrementHitCount()
    {
        this.hitCount++;
    }

    public int getHitCountTarget()
    {
        return hitCountTarget;
    }

    public void setHitCountTarget(int target)
    {
        this.hitCountTarget = target;
    }

    public boolean shouldBreakOnHit()
    {
        return hitCountTarget == 0 || hitCount >= hitCountTarget;
    }
}
