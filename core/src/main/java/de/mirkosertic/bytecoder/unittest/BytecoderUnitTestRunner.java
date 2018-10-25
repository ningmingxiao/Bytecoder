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
package de.mirkosertic.bytecoder.unittest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.apache.commons.io.IOUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import de.mirkosertic.bytecoder.backend.CompileOptions;
import de.mirkosertic.bytecoder.backend.CompileTarget;
import de.mirkosertic.bytecoder.backend.js.JSCompileResult;
import de.mirkosertic.bytecoder.backend.wasm.WASMCompileResult;
import de.mirkosertic.bytecoder.classlib.ExceptionRethrower;
import de.mirkosertic.bytecoder.core.BytecodeMethodSignature;
import de.mirkosertic.bytecoder.core.BytecodeObjectTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeTypeRef;
import de.mirkosertic.bytecoder.optimizer.KnownOptimizer;
import de.mirkosertic.bytecoder.ssa.ControlFlowProcessingException;

public class BytecoderUnitTestRunner extends ParentRunner<FrameworkMethod> {

    private static final Slf4JLogger LOGGER = new Slf4JLogger();

    private static ChromeDriverService DRIVERSERVICE;

    private final List<FrameworkMethod> testMethods;
    private final TestClass testClass;

    public BytecoderUnitTestRunner(final Class aClass) throws InitializationError {
        super(aClass);
        testClass = new TestClass(aClass);
        testMethods = new ArrayList<>();

        final Method[] classMethods = aClass.getDeclaredMethods();
        for (final Method classMethod : classMethods) {
            final Class retClass = classMethod.getReturnType();
            final int length = classMethod.getParameterTypes().length;
            final int modifiers = classMethod.getModifiers();
            if (null == retClass || 0 != length || Modifier.isStatic(modifiers)
                    || !Modifier.isPublic(modifiers) || Modifier.isInterface(modifiers)
                    || Modifier.isAbstract(modifiers)) {
                continue;
            }
            final String methodName = classMethod.getName();
            if (methodName.toUpperCase().startsWith("TEST")
                    || null != classMethod.getAnnotation(Test.class)) {
                testMethods.add(new FrameworkMethod(classMethod));
            }
            if (null != classMethod.getAnnotation(Ignore.class)) {
                testMethods.remove(classMethod);
            }
        }
    }

    @Override
    public Description getDescription() {
        return Description.createSuiteDescription(testClass.getName(),
                testClass.getJavaClass().getAnnotations());
    }

    @Override
    protected List<FrameworkMethod> getChildren() {
        return testMethods;
    }

    @Override
    protected Description describeChild(final FrameworkMethod frameworkMethod) {
        return Description.createTestDescription(testClass.getJavaClass(), frameworkMethod.getName());
    }

    private void testJVMBackendFrameworkMethod(final FrameworkMethod aFrameworkMethod, final RunNotifier aRunNotifier) {
        final Description theDescription = Description.createTestDescription(testClass.getJavaClass(), aFrameworkMethod.getName() + " JVM Target");
        aRunNotifier.fireTestStarted(theDescription);
        try {
            // Simply invoke using reflection
            final Object theInstance = testClass.getJavaClass().getDeclaredConstructor().newInstance();
            final Method theMethod = aFrameworkMethod.getMethod();
            theMethod.invoke(theInstance);

            aRunNotifier.fireTestFinished(theDescription);
        } catch (final Exception e) {
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        }
    }

    private static void initializeSeleniumDriver() throws IOException {
        if (null == DRIVERSERVICE) {

            final String theChromeDriverBinary = System.getenv("CHROMEDRIVER_BINARY");
            if (null == theChromeDriverBinary || theChromeDriverBinary.isEmpty()) {
                throw new RuntimeException("No chromedriver binary found! Please set CHROMEDRIVER_BINARY environment variable!");
            }

            ChromeDriverService.Builder theDriverService = new ChromeDriverService.Builder();
            theDriverService = theDriverService.withVerbose(false);
            theDriverService = theDriverService.usingDriverExecutable(new File(theChromeDriverBinary));

            DRIVERSERVICE = theDriverService.build();
            DRIVERSERVICE.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> DRIVERSERVICE.stop()));
        }
    }

    private WebDriver newDriverForTest() {
        final ChromeOptions theOptions = new ChromeOptions();
        theOptions.addArguments("headless");
        theOptions.addArguments("disable-gpu");

        final LoggingPreferences theLoggingPreferences = new LoggingPreferences();
        theLoggingPreferences.enable(LogType.BROWSER, Level.ALL);
        theOptions.setCapability(CapabilityType.LOGGING_PREFS, theLoggingPreferences);

        final DesiredCapabilities theCapabilities = DesiredCapabilities.chrome();
        theCapabilities.setCapability(ChromeOptions.CAPABILITY, theOptions);

        return new RemoteWebDriver(DRIVERSERVICE.getUrl(), theCapabilities);
    }

    private void testJSBackendFrameworkMethod(final FrameworkMethod aFrameworkMethod, final RunNotifier aRunNotifier) {
        final Description theDescription = Description.createTestDescription(testClass.getJavaClass(), aFrameworkMethod.getName() + " JS Backend ");
        aRunNotifier.fireTestStarted(theDescription);

        WebDriver theDriver = null;

        try {
            final CompileTarget theCompileTarget = new CompileTarget(testClass.getJavaClass().getClassLoader(), CompileTarget.BackendType.js);

            final BytecodeMethodSignature theSignature = theCompileTarget.toMethodSignature(aFrameworkMethod.getMethod());
            final BytecodeMethodSignature theGetLastExceptionSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(
                    Throwable.class), new BytecodeTypeRef[0]);

            final BytecodeObjectTypeRef theTypeRef = new BytecodeObjectTypeRef(testClass.getName());

            final StringWriter theStrWriter = new StringWriter();
            final PrintWriter theCodeWriter = new PrintWriter(theStrWriter);

            final CompileOptions theOptions = new CompileOptions(LOGGER, true, KnownOptimizer.ALL);
            final JSCompileResult result = (JSCompileResult) theCompileTarget.compileToJS(theOptions, testClass.getJavaClass(), aFrameworkMethod.getName(), theSignature);
            final JSCompileResult.JSContent content = result.getContent()[0];
            theCodeWriter.println(content.getData());

            final String theFilename = theCompileTarget.toClassName(theTypeRef) + "." + theCompileTarget.toMethodName(aFrameworkMethod.getName(), theSignature) + "_js.html";

            theCodeWriter.println();
            theCodeWriter.println("bytecoder.imports.system = {");
            theCodeWriter.println("     currentTimeMillis: function() {");
            theCodeWriter.println("         return Date.now();");
            theCodeWriter.println("     },");
            theCodeWriter.println("     nanoTime: function() {");
            theCodeWriter.println("         return Date.now() * 1000000;");
            theCodeWriter.println("     },");
            theCodeWriter.println("     writeByteArrayToConsole: function(thisRef, p1) {");
            theCodeWriter.println("         bytecoder.logByteArrayAsString(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     logDebug: function(p1) {");
            theCodeWriter.println("         bytecoder.logDebug(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     arraycopy: function(src, srcPos, dest, destPos, length) {");
            theCodeWriter.println("         for (i=0;i<length;i++) {");
            theCodeWriter.println("             dest.data[destPos++] = src.data[srcPos++];");
            theCodeWriter.println("         }");
            theCodeWriter.println("     }");
            theCodeWriter.println("};");
            theCodeWriter.println("bytecoder.imports.printstream = {");
            theCodeWriter.println("     logDebug: function(thisref, p1) {");
            theCodeWriter.println("         bytecoder.logDebug(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("};");
            theCodeWriter.println("bytecoder.imports.math = {");
            theCodeWriter.println("     ceil: function(p1) {");
            theCodeWriter.println("         return Math.ceil(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     floor: function(p1) {");
            theCodeWriter.println("         return Math.floor(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     sin: function(p1) {");
            theCodeWriter.println("         return Math.sin(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     cos: function(p1) {");
            theCodeWriter.println("         return Math.cos(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     sqrt: function(p1) {");
            theCodeWriter.println("         return Math.sqrt(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     round: function(p1) {");
            theCodeWriter.println("         return Math.round(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     NaN: function(p1) {");
            theCodeWriter.println("         return NaN;");
            theCodeWriter.println("     },");
            theCodeWriter.println("     atan2: function(p1, p2) {");
            theCodeWriter.println("         return Math.atan2(p1, p2);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     max: function(p1, p2) {");
            theCodeWriter.println("         return Math.max(p1, p2);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     random: function() {");
            theCodeWriter.println("         return Math.random();");
            theCodeWriter.println("     },");
            theCodeWriter.println("     tan: function(p1) {");
            theCodeWriter.println("         return Math.tan(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     toRadians: function(p1) {");
            theCodeWriter.println("         return Math.toRadians(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     toDegrees: function(p1) {");
            theCodeWriter.println("         return Math.toDegrees(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     min: function (p1, p2) {");
            theCodeWriter.println("         return Math.min(p1, p2);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     add: function(p1, p2) {");
            theCodeWriter.println("         return p1 + p2;");
            theCodeWriter.println("     }");
            theCodeWriter.println("};");
            theCodeWriter.println("bytecoder.imports.strictmath = {");
            theCodeWriter.println("     sin: function(p1) {");
            theCodeWriter.println("         return Math.sin(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     cos: function(p1) {");
            theCodeWriter.println("         return Math.cos(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     sqrt: function(p1) {");
            theCodeWriter.println("         return Math.sqrt(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     round: function(p1) {");
            theCodeWriter.println("         return Math.round(p1);");
            theCodeWriter.println("     },");
            theCodeWriter.println("     atan2: function(p1, p2) {");
            theCodeWriter.println("         return Math.atan2(p1, p2);");
            theCodeWriter.println("     },");
            theCodeWriter.println("};");
            theCodeWriter.println();

            theCodeWriter.println("console.log(\"Starting test\");");
            theCodeWriter.println("bytecoder.bootstrap();");
            theCodeWriter.println("var theTestInstance = new " + theCompileTarget.toClassName(theTypeRef) + ".Create();");
            theCodeWriter.println("theTestInstance." + theCompileTarget.toMethodName(aFrameworkMethod.getName(), theSignature) + "(theTestInstance);");
            theCodeWriter.println("var theLastException = " + theCompileTarget.toClassName(BytecodeObjectTypeRef.fromRuntimeClass(
                    ExceptionRethrower.class)) + "." + theCompileTarget.toMethodName("getLastOutcomeOrNullAndReset", theGetLastExceptionSignature) + "();");
            theCodeWriter.println("if (theLastException) {");
            theCodeWriter.println("var theStringData = theLastException.message.data.data;");
            theCodeWriter.println("   var theMessage = \"\";");
            theCodeWriter.println("   for (var i=0;i<theStringData.length;i++) {");
            theCodeWriter.println("     theMessage += String.fromCharCode(theStringData[i]);");
            theCodeWriter.println("   }");
            theCodeWriter.println("   console.log(\"Test finished with exception. Message = \" + theMessage);");
            theCodeWriter.println("  throw theLastException;");
            theCodeWriter.println("}");
            theCodeWriter.println("console.log(\"Test finished OK\");");

            theCodeWriter.flush();

            final File theWorkingDirectory = new File(".");

            initializeSeleniumDriver();

            final File theMavenTargetDir = new File(theWorkingDirectory, "target");
            final File theGeneratedFilesDir = new File(theMavenTargetDir, "bytecoderjs");
            theGeneratedFilesDir.mkdirs();
            final File theGeneratedFile = new File(theGeneratedFilesDir, theFilename);
            final PrintWriter theWriter = new PrintWriter(theGeneratedFile);
            theWriter.println("<html><body><script>");
            theWriter.println(theStrWriter.toString());
            theWriter.println("</script></body></html>");
            theWriter.flush();
            theWriter.close();

            theDriver = newDriverForTest();
            theDriver.get(theGeneratedFile.toURI().toURL().toString());

            final List<LogEntry> theAll = theDriver.manage().logs().get(LogType.BROWSER).getAll();
            if (1 > theAll.size()) {
                aRunNotifier.fireTestFailure(new Failure(theDescription, new RuntimeException("No console output from browser")));
            }
            for (final LogEntry theEntry : theAll) {
                System.out.println(theEntry.getMessage());
            }
            final LogEntry theLast = theAll.get(theAll.size() - 1);

            if (!theLast.getMessage().contains("Test finished OK")) {
                aRunNotifier.fireTestFailure(new Failure(theDescription, new RuntimeException("Test did not succeed! Got : " + theLast.getMessage())));
            }

        } catch (final ControlFlowProcessingException e) {
            System.out.println(e.getGraph().toDOT());
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        } catch (final Exception e) {
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        } finally {
            if (null != theDriver) {
                theDriver.close();
            }
            aRunNotifier.fireTestFinished(theDescription);
        }
    }

    private void testWASMBackendFrameworkMethod(final FrameworkMethod aFrameworkMethod, final RunNotifier aRunNotifier) {
        final Description theDescription = Description.createTestDescription(testClass.getJavaClass(), aFrameworkMethod.getName() + " WASM Backend ");
        aRunNotifier.fireTestStarted(theDescription);

        WebDriver theDriver = null;

        try {
            final CompileTarget theCompileTarget = new CompileTarget(testClass.getJavaClass().getClassLoader(), CompileTarget.BackendType.wasm);

            final BytecodeMethodSignature theSignature = theCompileTarget.toMethodSignature(aFrameworkMethod.getMethod());
            final BytecodeObjectTypeRef theTypeRef = new BytecodeObjectTypeRef(testClass.getName());

            final CompileOptions theOptions = new CompileOptions(LOGGER, true, KnownOptimizer.ALL);
            final WASMCompileResult theResult = (WASMCompileResult) theCompileTarget.compileToJS(theOptions, testClass.getJavaClass(), aFrameworkMethod.getName(), theSignature);
            final WASMCompileResult.WASMCompileContent content = theResult.getContent()[0];

            final String theFileName = theCompileTarget.toClassName(theTypeRef) + "." + theCompileTarget.toMethodName(aFrameworkMethod.getName(), theSignature) + ".html";

            final File theWorkingDirectory = new File(".");

            initializeSeleniumDriver();

            final File theMavenTargetDir = new File(theWorkingDirectory, "target");
            final File theGeneratedFilesDir = new File(theMavenTargetDir, "bytecoderwat");
            theGeneratedFilesDir.mkdirs();
            final File theGeneratedFile = new File(theGeneratedFilesDir, theFileName);

            // Copy WABT Tools
            final File theWABTFile = new File(theGeneratedFilesDir, "libwabt.js");
            try (final FileOutputStream theOS = new FileOutputStream(theWABTFile)) {
                IOUtils.copy(getClass().getResourceAsStream("/libwabt.js"), theOS);
            }

            final PrintWriter theWriter = new PrintWriter(theGeneratedFile);
            theWriter.println("<html>");
            theWriter.println("    <body>");
            theWriter.println("        <h1>Module code</h1>");
            theWriter.println("        <pre id=\"modulecode\">");
            theWriter.println(content.getData());
            theWriter.println("        </pre>");
            theWriter.println("        <h1>Compilation result</h1>");
            theWriter.println("        <pre id=\"compileresult\">");
            theWriter.println("        </pre>");
            theWriter.println("        <script src=\"libwabt.js\">");
            theWriter.println("        </script>");
            theWriter.println("        <script>");
            theWriter.println("            var runningInstance;");
            theWriter.println("            var runningInstanceMemory;");
            theWriter.println();

            theWriter.println("            function bytecoder_IntInMemory(value) {");
            theWriter.println("             return runningInstanceMemory[value]");
            theWriter.println("                 + (runningInstanceMemory[value + 1] * 256)");
            theWriter.println("                 + (runningInstanceMemory[value + 2] * 256 * 256)");
            theWriter.println("                 + (runningInstanceMemory[value + 3] * 256 * 256 * 256);");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function bytecoder_logByteArrayAsString(acaller, value) {");
            theWriter.println("                 var theLength = bytecoder_IntInMemory(value + 16);");
            theWriter.println("                 var theData = '';");
            theWriter.println("                 value = value + 20;");
            theWriter.println("                 for (var i=0;i<theLength;i++) {");
            theWriter.println("                     var theCharCode = bytecoder_IntInMemory(value);");
            theWriter.println("                     value = value + 4;");
            theWriter.println("                     theData+= String.fromCharCode(theCharCode);");
            theWriter.println("                 }");
            theWriter.println("                 console.log(theData);");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function bytecoder_logDebug(caller,value) {");
            theWriter.println("                 console.log(value);");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function compile() {");
            theWriter.println("                console.log('Test started');");
            theWriter.println("                try {");
            theWriter.println("                    var module = wabt.parseWat('test.wast', document.getElementById(\"modulecode\").innerText);");
            theWriter.println("                    module.resolveNames();");
            theWriter.println("                    module.validate();");
            theWriter.println("                    var binaryOutput = module.toBinary({log: true, write_debug_names:true});");
            theWriter.println("                    document.getElementById(\"compileresult\").innerText = binaryOutput.log;");
            theWriter.println("                    var binaryBuffer = binaryOutput.buffer;");
            theWriter.println("                    console.log('Size of compiled WASM binary is ' + binaryBuffer.length);");
            theWriter.println();
            theWriter.println("                    var theInstantiatePromise = WebAssembly.instantiate(binaryBuffer, {");
            theWriter.println("                         system: {");
            theWriter.println("                             currentTimeMillis: function() {return Date.now();},");
            theWriter.println("                             nanoTime: function() {return Date.now() * 1000000;},");
            theWriter.println("                             logDebug: bytecoder_logDebug,");
            theWriter.println("                             writeByteArrayToConsole: bytecoder_logByteArrayAsString,");
            theWriter.println("                         },");
            theWriter.println("                         printstream: {");
            theWriter.println("                             logDebug: bytecoder_logDebug,");
            theWriter.println("                         },");
            theWriter.println("                         math: {");
            theWriter.println("                             floor: function (thisref, p1) {return Math.floor(p1);},");
            theWriter.println("                             ceil: function (thisref, p1) {return Math.ceil(p1);},");
            theWriter.println("                             sin: function (thisref, p1) {return Math.sin(p1);},");
            theWriter.println("                             cos: function  (thisref, p1) {return Math.cos(p1);},");
            theWriter.println("                             round: function  (thisref, p1) {return Math.round(p1);},");
            theWriter.println("                             float_rem: function(a, b) {return a % b;},");
            theWriter.println("                             sqrt: function(thisref, p1) {return Math.sqrt(p1);},");
            theWriter.println("                             add: function(thisref, p1, p2) {return p1 + p2;},");
            theWriter.println("                             float_rem: function(a, b) {return a % b;},");
            theWriter.println("                             max: function(p1, p2) { return Math.max(p1, p2);},");
            theWriter.println("                             min: function(p1, p2) { return Math.min(p1, p2);},");
            theWriter.println("                         },");
            theWriter.println("                         strictmath: {");
            theWriter.println("                             floor: function (thisref, p1) {return Math.floor(p1);},");
            theWriter.println("                             ceil: function (thisref, p1) {return Math.ceil(p1);},");
            theWriter.println("                             sin: function (thisref, p1) {return Math.sin(p1);},");
            theWriter.println("                             cos: function  (thisref, p1) {return Math.cos(p1);},");
            theWriter.println("                             round: function  (thisref, p1) {return Math.round(p1);},");
            theWriter.println("                             float_rem: function(a, b) {return a % b;},");
            theWriter.println("                             sqrt: function(thisref, p1) {return Math.sqrt(p1);},");
            theWriter.println("                             add: function(thisref, p1, p2) {return p1 + p2;},");
            theWriter.println("                         },");
            theWriter.println("                         profiler: {");
            theWriter.println("                             logMemoryLayoutBlock: function(aCaller, aStart, aUsed, aNext) {");
            theWriter.println("                                 if (aUsed == 1) return;");
            theWriter.println("                                 console.log('   Block at ' + aStart + ' status is ' + aUsed + ' points to ' + aNext);");
            theWriter.println("                                 console.log('      Block size is ' + bytecoder_IntInMemory(aStart));");
            theWriter.println("                                 console.log('      Object type ' + bytecoder_IntInMemory(aStart + 12));");
            theWriter.println("                             }");
            theWriter.println("                         }");
            theWriter.println("                    });");
            theWriter.println("                    theInstantiatePromise.then(");
            theWriter.println("                         function (resolved) {");
            theWriter.println("                             var wasmModule = resolved.module;");
            theWriter.println("                             runningInstance = resolved.instance;");
            theWriter.println("                             runningInstanceMemory = new Uint8Array(runningInstance.exports.memory.buffer);");
            theWriter.println("                             runningInstance.exports.initMemory(0);");
            theWriter.println("                             console.log(\"Memory initialized\")");
            theWriter.println("                             runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                             console.log(\"Used memory in bytes \" + runningInstance.exports.usedMem());");
            theWriter.println("                             console.log(\"Free memory in bytes \" + runningInstance.exports.freeMem());");
            theWriter.println("                             runningInstance.exports.bootstrap(0);");
            theWriter.println("                             console.log(\"Used memory after bootstrap in bytes \" + runningInstance.exports.usedMem());");
            theWriter.println("                             console.log(\"Free memory after bootstrap in bytes \" + runningInstance.exports.freeMem());");
            theWriter.println("                             runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                             console.log(\"Creating test instance\")");

            theWriter.print("                             var theTest = runningInstance.exports.newObject(0,");
            theWriter.print(content.getSizeOf(theTypeRef));
            theWriter.print(",");
            theWriter.print(content.getTypeIDFor(theTypeRef));
            theWriter.print(",");
            theWriter.print(content.getVTableIndexOf(theTypeRef));
            theWriter.println(", 0);");
            theWriter.println("                             runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                             console.log(\"Bootstrapped\")");
            theWriter.println("                             try {");
            theWriter.println("                                 runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                                 console.log(\"Starting main method\")");
            theWriter.println("                                 runningInstance.exports.main(theTest);");
            theWriter.println("                                 console.log(\"Main finished\")");
            theWriter.println("                                 runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                                 wasmHexDump(runningInstanceMemory);");
            theWriter.println("                                 console.log(\"Test finished OK\")");
            theWriter.println("                             } catch (e) {");
            theWriter.println("                                 console.log(\"Test threw error\")");
            theWriter.println("                                 runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                                 wasmHexDump(runningInstanceMemory);");
            theWriter.println("                                 throw e;");
            theWriter.println("                             }");
            theWriter.println("                         },");
            theWriter.println("                         function (rejected) {");
            theWriter.println("                             console.log(\"Error instantiating webassembly\");");
            theWriter.println("                             console.log(rejected);");
            theWriter.println("                         }");
            theWriter.println("                    );");
            theWriter.println("                } catch (e) {");
            theWriter.println("                    document.getElementById(\"compileresult\").innerText = e.toString();");
            theWriter.println("                    console.log(e.toString());");
            theWriter.println("                    console.log(e.stack);");
            theWriter.println("                    if (runningInstance) {");
            theWriter.println("                         runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                         wasmHexDump(runningInstanceMemory);");
            theWriter.println("                    }");
            theWriter.println("                }");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function wasmHexDump(memory) {");
            theWriter.println("                var theStart = 0;");
            theWriter.println("                console.log('HEX DUMP');");
            theWriter.println("                console.log('=================================================================================');");
            theWriter.println("                for (var i=0;i<200;i++) {");
            theWriter.println("                    var theLine = '' + theStart;");
            theWriter.println("                    while(theLine.length < 15) {");
            theWriter.println("                        theLine+= ' ';");
            theWriter.println("                    }");
            theWriter.println("                    theLine+= ' : ';");
            theWriter.println("                    for (var j=0;j<32;j++) {");
            theWriter.println("                        var theByte = memory[theStart++];");
            theWriter.println("                        var theData = '' + theByte;");
            theWriter.println("                        while(theData.length < 3) {");
            theWriter.println("                            theData = ' ' + theData;");
            theWriter.println("                        }");
            theWriter.println("                        theLine += theData;");
            theWriter.println("                        theLine += ' ';");
            theWriter.println("                    }");
            theWriter.println("                    console.log(theLine);");
            theWriter.println("                }");
            theWriter.println("                console.log('DONE');");
            theWriter.println("            }");
            theWriter.println();
            theWriter.println("            compile();");
            theWriter.println("        </script>");
            theWriter.println("    </body>");
            theWriter.println("</html>");

            theWriter.flush();
            theWriter.close();

            try  (final PrintWriter theWATWriter = new PrintWriter(new FileWriter(new File(theGeneratedFilesDir, theCompileTarget.toClassName(theTypeRef) + "." + theCompileTarget.toMethodName(aFrameworkMethod.getName(), theSignature) + ".wat")))) {
                theWATWriter.println(content.getData());
            }

            // Invoke test in browser
            theDriver = newDriverForTest();
            theDriver.get(theGeneratedFile.toURI().toURL().toString());

            final long theStart = System.currentTimeMillis();
            boolean theTestSuccedded = false;

            while (!theTestSuccedded && 10 * 1000 > System.currentTimeMillis() - theStart) {
                final List<LogEntry> theAll = theDriver.manage().logs().get(LogType.BROWSER).getAll();
                for (final LogEntry theEntry : theAll) {
                    final String theMessage = theEntry.getMessage();
                    System.out.println(theMessage);

                    if (theMessage.contains("Test finished OK")) {
                        theTestSuccedded = true;
                    }
                }

                if (!theTestSuccedded) {
                    Thread.sleep(100);
                }
            }

            if (!theTestSuccedded) {
                aRunNotifier.fireTestFailure(new Failure(theDescription, new RuntimeException("Test did not succeed!")));
            }

        } catch (final ControlFlowProcessingException e) {
            System.out.println(e.getGraph().toDOT());
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        } catch (final Exception e) {
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        } finally {
            if (null != theDriver) {
                theDriver.close();
            }
            aRunNotifier.fireTestFinished(theDescription);
        }
    }

    private void testWASMASTBackendFrameworkMethod(final FrameworkMethod aFrameworkMethod, final RunNotifier aRunNotifier) {
        final Description theDescription = Description.createTestDescription(testClass.getJavaClass(), aFrameworkMethod.getName() + " WASM AST Backend ");
        aRunNotifier.fireTestStarted(theDescription);

        WebDriver theDriver = null;

        try {
            final CompileTarget theCompileTarget = new CompileTarget(testClass.getJavaClass().getClassLoader(), CompileTarget.BackendType.wasm_ast);

            final BytecodeMethodSignature theSignature = theCompileTarget.toMethodSignature(aFrameworkMethod.getMethod());
            final BytecodeObjectTypeRef theTypeRef = new BytecodeObjectTypeRef(testClass.getName());

            final CompileOptions theOptions = new CompileOptions(LOGGER, true, KnownOptimizer.ALL);
            final WASMCompileResult theResult = (WASMCompileResult) theCompileTarget.compileToJS(theOptions, testClass.getJavaClass(), aFrameworkMethod.getName(), theSignature);
            final WASMCompileResult.WASMCompileContent content = theResult.getContent()[0];

            final String theFileName = theCompileTarget.toClassName(theTypeRef) + "." + theCompileTarget.toMethodName(aFrameworkMethod.getName(), theSignature) + "_ast.html";

            final File theWorkingDirectory = new File(".");

            initializeSeleniumDriver();

            final File theMavenTargetDir = new File(theWorkingDirectory, "target");
            final File theGeneratedFilesDir = new File(theMavenTargetDir, "bytecoderwat");
            theGeneratedFilesDir.mkdirs();
            final File theGeneratedFile = new File(theGeneratedFilesDir, theFileName);

            // Copy WABT Tools
            final File theWABTFile = new File(theGeneratedFilesDir, "libwabt.js");
            try (final FileOutputStream theOS = new FileOutputStream(theWABTFile)) {
                IOUtils.copy(getClass().getResourceAsStream("/libwabt.js"), theOS);
            }

            final PrintWriter theWriter = new PrintWriter(theGeneratedFile);
            theWriter.println("<html>");
            theWriter.println("    <body>");
            theWriter.println("        <h1>Module code</h1>");
            theWriter.println("        <pre id=\"modulecode\">");
            theWriter.println(content.getData());
            theWriter.println("        </pre>");
            theWriter.println("        <h1>Compilation result</h1>");
            theWriter.println("        <pre id=\"compileresult\">");
            theWriter.println("        </pre>");
            theWriter.println("        <script src=\"libwabt.js\">");
            theWriter.println("        </script>");
            theWriter.println("        <script>");
            theWriter.println("            var runningInstance;");
            theWriter.println("            var runningInstanceMemory;");
            theWriter.println();

            theWriter.println("            function bytecoder_IntInMemory(value) {");
            theWriter.println("             return runningInstanceMemory[value]");
            theWriter.println("                 + (runningInstanceMemory[value + 1] * 256)");
            theWriter.println("                 + (runningInstanceMemory[value + 2] * 256 * 256)");
            theWriter.println("                 + (runningInstanceMemory[value + 3] * 256 * 256 * 256);");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function bytecoder_logByteArrayAsString(acaller, value) {");
            theWriter.println("                 var theLength = bytecoder_IntInMemory(value + 16);");
            theWriter.println("                 var theData = '';");
            theWriter.println("                 value = value + 20;");
            theWriter.println("                 for (var i=0;i<theLength;i++) {");
            theWriter.println("                     var theCharCode = bytecoder_IntInMemory(value);");
            theWriter.println("                     value = value + 4;");
            theWriter.println("                     theData+= String.fromCharCode(theCharCode);");
            theWriter.println("                 }");
            theWriter.println("                 console.log(theData);");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function bytecoder_logDebug(caller,value) {");
            theWriter.println("                 console.log(value);");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function compile() {");
            theWriter.println("                console.log('Test started');");
            theWriter.println("                try {");
            theWriter.println("                    var module = wabt.parseWat('test.wast', document.getElementById(\"modulecode\").innerText);");
            theWriter.println("                    module.resolveNames();");
            theWriter.println("                    module.validate();");
            theWriter.println("                    var binaryOutput = module.toBinary({log: true, write_debug_names:true});");
            theWriter.println("                    document.getElementById(\"compileresult\").innerText = binaryOutput.log;");
            theWriter.println("                    var binaryBuffer = binaryOutput.buffer;");
            theWriter.println("                    console.log('Size of compiled WASM binary is ' + binaryBuffer.length);");
            theWriter.println();
            theWriter.println("                    var theInstantiatePromise = WebAssembly.instantiate(binaryBuffer, {");
            theWriter.println("                         system: {");
            theWriter.println("                             currentTimeMillis: function() {return Date.now();},");
            theWriter.println("                             nanoTime: function() {return Date.now() * 1000000;},");
            theWriter.println("                             logDebug: bytecoder_logDebug,");
            theWriter.println("                             writeByteArrayToConsole: bytecoder_logByteArrayAsString,");
            theWriter.println("                         },");
            theWriter.println("                         printstream: {");
            theWriter.println("                             logDebug: bytecoder_logDebug,");
            theWriter.println("                         },");
            theWriter.println("                         math: {");
            theWriter.println("                             floor: function (thisref, p1) {return Math.floor(p1);},");
            theWriter.println("                             ceil: function (thisref, p1) {return Math.ceil(p1);},");
            theWriter.println("                             sin: function (thisref, p1) {return Math.sin(p1);},");
            theWriter.println("                             cos: function  (thisref, p1) {return Math.cos(p1);},");
            theWriter.println("                             round: function  (thisref, p1) {return Math.round(p1);},");
            theWriter.println("                             float_rem: function(a, b) {return a % b;},");
            theWriter.println("                             sqrt: function(thisref, p1) {return Math.sqrt(p1);},");
            theWriter.println("                             add: function(thisref, p1, p2) {return p1 + p2;},");
            theWriter.println("                             float_rem: function(a, b) {return a % b;},");
            theWriter.println("                             max: function(p1, p2) { return Math.max(p1, p2);},");
            theWriter.println("                             min: function(p1, p2) { return Math.min(p1, p2);},");
            theWriter.println("                         },");
            theWriter.println("                         strictmath: {");
            theWriter.println("                             floor: function (thisref, p1) {return Math.floor(p1);},");
            theWriter.println("                             ceil: function (thisref, p1) {return Math.ceil(p1);},");
            theWriter.println("                             sin: function (thisref, p1) {return Math.sin(p1);},");
            theWriter.println("                             cos: function  (thisref, p1) {return Math.cos(p1);},");
            theWriter.println("                             round: function  (thisref, p1) {return Math.round(p1);},");
            theWriter.println("                             float_rem: function(a, b) {return a % b;},");
            theWriter.println("                             sqrt: function(thisref, p1) {return Math.sqrt(p1);},");
            theWriter.println("                             add: function(thisref, p1, p2) {return p1 + p2;},");
            theWriter.println("                         },");
            theWriter.println("                         profiler: {");
            theWriter.println("                             logMemoryLayoutBlock: function(aCaller, aStart, aUsed, aNext) {");
            theWriter.println("                                 if (aUsed == 1) return;");
            theWriter.println("                                 console.log('   Block at ' + aStart + ' status is ' + aUsed + ' points to ' + aNext);");
            theWriter.println("                                 console.log('      Block size is ' + bytecoder_IntInMemory(aStart));");
            theWriter.println("                                 console.log('      Object type ' + bytecoder_IntInMemory(aStart + 12));");
            theWriter.println("                             }");
            theWriter.println("                         }");
            theWriter.println("                    });");
            theWriter.println("                    theInstantiatePromise.then(");
            theWriter.println("                         function (resolved) {");
            theWriter.println("                             var wasmModule = resolved.module;");
            theWriter.println("                             runningInstance = resolved.instance;");
            theWriter.println("                             runningInstanceMemory = new Uint8Array(runningInstance.exports.memory.buffer);");
            theWriter.println("                             runningInstance.exports.initMemory(0);");
            theWriter.println("                             console.log(\"Memory initialized\")");
            theWriter.println("                             runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                             console.log(\"Used memory in bytes \" + runningInstance.exports.usedMem());");
            theWriter.println("                             console.log(\"Free memory in bytes \" + runningInstance.exports.freeMem());");
            theWriter.println("                             runningInstance.exports.bootstrap(0);");
            theWriter.println("                             console.log(\"Used memory after bootstrap in bytes \" + runningInstance.exports.usedMem());");
            theWriter.println("                             console.log(\"Free memory after bootstrap in bytes \" + runningInstance.exports.freeMem());");
            theWriter.println("                             runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                             console.log(\"Creating test instance\")");

            theWriter.print("                             var theTest = runningInstance.exports.newObject(0,");
            theWriter.print(content.getSizeOf(theTypeRef));
            theWriter.print(",");
            theWriter.print(content.getTypeIDFor(theTypeRef));
            theWriter.print(",");
            theWriter.print(content.getVTableIndexOf(theTypeRef));
            theWriter.println(", 0);");
            theWriter.println("                             runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                             console.log(\"Bootstrapped\")");
            theWriter.println("                             try {");
            theWriter.println("                                 runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                                 console.log(\"Starting main method\")");
            theWriter.println("                                 runningInstance.exports.main(theTest);");
            theWriter.println("                                 console.log(\"Main finished\")");
            theWriter.println("                                 runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                                 wasmHexDump(runningInstanceMemory);");
            theWriter.println("                                 console.log(\"Test finished OK\")");
            theWriter.println("                             } catch (e) {");
            theWriter.println("                                 console.log(\"Test threw error\")");
            theWriter.println("                                 runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                                 wasmHexDump(runningInstanceMemory);");
            theWriter.println("                                 throw e;");
            theWriter.println("                             }");
            theWriter.println("                         },");
            theWriter.println("                         function (rejected) {");
            theWriter.println("                             console.log(\"Error instantiating webassembly\");");
            theWriter.println("                             console.log(rejected);");
            theWriter.println("                         }");
            theWriter.println("                    );");
            theWriter.println("                } catch (e) {");
            theWriter.println("                    document.getElementById(\"compileresult\").innerText = e.toString();");
            theWriter.println("                    console.log(e.toString());");
            theWriter.println("                    console.log(e.stack);");
            theWriter.println("                    if (runningInstance) {");
            theWriter.println("                         runningInstance.exports.logMemoryLayout(0);");
            theWriter.println("                         wasmHexDump(runningInstanceMemory);");
            theWriter.println("                    }");
            theWriter.println("                }");
            theWriter.println("            }");
            theWriter.println();

            theWriter.println("            function wasmHexDump(memory) {");
            theWriter.println("                var theStart = 0;");
            theWriter.println("                console.log('HEX DUMP');");
            theWriter.println("                console.log('=================================================================================');");
            theWriter.println("                for (var i=0;i<200;i++) {");
            theWriter.println("                    var theLine = '' + theStart;");
            theWriter.println("                    while(theLine.length < 15) {");
            theWriter.println("                        theLine+= ' ';");
            theWriter.println("                    }");
            theWriter.println("                    theLine+= ' : ';");
            theWriter.println("                    for (var j=0;j<32;j++) {");
            theWriter.println("                        var theByte = memory[theStart++];");
            theWriter.println("                        var theData = '' + theByte;");
            theWriter.println("                        while(theData.length < 3) {");
            theWriter.println("                            theData = ' ' + theData;");
            theWriter.println("                        }");
            theWriter.println("                        theLine += theData;");
            theWriter.println("                        theLine += ' ';");
            theWriter.println("                    }");
            theWriter.println("                    console.log(theLine);");
            theWriter.println("                }");
            theWriter.println("                console.log('DONE');");
            theWriter.println("            }");
            theWriter.println();
            theWriter.println("            compile();");
            theWriter.println("        </script>");
            theWriter.println("    </body>");
            theWriter.println("</html>");

            theWriter.flush();
            theWriter.close();

            try  (final PrintWriter theWATWriter = new PrintWriter(new FileWriter(new File(theGeneratedFilesDir, theCompileTarget.toClassName(theTypeRef) + "." + theCompileTarget.toMethodName(aFrameworkMethod.getName(), theSignature) + "_ast.wat")))) {
                theWATWriter.println(content.getData());
            }

            // Invoke test in browser
            theDriver = newDriverForTest();
            theDriver.get(theGeneratedFile.toURI().toURL().toString());

            final long theStart = System.currentTimeMillis();
            boolean theTestSuccedded = false;

            while (!theTestSuccedded && 10 * 1000 > System.currentTimeMillis() - theStart) {
                final List<LogEntry> theAll = theDriver.manage().logs().get(LogType.BROWSER).getAll();
                for (final LogEntry theEntry : theAll) {
                    final String theMessage = theEntry.getMessage();
                    System.out.println(theMessage);

                    if (theMessage.contains("Test finished OK")) {
                        theTestSuccedded = true;
                    }
                }

                if (!theTestSuccedded) {
                    Thread.sleep(100);
                }
            }

            if (!theTestSuccedded) {
                aRunNotifier.fireTestFailure(new Failure(theDescription, new RuntimeException("Test did not succeed!")));
            }

        } catch (final ControlFlowProcessingException e) {
            System.out.println(e.getGraph().toDOT());
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        } catch (final Exception e) {
            aRunNotifier.fireTestFailure(new Failure(theDescription, e));
        } finally {
            if (null != theDriver) {
                theDriver.close();
            }
            aRunNotifier.fireTestFinished(theDescription);
        }
    }


    @Override
    protected void runChild(final FrameworkMethod aFrameworkMethod, final RunNotifier aRunNotifier) {
        if (null != getDescription().getAnnotation(WASMOnly.class)) {
            testWASMBackendFrameworkMethod(aFrameworkMethod, aRunNotifier);
        } else {
            testJVMBackendFrameworkMethod(aFrameworkMethod, aRunNotifier);
            testJSBackendFrameworkMethod(aFrameworkMethod, aRunNotifier);
            testWASMBackendFrameworkMethod(aFrameworkMethod, aRunNotifier);
            testWASMASTBackendFrameworkMethod(aFrameworkMethod, aRunNotifier);
        }
    }
}