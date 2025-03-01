/*
 * Copyright (c) 2017, 2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2014, 2015, Andrey Rodchenko. All rights reserved.
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.sun.max.vm.run.java;

import com.sun.max.annotate.ALIAS;
import com.sun.max.annotate.HOSTED_ONLY;
import com.sun.max.program.ProgramError;
import com.sun.max.program.Trace;
import com.sun.max.vm.*;
import com.sun.max.vm.MaxineVM.Phase;
import com.sun.max.vm.actor.holder.ClassActor;
import com.sun.max.vm.actor.member.MethodActor;
import com.sun.max.vm.actor.member.StaticMethodActor;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.deopt.Deoptimization;
import com.sun.max.vm.heap.Heap;
import com.sun.max.vm.hosted.CompiledPrototype;
import com.sun.max.vm.instrument.InstrumentationManager;
import com.sun.max.vm.jdk.JDK_sun_launcher_LauncherHelper;
import com.sun.max.vm.jni.JniFunctions;
import com.sun.max.vm.log.VMLog;
import com.sun.max.vm.profilers.allocation.AllocationProfiler;
import com.sun.max.vm.profilers.allocation.ProfilerGCCallback;
import com.sun.max.vm.profilers.sampling.*;
import com.sun.max.vm.run.RunScheme;
import com.sun.max.vm.runtime.CriticalMethod;
import com.sun.max.vm.runtime.FatalError;
import com.sun.max.vm.runtime.PrintThreads;
import com.sun.max.vm.thread.VmThread;
import com.sun.max.vm.ti.VMTI;
import com.sun.max.vm.type.SignatureDescriptor;
import com.sun.max.vm.type.VMClassLoader;
import sun.misc.Launcher;
import sun.misc.Signal;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static com.sun.max.vm.MaxineVM.vm;
import static com.sun.max.vm.VMConfiguration.vmConfig;
import static com.sun.max.vm.VMOptions.register;
import static com.sun.max.vm.type.ClassRegistry.BOOT_CLASS_REGISTRY;

/**
 * The normal Java run scheme that starts up the standard JDK services, loads a user
 * class that has been specified on the command line, finds its main method, and
 * runs it with the specified arguments on the command line. This run scheme
 * is intended to provide the same usage as the standard "java" command in
 * a standard JRE.
 *
 * This class incorporates a lot of nasty, delicate JDK hacks that are needed to
 * get the JDK reinitialized to the point that it is ready to run a new program.
 */
public class JavaRunScheme extends AbstractVMScheme implements RunScheme {

    private static final VMOption versionOption = register(new VMOption(
        "-version", "print product version and exit"), MaxineVM.Phase.STARTING);
    private static final VMOption showVersionOption = register(new VMOption(
        "-showversion", "print product version and continue"), MaxineVM.Phase.STARTING);
    private static final VMOption D64Option = register(new VMOption("-d64",
        "Selects the 64-bit data model if available. Currently ignored."), MaxineVM.Phase.PRISTINE);
    private static final JavaAgentVMOption javaagentOption = register(new JavaAgentVMOption(), MaxineVM.Phase.STARTING);
    private static final VMExtensionVMOption vmExtensionOption = register(new VMExtensionVMOption(), MaxineVM.Phase.STARTING);
    private static final VMStringOption cprofOption = register(new VMStringOption(
        "-Xprof", false, null, "run CPU sampling profiler"), MaxineVM.Phase.STARTING);
    private static final VMStringOption hprofOption = register(new VMStringOption(
        "-Xhprof", false, null, "run heap sampling profiler"), MaxineVM.Phase.STARTING);
    private static final VMStringOption showSettingsOption = register(new VMStringOption(
        "-XshowSettings", false, ":all",
        "show all settings and continue (optionally limit to vm, properties or locale settings appending :vm, :properties and :locale respectively)"),
        MaxineVM.Phase.STARTING);

    /**
     * List of classes to explicitly reinitialise in the {@link Phase#STARTING} phase.
     * This supports extensions to the boot image.
     */
    private static List<String> reinitClasses = new LinkedList<String>();
    private static CPUSamplingProfiler cpuSamplingProfiler;
    private static HeapSamplingProfiler heapSamplingProfiler;
    private static String mainClassName;

    @HOSTED_ONLY
    public JavaRunScheme() {
    }

    /**
     * JDK methods that need to be re-executed at startup, e.g. to re-register native methods.
     */
    private StaticMethodActor[] initIDMethods;

    @HOSTED_ONLY
    public static void registerClassForReInit(String className) {
        CompiledPrototype.registerVMEntryPoint(className + ".<clinit>");
        MaxineVM.registerKeepClassInit(className);
        Trace.line(2, "registering "  +  className + " for reinitialization");
        reinitClasses.add(className);
    }

    /**
     * While bootstrapping, searches the boot class registry classes that have methods called "initIDs" with
     * signature "()V". Such methods are typically used in the JDK to initialize JNI identifiers for native code, and
     * need to be re-executed upon startup.
     */
    @HOSTED_ONLY
    public List< ? extends MethodActor> gatherNativeInitializationMethods() {
        final List<StaticMethodActor> methods = new LinkedList<StaticMethodActor>();
        for (ClassActor classActor : BOOT_CLASS_REGISTRY.bootImageClasses()) {
            for (StaticMethodActor method : classActor.localStaticMethodActors()) {
                if (method.isNativeInitialization()) {
                    method.makeInvocationStub();
                    methods.add(method);
                }
            }
        }
        initIDMethods = methods.toArray(new StaticMethodActor[methods.size()]);
        return methods;
    }

    /**
     * Runs all the native initializer methods gathered while bootstrapping.
     */
    public void runNativeInitializationMethods() {
        final List<StaticMethodActor> methods = new LinkedList<StaticMethodActor>();
        for (StaticMethodActor method : initIDMethods) {
            try {
                if (method.currentTargetMethod() == null) {
                    FatalError.unexpected("Native initialization method must be compiled in boot image: " + method);
                }
                method.invoke();
            } catch (UnsatisfiedLinkError unsatisfiedLinkError) {
                // Library not present yet - try again next time:
                methods.add(method);
            } catch (InvocationTargetException invocationTargetException) {
                if (invocationTargetException.getTargetException() instanceof UnsatisfiedLinkError) {
                   // Library not present yet - try again next time:
                    methods.add(method);
                } else {
                    throw ProgramError.unexpected(invocationTargetException.getTargetException());
                }
            } catch (Throwable throwable) {
                throw ProgramError.unexpected(throwable);
            }
        }
        initIDMethods = methods.toArray(new StaticMethodActor[methods.size()]);
    }

    public static boolean isHeapProfilingOptionPassed() {
        return hprofOption.getValue() != null;
    }

    public static void terminateProfilers() {
        if (cpuSamplingProfiler != null) {
            cpuSamplingProfiler.terminate();
        }
        if (heapSamplingProfiler != null) {
            heapSamplingProfiler.terminate();
        }
        if (MaxineVM.allocationProfiler != null) {
            MaxineVM.allocationProfiler.terminate();
        }
    }

    public static void restartProfilers() {
        if (cpuSamplingProfiler != null) {
            cpuSamplingProfiler.restart();
        }
        if (heapSamplingProfiler != null) {
            heapSamplingProfiler.restart();
        }
        // TODO: restart the allocation profiler as well, and dump its findings
    }

    @ALIAS(declaringClass = System.class)
    public static native void initializeSystemClass();

    /**
     * The initialization method of the Java run scheme runs at both bootstrapping and startup.
     * While bootstrapping, it gathers the methods needed for native initialization, and at startup
     * it initializes basic VM services.
     */
    @Override
    public void initialize(MaxineVM.Phase phase) {
        switch (phase) {
            case BOOTSTRAPPING: {
                if (MaxineVM.isHosted()) {
                    ProfilerGCCallback.init();
                    // Make sure MaxineVM.exit is available when running the JavaRunScheme.
                    new CriticalMethod(MaxineVM.class, "exit",
                                    SignatureDescriptor.create(void.class, int.class, boolean.class));
                }
                break;
            }
            case STARTING: {

                // This hack enables (platform-dependent) tracing before the eventual System properties are set:
                System.setProperty("line.separator", "\n");

                // Normally, we would have to initialize tracing this late,
                // because 'PrintWriter.<init>()' relies on a system property ("line.separator"), which is accessed during 'initializeSystemClass()'.
                initializeSystemClass();

                // reinitialise any registered classes
                for (String className : reinitClasses) {
                    try {
                        final ClassActor classActor = ClassActor.fromJava(Class.forName(className));
                        classActor.callInitializer();
                    } catch (Exception e) {
                        FatalError.unexpected("Error re-initializing " + className, e);
                    }
                }
                break;
            }

            case RUNNING: {
                // This is always the last scheme to be initialized, so now is the right time
                // to start the profiler if requested.
                final String cpuProfOptionValue = cprofOption.getValue();
                if (cpuProfOptionValue != null) {
                    final String cpuProfOptionPrefix = cprofOption.toString();
                    cpuSamplingProfiler = new CPUSamplingProfiler(cpuProfOptionPrefix, cpuProfOptionValue);
                }
                final String heapProfOptionValue = hprofOption.getValue();
                if (heapProfOptionValue != null) {
                    final String heapProfOptionPrefix = hprofOption.toString();
                    heapSamplingProfiler = new HeapSamplingProfiler(heapProfOptionPrefix, heapProfOptionValue);
                }
                // The same for the Allocation Profiler
                if (CompilationBroker.AllocationProfilerEntryPoint != null || AllocationProfiler.profileAll()) {
                    float beforeAllocProfiler = (float) Heap.reportUsedSpace() / (1024 * 1024);
                    // Initialize Allocation Profiler
                    MaxineVM.allocationProfiler = new AllocationProfiler();
                    float afterAllocProfiler = (float) Heap.reportUsedSpace() / (1024 * 1024);

                    if (AllocationProfiler.AllocationProfilerDebug) {
                        Log.println("*===================================================*\n" +
                            "* Allocation Profiler is on validation mode.\n" +
                            "*===================================================*\n" +
                            "* You can use Allocation Profiler with confidence if:\n" +
                            "* => a) VM Reported Heap Used Space = Initial Used Heap Space + Allocation Profiler Size + New Objects Size\n" +
                            "* => b) VM Reported Heap Used Space after GC = Initial Used Heap Space + Allocation Profiler Size + Survivor Objects Size\n" +
                            "* => c) Next Cycle's VM Reported Heap Used Space = Initial Used Heap Space + Allocation Profiler Size + Survivor Object Size\n" +
                            "*===================================================*\n");
                        Log.println("Initial Used Heap Size = " + beforeAllocProfiler + " MB");
                        float allocProfilerSize = afterAllocProfiler - beforeAllocProfiler;
                        Log.println("Allocation Profiler Size = " + allocProfilerSize + " MB\n");
                    }
                }
                break;
            }

            case TERMINATING: {
                JniFunctions.printJniFunctionTimers();
                terminateProfilers();
                break;
            }
            default: {
                break;
            }
        }
    }

    /**
     * Initializes basic features of the VM, including all of the VM schemes and the trap handling mechanism.
     * It also parses some program arguments that were not parsed earlier.
     */
    protected final void initializeBasicFeatures() {
        MaxineVM vm = vm();
        vm.phase = MaxineVM.Phase.STARTING;

        // Now we can decode all the other VM arguments using the full language
        if (VMOptions.parseStarting()) {
            VMLog.checkLogOptions();

            vmConfig().initializeSchemes(MaxineVM.Phase.STARTING);
            if (Heap.ExcessiveGCFrequency != 0) {
                new ExcessiveGCDaemon(Heap.ExcessiveGCFrequency).start();
            }
            if (Deoptimization.DeoptimizeALot != 0 && Deoptimization.UseDeopt) {
                new DeoptimizeALot(Deoptimization.DeoptimizeALot).start();
            }
            // Install the signal handler for dumping threads when SIGHUP is received
            Signal.handle(new Signal("QUIT"), new PrintThreads(false));
        }
    }

    protected boolean parseMain() {
        return VMOptions.parseMain(true);
    }

    /**
     * The run() method is the entrypoint to this run scheme, after the VM has started up.
     * This method initializes the basic features, parses the main program arguments, looks
     * up the user-specified main class, and invokes its main method with the specified
     * command-line arguments
     */
    public void run() throws Throwable {
        boolean error = true;
        String classKindName = "premain";
        try {
            initializeBasicFeatures();
            if (VMOptions.earlyVMExitRequested()) {
                return;
            }

            loadVMExtensions();

            error = false;

            if (versionOption.isPresent()) {
                sun.misc.Version.print();
                return;
            }
            if (showVersionOption.isPresent()) {
                sun.misc.Version.print();
            }
            if (showSettingsOption.isPresent()) {
                final String showSettingsOptionValue = showSettingsOption.getValue();
                JDK_sun_launcher_LauncherHelper.showSettings(true, showSettingsOptionValue, 0, 0, 0, true);
            }
            if (!parseMain()) {
                return;
            }

            error = true;
            MaxineVM vm = vm();
            vmConfig().initializeSchemes(MaxineVM.Phase.RUNNING);
            vm.phase = Phase.RUNNING;
            mainClassName = getMainClassName();
            VMTI.handler().vmInitialized();
            VMTI.handler().threadStart(VmThread.current());
            // load -javaagent agents
            loadJavaAgents();
            classKindName = "main";
            Class<?> mainClass = loadMainClass();
            if (mainClass != null) {
                lookupAndInvokeMain(mainClass);
                error = false;
            }

        } catch (ClassNotFoundException classNotFoundException) {
            error = true;
            System.err.println("Could not load " + classKindName + "class: " + classNotFoundException);
        } catch (NoClassDefFoundError noClassDefFoundError) {
            error = true;
            System.err.println("Error loading " + classKindName + "class: " + noClassDefFoundError);
        } catch (NoSuchMethodException noSuchMethodException) {
            error = true;
            System.err.println("Could not find " + classKindName + "method: " + noSuchMethodException);
        } catch (InvocationTargetException invocationTargetException) {
            // This is an application exception: let VmThread.run() handle this.
            // We only catch it here to set the VM exit code to a non-zero value.
            error = true;
            throw invocationTargetException.getCause();
        } catch (IllegalAccessException illegalAccessException) {
            error = true;
            System.err.println("Illegal access trying to invoke " + classKindName + "method: " + illegalAccessException);
        } catch (IOException ioException) {
            error = true;
            System.err.println("error reading jar file: " + ioException);
        } catch (ProgramError programError) {
            error = true;
            Log.print("ProgramError: ");
            Log.println(programError.getMessage());
        } finally {
            if (error) {
                MaxineVM.setExitCode(-1);
            }
        }
    }

    private void lookupAndInvokeMain(Class<?> mainClass) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        final Method mainMethod = lookupMainOrAgentClass(mainClass, "main", String[].class);
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                mainMethod.setAccessible(true);
                return null;
            }
        });
        mainMethod.invoke(null, new Object[] {VMOptions.mainClassArguments()});
    }

    /**
     * Try to locate a given method name and signature that is also public static void in given class.
     * @param mainClass class to search
     * @param methodName name of method
     * @param params parameter types
     * @return the method instance
     * @throws NoSuchMethodException if the method cannot be found
     */
    public static Method lookupMainOrAgentClass(Class<?> mainClass, String methodName, Class<?> ...params) throws NoSuchMethodException {
        final Method mainMethod = mainClass.getDeclaredMethod(methodName, params);
        final int modifiers = mainMethod.getModifiers();
        if ((!Modifier.isPublic(modifiers)) || (!Modifier.isStatic(modifiers)) || (mainMethod.getReturnType() != void.class)) {
            throw new NoSuchMethodException(methodName);
        }
        return mainMethod;
    }

    private Class<?> loadMainClass() throws IOException, ClassNotFoundException {
        final ClassLoader appClassLoader = Launcher.getLauncher().getClassLoader();
        return appClassLoader.loadClass(mainClassName);
    }

    /**
     * @return CPUSamplingProfiler instance or null
     */
    public static CPUSamplingProfiler getCPUSamplingProfiler() {
        return cpuSamplingProfiler;
    }

    /**
     * @return HeapSamplingProfiler instance or null
     */
    public static HeapSamplingProfiler getHeapSamplingProfiler() {
        return heapSamplingProfiler;
    }

    /**
     * Finds the main class name from the command line either explicitly or via the jar file.
     *
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static String getMainClassName() throws IOException, ClassNotFoundException {
        if (mainClassName == null) {
            final String jarFileName = VMOptions.jarFile();
            if (jarFileName == null) {
                // the main class was specified on the command line
                mainClassName = VMOptions.mainClassName();
            } else {
                // the main class is in the jar file
                final JarFile jarFile = new JarFile(jarFileName);
                mainClassName = findClassAttributeInJarFile(jarFile, "Main-Class");
                if (mainClassName == null) {
                    throw new ClassNotFoundException("Could not find main class in jarfile: " + jarFileName);
                }
            }
            VMProperty.SUN_JAVA_COMMAND.updateImmutableValue(mainClassName);
            System.setProperty(VMProperty.SUN_JAVA_COMMAND.property, mainClassName);
        }
        return mainClassName;
    }

    /**
     * Searches the manifest in given jar file for given attribute.
     * @param jarFile jar file to search
     * @param classAttribute attribute to search for
     * @return the value of the attribute of null if not found
     * @throws IOException if error reading jar file
     */
    public static String findClassAttributeInJarFile(JarFile jarFile, String classAttribute) throws IOException {
        final Manifest manifest =  jarFile.getManifest();
        if (manifest == null) {
            return null;
        }
        return manifest.getMainAttributes().getValue(classAttribute);
    }

    /**
     * The method used to extend the class path of the app class loader with entries specified by an agent.
     * Reflection is used for this as the method used to make the addition depends on the JDK
     * version in use.
     */
    private static final Method addURLToAppClassLoader;
    static {
        Method method;
        try {
            method = Launcher.class.getDeclaredMethod("addURL", URL.class);
        } catch (NoSuchMethodException e) {
            try {
                method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            } catch (NoSuchMethodException e2) {
                throw FatalError.unexpected("Cannot find method to extend class path of app class loader");
            }
        }
        method.setAccessible(true);
        addURLToAppClassLoader = method;
    }


    /**
     * Callback class for handling the option specific details of loading agent/vm extension code from jar files.
      */
    private static abstract class JarFileOptionHandler {
        abstract String classNameAttribute();
        abstract String classPathAttribute();
        abstract void handle(String className, URL[] urls, String agentArgs)
            throws IOException, ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException;
    }

    private static class JavaAgentJarFileOptionHandler extends JarFileOptionHandler {
        @Override
        String classNameAttribute() {
            return "Premain-Class";
        }

        @Override
        String classPathAttribute() {
            return "Boot-Class-Path";
        }

        @Override
        void handle(String className, URL[] urls, String agentArgs)
            throws IOException, ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
            for (URL url : urls) {
                addURLToAppClassLoader.invoke(Launcher.getLauncher().getClassLoader(), url);
            }
            invokeMethod(className, urls[0], "premain", agentArgs);
        }

        private void invokeMethod(String className, URL url, String methodName, String args) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
            final ClassLoader appClassLoader = Launcher.getLauncher().getClassLoader();
            final Class<?> agentClass = appClassLoader.loadClass(className);
            Method method = null;
            Object[] invokeArgs = null;
            try {
                method = lookupMainOrAgentClass(agentClass, methodName, new Class<?>[] {String.class, Instrumentation.class});
                invokeArgs = new Object[2];
                invokeArgs[1] = InstrumentationManager.createInstrumentation();
            } catch (NoSuchMethodException ex) {
                method = lookupMainOrAgentClass(agentClass, methodName, new Class<?>[] {String.class});
                invokeArgs = new Object[1];
            }
            invokeArgs[0] = args;
            InstrumentationManager.registerAgent(url);
            method.invoke(null, invokeArgs);
        }

    }

    private static final JavaAgentJarFileOptionHandler javaAgentJarFileOptionHandler = new JavaAgentJarFileOptionHandler();

    private void loadJavaAgents()
        throws IOException, ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        loadJarFile(javaagentOption, javaAgentJarFileOptionHandler);
    }

    private void loadJarFile(JarFileVMOption jarFileVMOption, JarFileOptionHandler handler)
        throws IOException, ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        for (int i = 0; i < jarFileVMOption.count(); i++) {
            final String jarFileVMOptionString = jarFileVMOption.getValue(i);
            String jarPath = null;
            String agentArgs = null;  // spec is silent, Hotspot passes null
            final int cIndex = jarFileVMOptionString.indexOf(':');
            if (jarFileVMOptionString.length() > 1 && cIndex >= 0) {
                final int eIndex = jarFileVMOptionString.indexOf('=', cIndex);
                if (eIndex > 0) {
                    jarPath = jarFileVMOptionString.substring(cIndex + 1, eIndex);
                    agentArgs = jarFileVMOptionString.substring(eIndex + 1);
                } else {
                    jarPath = jarFileVMOptionString.substring(cIndex + 1);
                }
                JarFile jarFile = null;
                try {
                    jarFile = new JarFile(jarPath);
                    final String className = findClassAttributeInJarFile(jarFile, handler.classNameAttribute());
                    if (className == null) {
                        throw new IOException("could not find " + handler.classNameAttribute() + "in jarfile manifest: " + jarFile.getName());
                    }
                    ArrayList<String> classPathParts = null;
                    final String classPath = findClassAttributeInJarFile(jarFile, handler.classPathAttribute());
                    if (classPath != null) {
                        classPathParts = jarFileClassPaths(classPath);
                    }
                    URL[] urls = new URL[classPath == null ? 1 : 1 + classPathParts.size()];
                    String jarAbsPath = new File(jarFile.getName()).getAbsolutePath();
                    urls[0] = new URL("file://" + jarAbsPath);
                    for (int u = 1; u < urls.length; u++) {
                        String classPathPart = classPathParts.get(u - 1);
                        String absClassPathPart = classPathPart;
                        if (classPathPart.charAt(0) == '/') {
                            // absolute
                        } else {
                            // relative to jar
                            absClassPathPart = new File(jarAbsPath).getParent() + File.separator + classPathPart;
                        }
                        urls[u] = new URL("file://" + absClassPathPart);
                    }
                    handler.handle(className, urls, agentArgs);
                } finally {
                    if (jarFile != null) {
                        jarFile.close();
                    }
                }
            } else {
                throw new IOException("syntax error in " + jarFileVMOption.optionName + jarFileVMOptionString);
            }
        }
    }

    private static ArrayList<String> jarFileClassPaths(String classPath) {
        ArrayList<String> result = new ArrayList<String>();
        String trimmedClassPath = classPath.trim();
        int sx = trimmedClassPath.indexOf(' ');
        if (sx < 0) {
            result.add(trimmedClassPath);
            return result;
        }
        int length = trimmedClassPath.length();
        int psx = 0;
        while (true) {
            result.add(trimmedClassPath.substring(psx, sx));
            if (sx >= length) {
                break;
            }
            while (sx < length && trimmedClassPath.charAt(sx) == ' ') {
                sx++;
            }
            psx = sx;
            while (sx < length && trimmedClassPath.charAt(sx) != ' ') {
                sx++;
            }
        }
        return result;
    }

    private static class VMExtensionJarFileOptionHandler extends JarFileOptionHandler {
        @Override
        String classNameAttribute() {
            return "VMExtension-Class";
        }

        @Override
        String classPathAttribute() {
            return "VM-Class-Path";
        }

        @Override
        void handle(String className, URL[] urls, String args)
            throws IOException, ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
            for (URL url : urls) {
                VMClassLoader.VM_CLASS_LOADER.addURL(url);
            }
            invokeMethod(className, args);
        }

        private void invokeMethod(String className, String args)
            throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
            final Class<?> klass = VMClassLoader.VM_CLASS_LOADER.loadClass(className);
            Method method = lookupMainOrAgentClass(klass, "onLoad", new Class<?>[] {String.class});
            Object[] invokeArgs = new Object[1];
            invokeArgs[0] = args;
            method.invoke(null, invokeArgs);
        }

    }

    private static final VMExtensionJarFileOptionHandler vmExtensionJarFileOptionHandler = new VMExtensionJarFileOptionHandler();

    private void loadVMExtensions()
        throws IOException, ClassNotFoundException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        loadJarFile(vmExtensionOption, vmExtensionJarFileOptionHandler);
    }
}
