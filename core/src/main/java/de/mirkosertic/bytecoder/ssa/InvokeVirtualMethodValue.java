/*
 * Copyright 2017 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.ssa;

import de.mirkosertic.bytecoder.core.BytecodeNameAndTypeConstant;

import java.util.List;

public class InvokeVirtualMethodValue extends Value {

    private final BytecodeNameAndTypeConstant method;
    private final Variable target;
    private final List<Variable> arguments;

    public InvokeVirtualMethodValue(BytecodeNameAndTypeConstant aMethod, Variable aTarget,
                                    List<Variable> aArguments) {
        method = aMethod;
        target = aTarget.usedBy(this);
        arguments = aArguments;
        for (Variable theVariable : aArguments) {
            theVariable.usedBy(this);
        }
    }

    public BytecodeNameAndTypeConstant getMethod() {
        return method;
    }

    public Variable getTarget() {
        return target;
    }

    public List<Variable> getArguments() {
        return arguments;
    }
}
