// Copyright 2025 Goldman Sachs
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

package org.finos.legend.pure.m3.tests.elements._class;

import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.junit.After;
import org.junit.Test;

public class AbstractTestDispatchQualifiedProperties extends AbstractPureTestWithCoreCompiled
{
    @After
    public void cleanRuntime()
    {
        runtime.delete("fromString.pure");
        runtime.compile();
    }

    @Test
    public void testFunction()
    {
        compileTestSource("fromString.pure",
                "Class A" +
                        "{" +
                        "   f(){'a'}:String[1];" +
                        "   f(s:String[1]){'a'+$s}:String[1];" +
                        "}" +
                        "Class B extends A" +
                        "{" +
                        "   f(){'b'}:String[1];" +
                        "   f(s:String[1]){'b'+$s}:String[1];" +
                        "}" +
                        "Class D" +
                        "{" +
                        "  i : Integer[1];" +
                        "}" +
                        "Class C" +
                        "{" +
                        "   f : Function<{Integer[1]->Integer[1]}>[1];" +
                        "   f(i:Integer[1]){^D(i=$this.f->eval($i));}:D[1];" +
                        "}" +
                        "function n():A[1]" +
                        "{ " +
                        "   ^B();" +
                        "}" +
                        "function myfunc(i:Integer[1]):Integer[1]" +
                        "{ " +
                        "   $i+1;" +
                        "}" +
                        "function testNew():Any[*]\n" +
                        "{\n" +
                        "   assertEquals('a', ^A().f);" +
                        "   assertEquals('b', ^B().f);" +
                        "   assertEquals('b', ^B()->cast(@A).f);" +
                        "   assertEquals('bok', n().f('ok'));" +
                        "   assertEquals('b', n().f);" +
                        "   assertEquals(['a','b','a','b'], [^A(),^B(),^A(),^B()]->cast(@A).f);" +
                        "   assertEquals(2, ^C(f=myfunc_Integer_1__Integer_1_)->evaluateAndDeactivate().f(1).i);" +
                        "}\n");
        execute("testNew():Any[*]");
    }
}
