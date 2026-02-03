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

public class VariableInfo
{
    private final String name;
    private final CoreInstance value;
    private final String typeName;
    private final boolean isLocal;

    public VariableInfo(String name, CoreInstance value, boolean isLocal)
    {
        this.name = name;
        this.value = value;
        this.typeName = extractTypeName(value);
        this.isLocal = isLocal;
    }

    private String extractTypeName(CoreInstance value)
    {
        if (value == null)
        {
            return "Nil";
        }
        CoreInstance classifier = value.getClassifier();
        if (classifier != null)
        {
            return classifier.getName();
        }
        return "Unknown";
    }

    public String getName()
    {
        return name;
    }

    public CoreInstance getValue()
    {
        return value;
    }

    public String getTypeName()
    {
        return typeName;
    }

    public boolean isLocal()
    {
        return isLocal;
    }

    public String getValuePreview()
    {
        return getValuePreview(50);
    }

    public String getValuePreview(int maxLength)
    {
        if (value == null)
        {
            return "nil";
        }
        String str = value.print("", 1);
        if (str.length() > maxLength)
        {
            return str.substring(0, maxLength - 3) + "...";
        }
        return str;
    }

    @Override
    public String toString()
    {
        return name + ": " + typeName + " = " + getValuePreview();
    }
}
