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
package de.mirkosertic.bytecoder.core;

public class BytecodeBootstrapMethod {

    private final int methodRef;
    private final int[] arguments;
    private final BytecodeConstantPool constantPool;

    public BytecodeBootstrapMethod(int aMethodRef, int[] aArguments, BytecodeConstantPool aConstantPool) {
        methodRef = aMethodRef;
        arguments = aArguments;
        constantPool = aConstantPool;
    }

    public int getMethodRefIndex() {
        return methodRef;
    }

    public BytecodeMethodHandleConstant getMethodRef() {
        return (BytecodeMethodHandleConstant) constantPool.constantByIndex(methodRef - 1);
    }

    public BytecodeConstant[] getArguments() {
        BytecodeConstant[] theResult = new BytecodeConstant[arguments.length];
        for (int i=0;i<arguments.length;i++) {
            theResult[i] = constantPool.constantByIndex(arguments[i]);
        }
        return theResult;
    }
}