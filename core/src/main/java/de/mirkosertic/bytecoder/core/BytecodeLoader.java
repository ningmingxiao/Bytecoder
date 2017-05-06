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

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class BytecodeLoader {

    private final BytecodePackageReplacer packageReplacer;

    public BytecodeLoader(BytecodePackageReplacer aPackageReplacer) {
        this.packageReplacer = aPackageReplacer;
    }

    public BytecodeClass loadByteCode(BytecodeObjectTypeRef aTypeRef) throws IOException, ClassNotFoundException {

        String theResourceName = "/" + packageReplacer.replaceTypeIn(aTypeRef).name().replace(".", "/") + ".class";
        InputStream theStream = getClass().getResourceAsStream(theResourceName);
        if (theStream == null) {
            throw new ClassNotFoundException(theResourceName);
        }
        try (DataInputStream dis = new DataInputStream(theStream)) {
            BytecodeClassParser parser = parseHeader(dis);
            return parser.parseBody(dis);
        }
    }

    private BytecodeClassParser parseHeader(DataInput aStream) throws IOException {
        int theMagic = aStream.readInt();
        if (!(theMagic == 0xCAFEBABE)) {
            throw new IllegalArgumentException("Wrong class file format : " + theMagic);
        }
        int theMinorVersion = aStream.readUnsignedShort();
        int theMajorVersion = aStream.readUnsignedShort();
        switch (theMajorVersion) {
            case 51:
                return new Bytecode5xClassParser(new Bytecode5XProgramParser(), new BytecodeSignatureParser(packageReplacer), packageReplacer);
            case 52:
                return new Bytecode5xClassParser(new Bytecode5XProgramParser(), new BytecodeSignatureParser(packageReplacer), packageReplacer);
        }
        throw new IllegalArgumentException("Not Supported bytecode format : " + theMajorVersion);
    }
}