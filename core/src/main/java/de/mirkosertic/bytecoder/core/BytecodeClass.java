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

import de.mirkosertic.bytecoder.annotations.OverrideParentClass;

public class BytecodeClass {

    private final BytecodeConstantPool constantPool;
    private final BytecodeAccessFlags accessFlags;
    private final BytecodeClassinfoConstant thisClass;
    private final BytecodeClassinfoConstant superClass;
    private final BytecodeInterface[] interfaces;
    private final BytecodeField[] fields;
    private final BytecodeMethod[] methods;
    private final BytecodeAttributeInfo[] classAttributes;

    public BytecodeClass(BytecodeConstantPool aConstantPool, BytecodeAccessFlags aAccessFlags, BytecodeClassinfoConstant aThisClass, BytecodeClassinfoConstant aSuperClass, BytecodeInterface[] aInterfaces, BytecodeField[] aFields, BytecodeMethod[] aMethods, BytecodeAttributeInfo[] aClassAttributes) {
        constantPool = aConstantPool;
        accessFlags = aAccessFlags;
        thisClass = aThisClass;
        superClass = aSuperClass;
        interfaces = aInterfaces;
        fields = aFields;
        methods = aMethods;
        classAttributes = aClassAttributes;
    }

    public BytecodeAccessFlags getAccessFlags() {
        return accessFlags;
    }

    public BytecodeAnnotations getAnnotations() {
        return new BytecodeAnnotations(classAttributes);
    }

    public BytecodeConstantPool getConstantPool() {
        return constantPool;
    }

    public BytecodeField fieldByName(String aName) {
        for (BytecodeField theField : fields) {
            if (theField.getName().stringValue().equals(aName)) {
                return theField;
            }
        }
        return null;
    }

    public BytecodeMethod methodByNameAndSignatureOrNull(String aMethodName, BytecodeMethodSignature aSignature) {
        for (BytecodeMethod theMethod : methods) {
            if (aMethodName.equals(theMethod.getName().stringValue()) && theMethod.getSignature().metchesExactlyTo(aSignature)) {
                return theMethod;
            }
        }
        return null;
    }

    public BytecodeClassinfoConstant getSuperClass() {
        BytecodeAnnotation theDelegatesTo = getAnnotations().getAnnotationByType(OverrideParentClass.class.getName());
        if (theDelegatesTo != null) {
            BytecodeAnnotation.ElementValue theParentOverride = ( BytecodeAnnotation.ClassElementValue) theDelegatesTo.getElementValueByName("parentClass");
            return new BytecodeClassinfoConstant(-1, null, null) {
                @Override
                public BytecodeUtf8Constant getConstant() {
                    return new BytecodeUtf8Constant(theParentOverride.stringValue().replace(".","/"));
                }
            };
        }
        return superClass;
    }

    public BytecodeInterface[] getInterfaces() {
        return interfaces;
    }

    public BytecodeMethod classInitializerOrNull() {
        for (BytecodeMethod theMethod : methods) {
            if ("<clinit>".equalsIgnoreCase(theMethod.getName().stringValue())) {
                return theMethod;
            }
        }
        return null;
    }
}
