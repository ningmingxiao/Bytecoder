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

public class Variable extends Value {

    private TypeRef type;
    private final String name;
    private Value value;

    public Variable(TypeRef aType, String aName, Value aValue) {
        type = aType;
        name = aName;
        value = aValue;
    }

    @Override
    public TypeRef resolveType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Value getValue() {
        return value;
    }

    public Variable withNewValue(Value aNewValue) {
        return new Variable(type, name, aNewValue);
    }

    public Variable withNewValue(TypeRef aType, Value aNewValue) {
        return new Variable(aType, name, aNewValue);
    }

    public void setValue(Value aNewValue) {
        value = aNewValue;
    }

    public void setType(TypeRef aType) {
        type = aType;
    }

    @Override
    public String toString() {
        return name + " -> " + value + " of type " + resolveType();
    }
}