// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.m3.stackgraph.search;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 */
public final class SymbolStack
{
    public static final SymbolStack EMPTY = new SymbolStack(null, null);

    private final String head;
    private final SymbolStack tail;
    private final int size;
    private final int hash;

    private SymbolStack(String head, SymbolStack tail)
    {
        this.head = head;
        this.tail = tail;
        this.size = (tail == null) ? 0 : tail.size + 1;
        this.hash = (tail == null) ? 17 : (31 * tail.hash) + head.hashCode();
    }

    public boolean isEmpty()
    {
        return this == EMPTY;
    }

    public int size()
    {
        return this.size;
    }

    public String peek()
    {
        return this.head;
    }

    public SymbolStack pop()
    {
        return this.tail;
    }

    public SymbolStack push(String symbol)
    {
        return new SymbolStack(symbol, this);
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof SymbolStack))
        {
            return false;
        }
        SymbolStack s1 = this;
        SymbolStack s2 = (SymbolStack) other;
        if (s1.size != s2.size || s1.hash != s2.hash)
        {
            return false;
        }
        while (s1 != EMPTY)
        {
            if (s2 == EMPTY || !s1.head.equals(s2.head))
            {
                return false;
            }
            s1 = s1.tail;
            s2 = s2.tail;
        }
        return s2 == EMPTY;
    }

    @Override
    public int hashCode()
    {
        return this.hash;
    }
}
