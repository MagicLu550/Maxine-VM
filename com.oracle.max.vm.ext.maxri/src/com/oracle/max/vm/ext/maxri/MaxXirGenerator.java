/*
 * Copyright (c) 2017-2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2015, Andrey Rodchenko. All rights reserved.
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.vm.ext.maxri;

import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.VMConfiguration.*;
import static com.sun.max.vm.compiler.CallEntryPoint.*;
import static com.sun.max.vm.layout.Layout.*;
import static java.lang.reflect.Modifier.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import com.oracle.max.asm.target.armv7.*;
import com.sun.cri.ci.CiAddress.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.ri.RiType.*;
import com.sun.cri.xir.*;
import com.sun.cri.xir.CiXirAssembler.*;
import com.sun.max.*;
import com.sun.max.annotate.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.util.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.classfile.constant.UnresolvedType.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.heap.debug.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.methodhandle.*;
import com.sun.max.vm.object.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.runtime.aarch64.*;
import com.sun.max.vm.runtime.amd64.*;
import com.sun.max.vm.runtime.arm.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;

/**
 * This class is the Maxine's implementation of VM interface for generating XIR snippets that express
 * the low-level implementation of each bytecode for C1X compilation.
 */
public class MaxXirGenerator implements RiXirGenerator {

    private static final int SMALL_MULTIANEWARRAY_RANK = 2;
    private CiRegister LATCH_REGISTER = null;
    // (tw) TODO: Up this to 255 / make a loop in the template
    private static final int MAX_MULTIANEWARRAY_RANK = 6;

    static XirWriteBarrierSpecification writeBarrierSpecification() {
        HeapScheme heapScheme = VMConfiguration.vmConfig().heapScheme();
        if (heapScheme instanceof XirWriteBarrierSpecification) {
            return (XirWriteBarrierSpecification) heapScheme;
        }
        return new XirWriteBarrierSpecification() {
            public XirWriteBarrierGenerator barrierGenerator(IntBitSet<WriteBarrierSpecification.WriteBarrierSpec> writeBarrierSpec) {
                return XirWriteBarrierSpecification.NULL_WRITE_BARRIER_GEN;
            }
        };
    }

    public static class XirPair {
        public final XirTemplate resolved;
        public final XirTemplate unresolved;

        public XirPair(XirTemplate resolved, XirTemplate unresolved) {
            this.resolved = resolved;
            this.unresolved = unresolved;
        }
    }

    static class NewInstanceTemplates extends XirPair {
        final XirTemplate resolvedHybrid;

        NewInstanceTemplates(XirTemplate resolved, XirTemplate resolvedHybrid, XirTemplate unresolved) {
            super(resolved, unresolved);
            this.resolvedHybrid = resolvedHybrid;
        }
    }

    public static class InvokeSpecialTemplates extends XirPair {
        public final XirTemplate resolvedNullCheckEliminated;

        public InvokeSpecialTemplates(XirTemplate resolved, XirTemplate unresolved, XirTemplate resolvedNullCheckEliminated) {
            super(resolved, unresolved);
            this.resolvedNullCheckEliminated = resolvedNullCheckEliminated;
        }
    }

    private final HashMap<String, XirTemplate> runtimeCallStubs = new HashMap<String, XirTemplate>();
    private final HashMap<String, RiMethod> runtimeMethods = new HashMap<String, RiMethod>();

    private XirTemplate epilogueTemplate;
    private XirPair[] putFieldTemplates;
    private XirPair[] getFieldTemplates;
    private XirPair[] putStaticFieldTemplates;
    private XirPair[] getStaticFieldTemplates;

    private XirPair invokeVirtualTemplates;
    private XirPair invokeInterfaceTemplates;
    private InvokeSpecialTemplates invokeSpecialTemplates;
    private XirPair invokeStaticTemplates;
    private XirPair[] newArrayTemplates;
    private XirPair[] tlabNewArrayTemplates;
    private XirTemplate[] arrayLoadTemplates;
    private XirTemplate[] arrayLoadNoBoundsCheckTemplates;
    private XirTemplate[] arrayStoreTemplates;
    private XirTemplate[] arrayStoreNoBoundsCheckTemplates;
    private XirTemplate arrayStoreNoStoreCheckTemplate;
    private XirTemplate arrayStoreNoBoundsOrStoreCheckTemplate;

    private DynamicHub[] arrayHubs;

    private XirPair[] multiNewArrayTemplate;

    private XirTemplate invokeHandleTemplate;
    private XirTemplate linkToSpecialTemplate;
    private XirTemplate linkToInterfaceTemplate;
    private XirTemplate linkToVirtualTemplate;
    private XirTemplate linkToStaticTemplate;

    private XirTemplate safepointTemplate;
    private XirTemplate arraylengthTemplate;
    private XirTemplate monitorEnterTemplate;
    private XirTemplate monitorExitTemplate;
    private XirTemplate[] resolveClassTemplates;
    private NewInstanceTemplates newInstanceTemplate;
    private NewInstanceTemplates tlabNewInstanceTemplate;
    private XirPair checkcastForLeafTemplate;
    private XirPair checkcastForNonLeafTemplate;
    private XirPair instanceofForLeafTemplate;
    private XirPair instanceofForNonLeafTemplate;
    private XirPair materializedInstanceofForLeafTemplate;
    private XirPair materializedInstanceofForLeafAndNonNullTemplate;
    private XirPair materializedInstanceofForNonLeafTemplate;

    private XirTemplate typeAssertTemplate;

    private XirTemplate exceptionObjectTemplate;

    public final List<XirTemplate> stubs = new ArrayList<XirTemplate>();

    @FOLD
    int hubOffset() {
        return generalLayout().getOffsetFromOrigin(Layout.HeaderField.HUB).toInt();
    }

    @FOLD
    int hubFirstWordIndex() {
        return Hub.getFirstWordIndex();
    }

    @FOLD
    int offsetOfFirstArrayElement() {
        return byteArrayLayout().getElementOffsetFromOrigin(0).toInt();
    }

    @FOLD
    int offsetOfMTableStartIndex() {
        return FieldActor.findInstance(ClassActor.fromJava(Hub.class), "mTableStartIndex").offset();
    }

    @FOLD
    int offsetOfMTableLength() {
        return FieldActor.findInstance(ClassActor.fromJava(Hub.class), "mTableLength").offset();
    }

    @FOLD
    int offsetOfTupleSize() {
        return FieldActor.findInstance(ClassActor.fromJava(Hub.class), "tupleSize").offset();
    }

    @FOLD
    int minObjectAlignmentMask() {
        return vmConfig().heapScheme().objectAlignment() - 1;
    }

    public final boolean printXirTemplates;

    public MaxXirGenerator(boolean printXirTemplates) {
        this.printXirTemplates = printXirTemplates;
        if (platform().cpu == CPU.ARMV7) {
            this.LATCH_REGISTER = ARMSafepointPoll.LATCH_REGISTER;
        } else if (platform().target.arch.isAarch64()) {
            this.LATCH_REGISTER = Aarch64SafepointPoll.LATCH_REGISTER;
        } else {
            this.LATCH_REGISTER = AMD64SafepointPoll.LATCH_REGISTER;
        }
    }

    private static final Class<? extends RuntimeCalls> runtimeCalls = RuntimeCalls.class;

    private CiXirAssembler asm;

    @Override
    public List<XirTemplate> makeTemplates(CiXirAssembler asm) {
        if (!stubs.isEmpty()) {
            return stubs;
        }

        CiKind[] kinds = CiKind.values();
        this.asm = asm;

        epilogueTemplate = buildEpilogue();

        putFieldTemplates = new XirPair[kinds.length];
        getFieldTemplates = new XirPair[kinds.length];
        putStaticFieldTemplates = new XirPair[kinds.length];
        getStaticFieldTemplates = new XirPair[kinds.length];

        newArrayTemplates = new XirPair[kinds.length];
        tlabNewArrayTemplates = new XirPair[kinds.length];
        arrayLoadTemplates = new XirTemplate[kinds.length];
        arrayStoreTemplates = new XirTemplate[kinds.length];
        arrayLoadNoBoundsCheckTemplates = new XirTemplate[kinds.length];
        arrayStoreNoBoundsCheckTemplates = new XirTemplate[kinds.length];
        arrayStoreNoBoundsOrStoreCheckTemplate = buildArrayStore(CiKind.Object, asm, false, false, true);
        arrayStoreNoStoreCheckTemplate = buildArrayStore(CiKind.Object, asm, true, false, true);

        arrayHubs = new DynamicHub[kinds.length];

        arrayHubs[CiKind.Boolean.ordinal()] = ClassRegistry.BOOLEAN_ARRAY.dynamicHub();
        arrayHubs[CiKind.Byte.ordinal()] = ClassRegistry.BYTE_ARRAY.dynamicHub();
        arrayHubs[CiKind.Short.ordinal()] = ClassRegistry.SHORT_ARRAY.dynamicHub();
        arrayHubs[CiKind.Char.ordinal()] = ClassRegistry.CHAR_ARRAY.dynamicHub();
        arrayHubs[CiKind.Int.ordinal()] = ClassRegistry.INT_ARRAY.dynamicHub();
        arrayHubs[CiKind.Float.ordinal()] = ClassRegistry.FLOAT_ARRAY.dynamicHub();
        arrayHubs[CiKind.Double.ordinal()] = ClassRegistry.DOUBLE_ARRAY.dynamicHub();
        arrayHubs[CiKind.Long.ordinal()] = ClassRegistry.LONG_ARRAY.dynamicHub();
        arrayHubs[CiKind.Object.ordinal()] = ClassActor.fromJava(Object[].class).dynamicHub();

        for (CiKind kind : kinds) {
            int index = kind.ordinal();
            if (kind == CiKind.Illegal || kind == CiKind.Jsr) {
                continue;
            }
            if (kind != CiKind.Void) {
                putFieldTemplates[index] = buildPutFieldTemplates(kind, kind == CiKind.Object, false);
                getFieldTemplates[index] = buildGetFieldTemplates(kind, false);
                putStaticFieldTemplates[index] = buildPutFieldTemplates(kind, kind == CiKind.Object, true);
                getStaticFieldTemplates[index] = buildGetFieldTemplates(kind, true);
                arrayLoadTemplates[index] = buildArrayLoad(kind, asm, true);
                arrayLoadNoBoundsCheckTemplates[index] = buildArrayLoad(kind, asm, false);
                arrayStoreTemplates[index] = buildArrayStore(kind, asm, true, kind == CiKind.Object, kind == CiKind.Object);
                arrayStoreNoBoundsCheckTemplates[index] = buildArrayStore(kind, asm, false, kind == CiKind.Object, kind == CiKind.Object);
                newArrayTemplates[index] = buildNewArray(kind);
                tlabNewArrayTemplates[index] = buildTLABNewArray(kind);
            }
        }

        invokeVirtualTemplates = buildInvokeVirtual();
        invokeInterfaceTemplates = buildInvokeInterface();
        invokeSpecialTemplates = buildInvokeSpecial();
        invokeStaticTemplates = buildInvokeStatic();

        invokeHandleTemplate = buildInvokeHandle();
        linkToVirtualTemplate = buildLinkToVirtual();
        linkToInterfaceTemplate = buildLinkToInterface();
        linkToSpecialTemplate = buildLinkToSpecial();
        linkToStaticTemplate = buildLinkToStatic();

        multiNewArrayTemplate = new XirPair[MAX_MULTIANEWARRAY_RANK + 1];

        for (int i = 1; i < MAX_MULTIANEWARRAY_RANK + 1; i++) {
            multiNewArrayTemplate[i] = buildNewMultiArray(i);
        }

        resolveClassTemplates = new XirTemplate[Representation.values().length];
        for (Representation representation : Representation.values()) {
            resolveClassTemplates[representation.ordinal()] = buildResolveClass(representation);
        }

        safepointTemplate = buildSafepoint();
        arraylengthTemplate = buildArrayLength();
        monitorEnterTemplate = buildMonitorEnter();
        monitorExitTemplate = buildMonitorExit();

        newInstanceTemplate = buildNewInstance();
        tlabNewInstanceTemplate = buildTLABNewInstance();

        checkcastForLeafTemplate = buildCheckcastForLeaf(false);
        checkcastForNonLeafTemplate = buildCheckcastForNonLeaf(false);

        instanceofForLeafTemplate = buildInstanceofForLeaf(false);
        instanceofForNonLeafTemplate = buildInstanceofForNonLeaf(false);

        materializedInstanceofForLeafTemplate = buildMaterializeInstanceOf(false, true);
        materializedInstanceofForLeafAndNonNullTemplate = buildMaterializeInstanceOf(true, true);
        materializedInstanceofForNonLeafTemplate = buildMaterializeInstanceOf(false, false);

        typeAssertTemplate = buildTypeAssert();

        exceptionObjectTemplate = buildExceptionObject();

        MaxTargetMethod.initializeMaxXirGeneratorRuntimeCallsMonitorEnterMethodActor(getRuntimeCallsMonitorEnterMethodActor());

        return stubs;
    }

    /**
     * Returns method actor for {@link RuntimeCalls#monitorEnter} method of {@link RuntimeCalls) class.
     */
    @HOSTED_ONLY
    private static StaticMethodActor getRuntimeCallsMonitorEnterMethodActor() {
        Utf8Constant runtimeCallsMonitorEnterMethodName = SymbolTable.makeSymbol("monitorEnter");
        return ClassActor.fromJava(MaxXirGenerator.RuntimeCalls.class).findLocalStaticMethodActor(runtimeCallsMonitorEnterMethodName);
    }

    @Override
    public XirSnippet genPrologue(XirSite site, RiResolvedMethod method) {
        ClassMethodActor callee = (ClassMethodActor) method;

        if (callee.isTemplate()) {
            return null;
        }

        // Cannot share 'asm' across concurrent compilations.
        CiXirAssembler asm = this.asm.copy();
        asm.restart(CiKind.Void);

        AdapterGenerator generator = AdapterGenerator.forCallee(callee, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        if (generator != null) {
            ByteArrayOutputStream os = new ByteArrayOutputStream(8);
            generator.adapt(callee, os);
            asm.rawBytes(os.toByteArray());
        }

        asm.pushFrame();

        if (!callee.isVmEntryPoint()) {
            asm.stackOverflowCheck();
        }

        if (MaxineVM.isRunning() &&
                methodIsAllocationProfilerEntryOrExitPoint(method, CompilationBroker.AllocationProfilerEntryPoint)) {
            XirOperand  tla             = asm.createRegisterTemp("TLA", WordUtil.archKind(), this.LATCH_REGISTER);
            XirConstant offsetToProfile = asm.i(VmThreadLocal.PROFILER_TLA.offset);
            XirConstant constantOne     = asm.i(1);
            asm.pstore(WordUtil.archKind(), tla, offsetToProfile, constantOne, false);
        }

        return new XirSnippet(finishTemplate(asm, "prologue"));
    }

    @HOSTED_ONLY
    private XirTemplate buildEpilogue() {
        asm.restart(CiKind.Void);
        asm.popFrame();
        // TODO safepoint check
        return asm.finishTemplate("epilogue");
    }

    @Override
    public XirSnippet genEpilogue(XirSite site, RiResolvedMethod method) {
        ClassMethodActor callee = (ClassMethodActor) method;
        if (callee.isTemplate()) {
            return null;
        }
        if (MaxineVM.isRunning() &&
                methodIsAllocationProfilerEntryOrExitPoint(method, CompilationBroker.AllocationProfilerExitPoint)) {
            XirOperand  tla             = asm.createRegisterTemp("TLA", WordUtil.archKind(), this.LATCH_REGISTER);
            XirConstant offsetToProfile = asm.i(VmThreadLocal.PROFILER_TLA.offset);
            XirConstant constantZero    = asm.i(0);
            asm.pstore(WordUtil.archKind(), tla, offsetToProfile, constantZero, false);
        }
        return new XirSnippet(epilogueTemplate);
    }

    private boolean methodIsAllocationProfilerEntryOrExitPoint(RiResolvedMethod method, String entryOrExitPoint) {
        final String methodFullName = method.toString();
        return methodFullName.substring(0, methodFullName.indexOf('(')).equals(entryOrExitPoint);
    }

    @Override
    public XirSnippet genSafepointPoll(XirSite site) {
        return new XirSnippet(safepointTemplate);
    }

    @Override
    public XirSnippet genResolveClass(XirSite site, RiType type, Representation representation) {
        return new XirSnippet(resolveClassTemplates[representation.ordinal()], guardFor(type));
    }

    @Override
    public XirSnippet genInvokeHandle(XirSite site, XirArgument actor) {
        return new XirSnippet(invokeHandleTemplate, actor);
    }

    @Override
    public XirSnippet genInvokeInterface(XirSite site, XirArgument receiver, RiMethod method) {
        XirPair pair = invokeInterfaceTemplates;
        if (method instanceof RiResolvedMethod) {
            InterfaceMethodActor methodActor = (InterfaceMethodActor) method;
            XirArgument interfaceID = XirArgument.forInt(methodActor.holder().id);
            XirArgument methodIndex = XirArgument.forInt(methodActor.iIndexInInterface());
            return new XirSnippet(pair.resolved, receiver, interfaceID, methodIndex);
        }
        XirArgument guard = XirArgument.forObject(guardFor(method));
        return new XirSnippet(pair.unresolved, receiver, guard);
    }

    @Override
    public XirSnippet genInvokeVirtual(XirSite site, XirArgument receiver, RiMethod method) {
        XirPair pair = invokeVirtualTemplates;
        if (method instanceof RiResolvedMethod) {
            VirtualMethodActor methodActor = (VirtualMethodActor) method;
            XirArgument vtableOffset = XirArgument.forInt(methodActor.vTableIndex() * Word.size() + offsetOfFirstArrayElement());
            return new XirSnippet(pair.resolved, receiver, vtableOffset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(method));
        return new XirSnippet(pair.unresolved, receiver, guard);
    }

    @Override
    public XirSnippet genInvokeSpecial(XirSite site, XirArgument receiver, RiMethod method) {
        if (method instanceof RiResolvedMethod) {
            if (site.requiresNullCheck()) {
                return new XirSnippet(invokeSpecialTemplates.resolved, WordUtil.argument(Word.zero()), receiver);
            }
            return new XirSnippet(invokeSpecialTemplates.resolvedNullCheckEliminated, WordUtil.argument(Word.zero()));
        }

        XirArgument guard = XirArgument.forObject(guardFor(method));
        return new XirSnippet(invokeSpecialTemplates.unresolved, guard);
    }

    @Override
    public XirSnippet genInvokeStatic(XirSite site, RiMethod method) {
        if (method instanceof RiResolvedMethod) {
            //assert C1XOptions.ResolveClassBeforeStaticInvoke : "need to add class initialization barrier for INVOKESTATIC";
            return new XirSnippet(invokeStaticTemplates.resolved, WordUtil.argument(Word.zero()));
        }

        XirArgument guard = XirArgument.forObject(guardFor(method));
        return new XirSnippet(invokeStaticTemplates.unresolved, guard);
    }

    @Override
    public XirSnippet genLinkToSpecial(XirSite site, XirArgument memberName, RiMethod target) {
        return new XirSnippet(linkToSpecialTemplate, memberName);
    }

    @Override
    public XirSnippet genLinkToInterface(XirSite site, XirArgument receiver, XirArgument memberName, RiMethod target) {
        return new XirSnippet(linkToInterfaceTemplate, memberName, receiver);
    }

    @Override
    public XirSnippet genLinkToVirtual(XirSite site, XirArgument receiver, XirArgument memberName, RiMethod target) {
        return new XirSnippet(linkToVirtualTemplate, memberName, receiver);
    }

    @Override
    public XirSnippet genLinkToStatic(XirSite site, XirArgument memberName, RiMethod target) {
        return new XirSnippet(linkToStaticTemplate, memberName);
    }

    @Override
    public XirSnippet genMonitorEnter(XirSite site, XirArgument receiver, XirArgument lockAddress) {
        return new XirSnippet(monitorEnterTemplate, receiver);
    }

    @Override
    public XirSnippet genMonitorExit(XirSite site, XirArgument receiver, XirArgument lockAddress) {
        return new XirSnippet(monitorExitTemplate, receiver);
    }

    @Override
    public XirSnippet genGetField(XirSite site, XirArgument receiver, RiField field) {
        XirPair pair = getFieldTemplates[field.kind(true).ordinal()];
        if (field instanceof RiResolvedField) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, receiver, offset);
        }

        XirArgument guard = XirArgument.forObject(guardFor(field));
        return new XirSnippet(pair.unresolved, receiver, guard);
    }

    @Override
    public XirSnippet genPutField(XirSite site, XirArgument receiver, RiField field, XirArgument value) {
        XirPair pair = putFieldTemplates[field.kind(true).ordinal()];
        if (field instanceof RiResolvedField) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, receiver, value, offset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(field));
        return new XirSnippet(pair.unresolved, receiver, value, guard);
    }

    @Override
    public XirSnippet genGetStatic(XirSite site, XirArgument staticTuple, RiField field) {
        XirPair pair = getStaticFieldTemplates[field.kind(true).ordinal()];
        if (field instanceof RiResolvedField) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, staticTuple, offset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(field));
        return new XirSnippet(pair.unresolved, staticTuple, guard);
    }

    @Override
    public XirSnippet genPutStatic(XirSite site, XirArgument staticTuple, RiField field, XirArgument value) {
        XirPair pair = putStaticFieldTemplates[field.kind(true).ordinal()];
        if (field instanceof RiResolvedField) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, staticTuple, value, offset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(field));
        return new XirSnippet(pair.unresolved, staticTuple, value, guard);
    }


    private boolean useTLABs() {
        if (DebugHeap.isTagging()) {
            // The XIR for TLAB allocation does not handle this (yet)
            return false;
        }
        // TODO: second clause in each of the two conditions below should disappear. This is just to evaluate the impact of
        // inlined tlab allocation on performance.
        if (MaxineVM.isHosted()) {
            return vmConfig().heapScheme() instanceof HeapSchemeWithTLAB && Heap.genInlinedTLAB;
        }
        return vmConfig().heapScheme().usesTLAB() && HeapSchemeWithTLAB.GenInlinedTLABAlloc;
    }

    @Override
    public XirSnippet genNewInstance(XirSite site, RiType type) {
        NewInstanceTemplates templates = useTLABs() ? tlabNewInstanceTemplate : newInstanceTemplate;
        if (type instanceof RiResolvedType) {
            RiResolvedType resolvedType = (RiResolvedType) type;
            if (resolvedType.isInitialized()) {
                final DynamicHub hub = hubFor(resolvedType);
                final XirTemplate template = hub.classActor.isHybridClass() ? templates.resolvedHybrid : templates.resolved;
                if (useTLABs()) {
                    return new XirSnippet(template, XirArgument.forObject(hub), XirArgument.forInt(hub.tupleSize.toInt()));
                }
                return new XirSnippet(template, XirArgument.forObject(hub));
            }
        }
        XirArgument guard = guardFor(type);
        return new XirSnippet(templates.unresolved, guard);
    }

    private DynamicHub hubFor(RiType type) {
        return ((ClassActor) type).dynamicHub();
    }

    @Override
    public XirSnippet genNewArray(XirSite site, XirArgument length, CiKind elementKind, RiType componentType, RiType arrayType) {
        XirPair [] templates = useTLABs() ? tlabNewArrayTemplates : newArrayTemplates;
        XirPair pair = templates[elementKind.ordinal()];
        Object hub = arrayHubs[elementKind.ordinal()];
        if (elementKind == CiKind.Object && arrayType instanceof RiResolvedType) {
            hub = hubFor(arrayType);
        } else if (elementKind == CiKind.Object) {
            hub = null;
        }

        if (hub != null) {
            return new XirSnippet(pair.resolved, XirArgument.forObject(hub), length);
        }
        XirArgument guard = guardForComponentType(componentType);
        return new XirSnippet(pair.unresolved, guard, length);
    }

    @Override
    public XirSnippet genNewMultiArray(XirSite site, XirArgument[] lengths, RiType type) {
        int rank = lengths.length;
        if (!(type instanceof RiResolvedType) || rank >= SMALL_MULTIANEWARRAY_RANK) {
            XirArgument guard = guardFor(type);
            return new XirSnippet(multiNewArrayTemplate[rank].unresolved, Utils.concat(lengths, guard));
        }
        if (rank >= multiNewArrayTemplate.length) {
            FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxXirGenerator.genNewMultiArray");
        }
        XirArgument hub = XirArgument.forObject(hubFor(type));
        return new XirSnippet(multiNewArrayTemplate[rank].resolved, Utils.concat(lengths, hub));
    }

    @Override
    public XirSnippet genCheckCast(XirSite site, XirArgument object, XirArgument hub, RiType type) {
        if (type instanceof RiResolvedType) {
            RiResolvedType resolvedType = (RiResolvedType) type;
            XirTemplate template;
            if (isFinal(resolvedType.accessFlags()) && !resolvedType.isArrayClass()) {
                assert !resolvedType.isInterface();
                // can use the leaf class test
                template = checkcastForLeafTemplate.resolved;
            } else {
                // can use the class test
                template = checkcastForNonLeafTemplate.resolved;
                ClassActor classActor = (ClassActor) resolvedType;
                int typeID = classActor.id;
                return new XirSnippet(template, object, XirArgument.forInt(typeID), hub);
            }
            return new XirSnippet(template, object, hub);
        }
        XirArgument guard = guardFor(type);
        return new XirSnippet(checkcastForNonLeafTemplate.unresolved, object, guard);
    }

    @Override
    public XirSnippet genInstanceOf(XirSite site, XirArgument object, XirArgument hub, RiType type) {
        if (type instanceof RiResolvedType) {
            RiResolvedType resolvedType = (RiResolvedType) type;
            XirTemplate template;
            if (isFinal(resolvedType.accessFlags()) && !resolvedType.isArrayClass()) {
                assert !resolvedType.isInterface();
                template = instanceofForLeafTemplate.resolved;
                return new XirSnippet(template, object, hub);
            } else {
                template = instanceofForNonLeafTemplate.resolved;
                ClassActor classActor = (ClassActor) type;
                int typeID = classActor.id;
                return new XirSnippet(template, object, XirArgument.forInt(typeID), hub);
            }
        }
        XirArgument guard = guardFor(type);
        return new XirSnippet(instanceofForNonLeafTemplate.unresolved, object, guard);
    }


    @Override
    public XirSnippet genMaterializeInstanceOf(XirSite site, XirArgument receiver, XirArgument hub, XirArgument trueValue, XirArgument falseValue, RiType type) {
        if (type instanceof RiResolvedType) {
            RiResolvedType resolvedType = (RiResolvedType) type;
            XirTemplate template;
            if (isFinal(resolvedType.accessFlags()) && !resolvedType.isArrayClass()) {
                assert !resolvedType.isInterface();
                if (site.isNonNull(receiver)) {
                    template = materializedInstanceofForLeafAndNonNullTemplate.resolved;
                } else {
                    template = materializedInstanceofForLeafTemplate.resolved;
                }
                return new XirSnippet(template, receiver, hub, trueValue, falseValue);
            } else {
                template = materializedInstanceofForNonLeafTemplate.resolved;
                ClassActor classActor = (ClassActor) type;
                int typeID = classActor.id;
                return new XirSnippet(template, receiver, hub, trueValue, falseValue, XirArgument.forInt(typeID));
            }
        } else {
            XirArgument guard = guardFor(type);
            return new XirSnippet(materializedInstanceofForNonLeafTemplate.unresolved, receiver, guard, trueValue, falseValue);
        }
    }

    @Override
    public XirSnippet genTypeCheck(XirSite site, XirArgument object, XirArgument hub, RiType type) {
        assert type instanceof RiResolvedType;
        assert site.isNonNull(object);
        return new XirSnippet(typeAssertTemplate, object, hub);
    }

    @Override
    public XirSnippet genArrayLoad(XirSite site, XirArgument array, XirArgument index, CiKind elementKind, RiType elementType) {
        XirTemplate template;
        if (site.requiresBoundsCheck()) {
            template = arrayLoadTemplates[elementKind.ordinal()];
        } else {
            template = arrayLoadNoBoundsCheckTemplates[elementKind.ordinal()];
        }
        return new XirSnippet(template, array, index);
    }

    @Override
    public XirSnippet genArrayStore(XirSite site, XirArgument array, XirArgument index, XirArgument value, CiKind elementKind, RiType elementType) {
        XirTemplate template;
        if (elementKind.isObject()) {
            if (site.requiresBoundsCheck() && site.requiresArrayStoreCheck()) {
                template = arrayStoreTemplates[CiKind.Object.ordinal()];
            } else if (site.requiresArrayStoreCheck()) {
                // no bounds check
                template = arrayStoreNoBoundsCheckTemplates[CiKind.Object.ordinal()];
            } else if (site.requiresBoundsCheck()) {
                // no store check
                template = arrayStoreNoStoreCheckTemplate;
            } else {
                template = arrayStoreNoBoundsOrStoreCheckTemplate;
            }
        } else if (site.requiresBoundsCheck()) {
            template = arrayStoreTemplates[elementKind.ordinal()];
        } else {
            template = arrayStoreNoBoundsCheckTemplates[elementKind.ordinal()];
        }
        return new XirSnippet(template, array, index, value);
    }

    @Override
    public XirSnippet genArrayLength(XirSite site, XirArgument array) {
        return new XirSnippet(arraylengthTemplate, array);
    }

    @Override
    public XirSnippet genExceptionObject(XirSite site) {
        return new XirSnippet(exceptionObjectTemplate);
    }

    public ResolutionGuard guardFor(RiField unresolvedField) {
        UnresolvedField f = (UnresolvedField) unresolvedField;
        return makeResolutionGuard(f.constantPool, f.cpi);
    }

    public ResolutionGuard guardFor(RiMethod unresolvedMethod) {
        UnresolvedMethod m = (UnresolvedMethod) unresolvedMethod;
        return makeResolutionGuard(m.constantPool, m.cpi);
    }

    private XirArgument guardForComponentType(RiType type) {
        if (type instanceof InPool) {
            InPool unresolvedType = (InPool) type;
            return XirArgument.forObject(makeResolutionGuard(unresolvedType.pool, unresolvedType.cpi));
        }
        return XirArgument.forObject(new ResolutionGuard.InAccessingClass((ByAccessingClass) type));
    }

    private XirArgument guardFor(RiType type) {
        ResolutionGuard guard;
        if (type instanceof RiResolvedType) {
            guard = new ResolutionGuard.InPool(null, Integer.MAX_VALUE);
            guard.value = (ClassActor) type;
        } else {
            if (type instanceof InPool) {
                InPool unresolvedType = (InPool) type;
                guard = makeResolutionGuard(unresolvedType.pool, unresolvedType.cpi);
            } else {
                guard = new ResolutionGuard.InAccessingClass((ByAccessingClass) type);
            }
        }
        return XirArgument.forObject(guard);
    }

    private ResolutionGuard makeResolutionGuard(ConstantPool pool, int cpi) {
        assert cpi > 0;
        return pool.makeResolutionGuard(cpi);
    }

    @HOSTED_ONLY
    private XirTemplate buildResolveClass(Representation representation) {
        XirOperand result = asm.restart(CiKind.Object);
        XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
        String resolver = null;
        switch (representation) {
            // Checkstyle: stop
            case JavaClass    : resolver = "resolveClassObject"; break;
            case ObjectHub    : resolver = "resolveHub"; break;
            case StaticFields : resolver = "resolveStaticTuple"; break;
            case TypeInfo     : resolver = "resolveClassActor"; break;
            // Checkstyle: resume
        }
        callRuntimeThroughStub(asm, resolver, result, guard);
        return finishTemplate(asm, resolver);
    }

    @HOSTED_ONLY
    private XirTemplate buildSafepoint() {
        asm.restart(CiKind.Void);
        XirOperand latch = asm.createRegisterTemp("latch", WordUtil.archKind(), LATCH_REGISTER);
        asm.safepoint(0);
        asm.pload(WordUtil.archKind(), latch, latch, false);
        return finishTemplate(asm, "safepoint");
    }

    @HOSTED_ONLY
    private XirTemplate buildArrayLength() {
        XirOperand result = asm.restart(CiKind.Int);
        XirOperand param = asm.createInputParameter("param", CiKind.Object);
        asm.pload(WordUtil.archKind(), result, param, asm.i(arrayLayout().arrayLengthOffset()), true);
        return finishTemplate(asm, "arraylength");
    }

    @HOSTED_ONLY
    private XirTemplate buildArrayStore(CiKind kind, CiXirAssembler asm, boolean genBoundsCheck, boolean genStoreCheck, boolean genWriteBarrier) {
        XirWriteBarrierSpecification writeBarrierSpecification = writeBarrierSpecification();
        asm.restart(CiKind.Void);
        XirParameter array = asm.createInputParameter("array", CiKind.Object);
        XirParameter index = asm.createInputParameter("index", CiKind.Int);
        XirParameter value = asm.createInputParameter("value", kind);
        XirOperand length = asm.createTemp("length", CiKind.Int);
        XirOperand valueHub = null;
        XirOperand compHub = null;
        XirLabel store = asm.createInlineLabel("store");
        XirLabel failBoundsCheck = null;
        XirLabel slowStoreCheck = null;
        if (genBoundsCheck) {
            // load the array length and check the index
            failBoundsCheck = asm.createOutOfLineLabel("failBoundsCheck");
            asm.pload(CiKind.Int, length, array, asm.i(arrayLayout().arrayLengthOffset()), true);
            asm.jugteq(failBoundsCheck, index, length);
        }
        if (genStoreCheck) {
            slowStoreCheck = asm.createOutOfLineLabel("slowStoreCheck");
            asm.jeq(store, value, asm.o(null)); // first check if value is null
            valueHub = asm.createTemp("valueHub", CiKind.Object);
            compHub = asm.createTemp("compHub", CiKind.Object);
            int compHubOffset = FieldActor.findInstance(ClassActor.fromJava(Hub.class), "componentHub").offset();
            asm.pload(CiKind.Object, compHub, array, asm.i(hubOffset()), !genBoundsCheck);
            asm.pload(CiKind.Object, compHub, compHub, asm.i(compHubOffset), false);
            asm.pload(CiKind.Object, valueHub, value, asm.i(hubOffset()), false);
            asm.jneq(slowStoreCheck, compHub, valueHub); // then check component hub matches value hub
        }
        asm.bindInline(store);
        int elemSize = target().sizeInBytes(kind);
        if (genWriteBarrier) {
            writeBarrierSpecification.barrierGenerator(WriteBarrierSpecification.ARRAY_PRE_BARRIER).genWriteBarrier(asm, array, index);
        }
        asm.pstore(kind, array, index, value, offsetOfFirstArrayElement(), Scale.fromInt(elemSize), !genBoundsCheck && !genStoreCheck);
        if (genWriteBarrier) {
            writeBarrierSpecification.barrierGenerator(WriteBarrierSpecification.ARRAY_POST_BARRIER).genWriteBarrier(asm, array, index);
        }
        if (genBoundsCheck) {
            asm.bindOutOfLine(failBoundsCheck);
            callRuntimeThroughStub(asm, "throwArrayIndexOutOfBoundsException", null, array, index);
        }
        if (genStoreCheck) {
            asm.bindOutOfLine(slowStoreCheck);
            callRuntimeThroughStub(asm, "arrayHubStoreCheck", null, compHub, valueHub);
            asm.jmp(store);
        }
        return finishTemplate(asm, "arraystore<" + kind + ">");
    }

    @HOSTED_ONLY
    private XirTemplate buildArrayLoad(CiKind kind, CiXirAssembler asm, boolean genBoundsCheck) {
        XirOperand result = asm.restart(kind);
        XirParameter array = asm.createInputParameter("array", CiKind.Object);
        XirParameter index = asm.createInputParameter("index", CiKind.Int);
        XirOperand length = asm.createTemp("length", CiKind.Int);
        XirLabel fail = null;
        if (genBoundsCheck) {
            // load the array length and check the index
            fail = asm.createOutOfLineLabel("fail");
            asm.pload(CiKind.Int, length, array, asm.i(arrayLayout().arrayLengthOffset()), true);
            asm.jugteq(fail, index, length);
        }
        int elemSize = target().sizeInBytes(kind);
        asm.pload(kind, result, array, index, offsetOfFirstArrayElement(), Scale.fromInt(elemSize), !genBoundsCheck);
        if (genBoundsCheck) {
            asm.bindOutOfLine(fail);
            callRuntimeThroughStub(asm, "throwArrayIndexOutOfBoundsException", null, array, index);
        }
        return finishTemplate(asm, "arrayload<" + kind + ">");
    }

    @HOSTED_ONLY
    private XirTemplate buildInvokeHandle() {
        asm.restart();
        XirParameter actor = asm.createInputParameter("actor", CiKind.Object);
        XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
        callRuntimeThroughStub(asm, "invokeHandle", addr, actor);
        return finishTemplate(asm, addr, "invokehandle");
    }

    @HOSTED_ONLY
    private XirPair buildInvokeStatic() {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved invokestatic template
            asm.restart();
            XirParameter addr = asm.createConstantInputParameter("addr", WordUtil.archKind());
            resolved = finishTemplate(asm, addr, "invokestatic");
        }
        {
            // unresolved invokestatic template
            asm.restart();
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
            callRuntimeThroughStub(asm, "resolveStaticMethod", addr, guard);
            unresolved = finishTemplate(asm, addr, "invokestatic-unresolved");
        }
        return new XirPair(resolved, unresolved);
    }

    private InvokeSpecialTemplates buildInvokeSpecial() {
        XirTemplate resolved;
        XirTemplate resolvedNullCheckEliminated;
        XirTemplate unresolved;
        {
            // resolved case
            asm.restart();
            XirParameter addr = asm.createConstantInputParameter("addr", WordUtil.archKind()); // address to call
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            asm.nullCheck(receiver);
            resolved = finishTemplate(asm, addr, "invokespecial");
        }
        {
            // resolved case, null pointer check eliminated
            asm.restart();
            XirParameter addr = asm.createConstantInputParameter("addr", WordUtil.archKind()); // address to call
            resolvedNullCheckEliminated = finishTemplate(asm, addr, "invokespecial-nce");
        }
        {
            // unresolved invokespecial template
            asm.restart();
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
            callRuntimeThroughStub(asm, "resolveSpecialMethod", addr, guard);
            unresolved = finishTemplate(asm, addr, "invokespecial-unresolved");
        }
        return new InvokeSpecialTemplates(resolved, unresolved, resolvedNullCheckEliminated);
    }

    @HOSTED_ONLY
    private XirPair buildInvokeInterface() {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved invokeinterface
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter interfaceID = asm.createConstantInputParameter("interfaceID", CiKind.Int);
            XirParameter methodIndex = asm.createConstantInputParameter("methodIndex", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand mtableLengthOrStartIndex = asm.createTemp("mtableLength/StartIndex", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableLength()), false);
            asm.mod(a, interfaceID, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.add(a, a, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.add(a, a, methodIndex);
            XirOperand result = asm.createTemp("result", WordUtil.archKind());
            asm.pload(WordUtil.archKind(), result, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            resolved = finishTemplate(asm, result, "invokeinterface");
        }
        {
            // unresolved invokeinterface
            // TODO This uses seven registers, combined with lots of parameters this can lead to heavy spilling.
            // Some of the temps could be reused if there was a way to use them as another CiKind.
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter guard = asm.createInputParameter("guard", CiKind.Object); // guard
            XirOperand interfaceID = asm.createTemp("interfaceID", CiKind.Int);
            XirOperand methodIndex = asm.createTemp("methodIndex", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);

            callRuntimeThroughStub(asm, "resolveInterfaceMethod", methodIndex, guard);
            callRuntimeThroughStub(asm, "resolveInterfaceID", interfaceID, guard);
            XirOperand mtableLengthOrStartIndex = asm.createTemp("mtableLength/StartIndex", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableLength()), false);
            asm.mod(a, interfaceID, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.add(a, a, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.add(a, a, methodIndex);
            XirOperand result = asm.createTemp("result", WordUtil.archKind());
            asm.pload(WordUtil.archKind(), result, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            unresolved = finishTemplate(asm, result, "invokeinterface");
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildInvokeVirtual() {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved invokevirtual
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object);
            XirParameter vtableOffset = asm.createConstantInputParameter("vtableOffset", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(WordUtil.archKind(), addr, hub, vtableOffset, false);
            resolved = finishTemplate(asm, addr, "invokevirtual");
        }
        {
            // unresolved invokevirtual template
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand vtableOffset = asm.createTemp("vtableOffset", CiKind.Int);
            callRuntimeThroughStub(asm, "resolveVirtualMethod", vtableOffset, guard);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(WordUtil.archKind(), addr, hub, vtableOffset, false);
            unresolved = finishTemplate(asm, addr, "invokevirtual-unresolved");
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildLinkToStatic() {
        asm.restart();
        XirParameter memberName = asm.createInputParameter("memberName", CiKind.Object);
        XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
        callRuntimeThroughStub(asm, "linkToStatic", addr, memberName);
        return finishTemplate(asm, addr, "linktostatic");
    }

    @HOSTED_ONLY
    private XirTemplate buildLinkToInterface() {
        asm.restart();
        XirParameter memberName = asm.createInputParameter("memberName", CiKind.Object);
        XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object);
        XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
        callRuntimeThroughStub(asm, "linkToInterface", addr, memberName, receiver);
        return finishTemplate(asm, addr, "linktointerface");
    }

    @HOSTED_ONLY
    private XirTemplate buildLinkToSpecial() {
        asm.restart();
        XirParameter memberName = asm.createInputParameter("memberName", CiKind.Object);
        XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
        callRuntimeThroughStub(asm, "linkToSpecial", addr, memberName);
        return finishTemplate(asm, addr, "linktospecial");
    }

    @HOSTED_ONLY
    private XirTemplate buildLinkToVirtual() {
        asm.restart();
        XirParameter memberName = asm.createInputParameter("memberName", CiKind.Object);
        XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object);
        XirOperand addr = asm.createTemp("addr", WordUtil.archKind());
        callRuntimeThroughStub(asm, "linkToVirtual", addr, memberName, receiver);
        return finishTemplate(asm, addr, "linktovirtual");
    }

    private void alignArraySize(XirParameter length, XirOperand arraySize, int elemSize, Scale scale) {
        if (Platform.target().arch.isARM()) {
            XirOperand scratch = asm.createRegisterTemp("scratch", WordUtil.archKind(), ARMV7.r8);
            XirLabel aligned = asm.createInlineLabel("aligned");
            asm.mov(arraySize, asm.i(arrayLayout().headerSize()));
            asm.lea(arraySize, arraySize, length, 0, scale);
            asm.and(scratch, arraySize, asm.i(vmConfig().heapScheme().objectAlignment() - 1));
            asm.jeq(aligned, scratch, asm.i(0));
            asm.mov(arraySize, asm.i(arrayLayout().headerSize() + minObjectAlignmentMask()));
            asm.lea(arraySize, arraySize, length, 0, scale);
            asm.and(arraySize, arraySize, asm.i(~minObjectAlignmentMask()));
            asm.bindInline(aligned);
        } else {
            assert Platform.target().arch.isAarch64() || Platform.target().arch.isX86() || target().arch.isRISCV64() : "Arch unimplemented!";
            if (elemSize == vmConfig().heapScheme().objectAlignment()) {
                // Assumed here that header size is already aligned.buildTLABAllocateArray
                asm.mov(arraySize, asm.i(arrayLayout().headerSize()));
                asm.lea(arraySize, arraySize, length, 0, scale);
            } else {
                // Very x86 / x64 way of doing alignment.
                asm.mov(arraySize, asm.i(arrayLayout().headerSize() + minObjectAlignmentMask()));
                asm.lea(arraySize, arraySize, length, 0, scale);
                asm.and(arraySize, arraySize, asm.i(~minObjectAlignmentMask()));
            }
        }
    }

    @HOSTED_ONLY
    private XirTemplate buildTLABAllocateArrayIn(CiKind kind, XirOperand result, XirOperand hub) {
        XirParameter length = asm.createInputParameter("length", CiKind.Int);
        XirOperand arraySize = asm.createTemp("arraySize", CiKind.Int);
        XirOperand cell = asm.createTemp("cell",  WordUtil.archKind());

        XirLabel done = asm.createInlineLabel("done");
        XirLabel ok = asm.createInlineLabel("ok");
        XirLabel reportNegativeIndexError = asm.createOutOfLineLabel("indexError");

        XirOperand tla = asm.createRegisterTemp("TLA", WordUtil.archKind(), LATCH_REGISTER);
        XirOperand etla = asm.createTemp("ETLA", WordUtil.archKind());
        XirOperand tlabEnd = asm.createTemp("tlabEnd", WordUtil.archKind());
        XirOperand newMark = asm.createTemp("newMark", WordUtil.archKind());

        XirConstant offsetToTLABMark = asm.i(HeapSchemeWithTLAB.TLAB_MARK.offset);
        XirConstant offsetToTLABEnd = asm.i(HeapSchemeWithTLAB.TLAB_TOP.offset);

        asm.jlt(reportNegativeIndexError, length, asm.i(0));
        asm.pload(WordUtil.archKind(), etla, tla, asm.i(VmThreadLocal.ETLA.offset), false);

        int elemSize = target().sizeInBytes(kind);
        Scale scale = Scale.fromInt(elemSize);
        alignArraySize(length, arraySize, elemSize, scale);

        asm.pload(WordUtil.archKind(), cell, etla, offsetToTLABMark, false);
        asm.pload(WordUtil.archKind(), tlabEnd, etla, offsetToTLABEnd, false);
        asm.add(newMark, cell, arraySize);
        asm.jlteq(ok, newMark, tlabEnd);

        callRuntimeThroughStub(asm, "slowPathAllocate", cell, arraySize, etla);
        asm.jmp(done);

        asm.bindInline(ok);
        asm.pstore(WordUtil.archKind(), etla, offsetToTLABMark, newMark, false);
        if (MaxineVM.isDebug()) {
            buildTLABLogging(etla, arraySize, cell);
        }
        asm.bindInline(done);
        // Now, plant the hub to properly format the allocated cell as an object.
        asm.pstore(CiKind.Object, cell, asm.i(hubOffset()), hub, false);
        asm.pstore(CiKind.Int, cell, asm.i(arrayLayout().arrayLengthOffset()), length, false);
        asm.mov(result, cell);

        callRuntimeThroughStub(asm, "callProfilerArray", null, arraySize, hub, cell);

        asm.bindOutOfLine(reportNegativeIndexError);
        callRuntimeThroughStub(asm, "throwNegativeArraySizeException", null, length);

        return finishTemplate(asm, (kind.isObject() ? "a" : "") + "newarray<" + kind + ">");
    }

    @HOSTED_ONLY
    private XirTemplate buildTLABAllocateArray(CiKind kind, XirOperand result, XirOperand hub) {
        XirParameter length = asm.createInputParameter("length", CiKind.Int);
        XirOperand arraySize = asm.createTemp("arraySize", CiKind.Int);
        XirOperand cell = asm.createTemp("cell",  WordUtil.archKind());

        XirLabel done = asm.createInlineLabel("done");
        XirLabel slowPath = asm.createOutOfLineLabel("slowPath");
        XirLabel reportNegativeIndexError = asm.createOutOfLineLabel("indexError");

        XirOperand tla = asm.createRegisterTemp("TLA", WordUtil.archKind(), this.LATCH_REGISTER);
        XirOperand etla = asm.createTemp("ETLA", WordUtil.archKind());
        XirOperand tlabEnd = asm.createTemp("tlabEnd", WordUtil.archKind());
        XirOperand newMark = asm.createTemp("newMark", WordUtil.archKind());

        XirConstant offsetToTLABMark = asm.i(HeapSchemeWithTLAB.TLAB_MARK.offset);
        XirConstant offsetToTLABEnd = asm.i(HeapSchemeWithTLAB.TLAB_TOP.offset);

        asm.jlt(reportNegativeIndexError, length, asm.i(0));
        asm.pload(WordUtil.archKind(), etla, tla, asm.i(VmThreadLocal.ETLA.offset), false);

        int elemSize = target().sizeInBytes(kind);
        Scale scale = Scale.fromInt(elemSize);
        alignArraySize(length, arraySize, elemSize, scale);

        asm.pload(WordUtil.archKind(), cell, etla, offsetToTLABMark, false);
        asm.pload(WordUtil.archKind(), tlabEnd, etla, offsetToTLABEnd, false);

        asm.add(newMark, cell, arraySize);
        asm.jgt(slowPath, newMark, tlabEnd);
        asm.pstore(WordUtil.archKind(), etla, offsetToTLABMark, newMark, false);
        if (MaxineVM.isDebug()) {
            buildTLABLogging(etla, arraySize, cell);
        }
        asm.bindInline(done);
        // Now, plant the hub to properly format the allocated cell as an object.
        asm.pstore(CiKind.Object, cell, asm.i(hubOffset()), hub, false);
        asm.pstore(CiKind.Int, cell, asm.i(arrayLayout().arrayLengthOffset()), length, false);
        asm.mov(result, cell);

        callRuntimeThroughStub(asm, "callProfiler", null, arraySize, hub, cell);

        asm.bindOutOfLine(reportNegativeIndexError);
        callRuntimeThroughStub(asm, "throwNegativeArraySizeException", null, length);

        asm.bindOutOfLine(slowPath);
        callRuntimeThroughStub(asm, "slowPathAllocate", cell, arraySize, etla);
        asm.jmp(done);
        return finishTemplate(asm, (kind.isObject() ? "a" : "") + "newarray<" + kind + ">");
    }

    @HOSTED_ONLY
    private XirTemplate buildTLABAllocateArray(CiKind kind) {
        XirOperand result = asm.restart(CiKind.Object);
        XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
        return Heap.useOutOfLineStubs ?
                        buildTLABAllocateArray(kind, result, hub) :
                            buildTLABAllocateArrayIn(kind, result, hub);
    }

    @HOSTED_ONLY
    private XirPair buildTLABNewArray(CiKind kind) {
        XirTemplate resolved;
        XirTemplate unresolved;
        if (kind == CiKind.Object) {
            resolved = buildTLABAllocateArray(kind);
            // unresolved new object array
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "resolveNewArray", hub, guard);
            unresolved = buildTLABAllocateArray(kind, result, hub);
        } else {
            resolved = buildTLABAllocateArray(kind);
            unresolved = resolved;
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildNewArray(CiKind kind) {
        XirTemplate resolved;
        XirTemplate unresolved;
        if (kind == CiKind.Object) {
            {
                // resolved new object array
                XirOperand result = asm.restart(CiKind.Object);
                XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
                XirParameter length = asm.createInputParameter("length", CiKind.Int);
                callRuntimeThroughStub(asm, "allocateObjectArray", result, hub, length);
                resolved = finishTemplate(asm, "anewarray<" + kind + ">");
            }
            {
                // unresolved new object array
                XirOperand result = asm.restart(CiKind.Object);
                XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
                XirParameter length = asm.createInputParameter("length", CiKind.Int);
                XirOperand hub = asm.createTemp("hub", CiKind.Object);
                callRuntimeThroughStub(asm, "resolveNewArray", hub, guard);
                callRuntimeThroughStub(asm, "allocateObjectArray", result, hub, length);
                unresolved = finishTemplate(asm, "anewarray<" + kind + ">-unresolved");
            }

        } else {
            // XXX: specialized, inline templates for each kind
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            XirParameter length = asm.createInputParameter("length", CiKind.Int);
            callRuntimeThroughStub(asm, "allocatePrimitiveArray", result, hub, length);
            resolved = finishTemplate(asm, "newarray<" + kind + ">");
            unresolved = resolved;
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildNewMultiArray(int rank) {
        XirTemplate resolved = null;
        XirTemplate unresolved;
        if (rank < SMALL_MULTIANEWARRAY_RANK) {
            // "small" resolved multianewarray (rank 3 or less)
            XirOperand result = asm.restart(CiKind.Object);
            XirOperand[] lengths = new XirOperand[rank];
            for (int i = 0; i < rank; i++) {
                lengths[i] = asm.createInputParameter("lengths[" + i + "]", CiKind.Int);
            }
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "allocateMultiArray" + rank, result, Utils.prepend(lengths, hub));
            resolved = finishTemplate(asm, "multianewarray<" + rank + ">");
        }

        // unresolved or large multianewarray
        XirOperand result = asm.restart(CiKind.Object);
        XirOperand[] lengths = new XirOperand[rank];
        for (int i = 0; i < rank; i++) {
            lengths[i] = asm.createInputParameter("lengths[" + i + "]", CiKind.Int);
        }
        XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
        XirOperand lengthArray = asm.createTemp("lengthArray", CiKind.Object);
        callRuntimeThroughStub(asm, "allocateIntArray", lengthArray, asm.i(rank));
        for (int i = 0; i < rank; i++) {
            asm.pstore(CiKind.Int, lengthArray, asm.i(offsetOfFirstArrayElement() + (i * 4)), lengths[i], false);
        }
        callRuntimeThroughStub(asm, "allocateMultiArrayN", result, guard, lengthArray);
        unresolved = finishTemplate(asm, "multianewarray-complex<" + rank + ">");

        return new XirPair(resolved == null ? unresolved : resolved, unresolved);
    }

    // Version that doesn't use out-of-line stub.
    @HOSTED_ONLY
    private void buildTLABAllocateIn(boolean isHybrid, XirOperand result, XirOperand hub, XirOperand tupleSize) {
        XirOperand cell = asm.createTemp("cell",  WordUtil.archKind());
        XirLabel done = asm.createInlineLabel("done");
        XirLabel ok = asm.createInlineLabel("slowPath");
        XirOperand tla = asm.createRegisterTemp("TLA", WordUtil.archKind(), this.LATCH_REGISTER);
        XirOperand etla = asm.createTemp("ETLA", WordUtil.archKind());
        XirOperand tlabEnd = asm.createTemp("tlabEnd", WordUtil.archKind());
        XirOperand newMark = asm.createTemp("newMark", WordUtil.archKind());

        XirConstant offsetToTLABMark = asm.i(HeapSchemeWithTLAB.TLAB_MARK.offset);
        XirConstant offsetToTLABEnd = asm.i(HeapSchemeWithTLAB.TLAB_TOP.offset);
        asm.pload(WordUtil.archKind(), etla, tla, asm.i(VmThreadLocal.ETLA.offset), false);
        asm.pload(WordUtil.archKind(), cell, etla, offsetToTLABMark, false);
        asm.pload(WordUtil.archKind(), tlabEnd, etla, offsetToTLABEnd, false);

        asm.add(newMark, cell, tupleSize);
        asm.jlteq(ok, newMark, tlabEnd);
        // Slow path.
        callRuntimeThroughStub(asm, "slowPathAllocate", cell, tupleSize, etla);
        // Jump over update of TLAB mark and go directly to code formatting the allocated cell.
        asm.jmp(done);
        asm.bindInline(ok);
        asm.pstore(WordUtil.archKind(), etla, offsetToTLABMark, newMark, false);
        if (MaxineVM.isDebug()) {
            buildTLABLogging(etla, tupleSize, cell);
        }
        asm.bindInline(done);
        // Now, plant the hub to properly format the allocated cell as an object.
        asm.pstore(CiKind.Object, cell, asm.i(hubOffset()), hub, false);
        if (isHybrid) {
            asm.pstore(CiKind.Int, cell, asm.i(arrayLayout().arrayLengthOffset()), asm.i(hubFirstWordIndex()), false);
        }
        asm.mov(result, cell);

        callRuntimeThroughStub(asm, "callProfiler", null, tupleSize, hub, cell);
    }

    @HOSTED_ONLY
    private void buildTLABLogging(XirOperand etla, XirOperand cellSize, XirOperand cell) {
        XirLabel flushLog = asm.createOutOfLineLabel("flushLog");
        XirLabel recordInLog = asm.createInlineLabel("recordInLog");
        XirLabel done = asm.createInlineLabel("done");
        XirOperand logTail = asm.createTemp("logTail",  WordUtil.archKind());
        XirOperand logEndMark = asm.createTemp("logEndMark",  WordUtil.archKind());
        XirOperand allocationSite = asm.createTemp("allocationSite",  WordUtil.archKind());
        XirConstant offsetToTLABLogTail = asm.i(TLABLog.TLAB_LOG_TAIL.offset);

        asm.pload(WordUtil.archKind(), logTail, etla, offsetToTLABLogTail, false);
        asm.jeq(done, logTail, asm.i(0));
        asm.pload(WordUtil.archKind(), logEndMark, logTail, false);
        asm.jeq(flushLog, logEndMark, logTail);
        asm.bindInline(recordInLog);
        asm.here(allocationSite);
        asm.pstore(WordUtil.archKind(), logTail, asm.i(0), allocationSite, false);
        asm.pstore(WordUtil.archKind(), logTail, asm.i(Word.size()), cell, false);
        asm.pstore(WordUtil.archKind(), logTail, asm.i(2 * Word.size()), cellSize, false);
        asm.add(logTail, logTail, asm.i(3 * Word.size()));
        asm.pstore(WordUtil.archKind(), etla, offsetToTLABLogTail, logTail, false);
        asm.bindInline(done);
        asm.bindOutOfLine(flushLog);
        callRuntimeThroughStub(asm, "flushLog", logTail, logTail);
        asm.jmp(recordInLog);
    }

    @HOSTED_ONLY
    private void buildTLABAllocate(boolean isHybrid, XirOperand result, XirOperand hub, XirOperand tupleSize) {
        XirOperand cell = asm.createTemp("cell",  WordUtil.archKind());
        XirLabel done = asm.createInlineLabel("done");
        XirLabel slowPath = asm.createOutOfLineLabel("slowPath");
        XirOperand tla = asm.createRegisterTemp("TLA", WordUtil.archKind(), this.LATCH_REGISTER);
        XirOperand etla = asm.createTemp("ETLA", WordUtil.archKind());
        XirOperand tlabEnd = asm.createTemp("tlabEnd", WordUtil.archKind());
        XirOperand newMark = asm.createTemp("newMark", WordUtil.archKind());
        XirOperand debugTemp = null;
        if (MaxineVM.isDebug()) {
            debugTemp = asm.createTemp("debugTemp",  WordUtil.archKind());
        }
        XirConstant offsetToTLABMark = asm.i(HeapSchemeWithTLAB.TLAB_MARK.offset);
        XirConstant offsetToTLABEnd = asm.i(HeapSchemeWithTLAB.TLAB_TOP.offset);
        asm.pload(WordUtil.archKind(), etla, tla, asm.i(VmThreadLocal.ETLA.offset), false);
        asm.pload(WordUtil.archKind(), cell, etla, offsetToTLABMark, false);
        asm.pload(WordUtil.archKind(), tlabEnd, etla, offsetToTLABEnd, false);

        asm.add(newMark, cell, tupleSize);
        asm.jgt(slowPath, newMark, tlabEnd);
        asm.pstore(WordUtil.archKind(), etla, offsetToTLABMark, newMark, false);
        if (MaxineVM.isDebug()) {
            buildTLABLogging(etla, tupleSize, cell);
        }
        asm.bindInline(done);
        if (MaxineVM.isDebug()) {
            asm.pload(WordUtil.archKind(), debugTemp, hub, false);
            asm.pload(WordUtil.archKind(), debugTemp, debugTemp, false);
        }
        // Now, plant the hub to properly format the allocated cell as an object.
        asm.pstore(CiKind.Object, cell, asm.i(hubOffset()), hub, false);
        if (isHybrid) {
            asm.pstore(CiKind.Int, cell, asm.i(arrayLayout().arrayLengthOffset()), asm.i(hubFirstWordIndex()), false);
        }
        asm.mov(result, cell);

        callRuntimeThroughStub(asm, "callProfiler", null, tupleSize, hub, cell);

        asm.bindOutOfLine(slowPath);
        callRuntimeThroughStub(asm, "slowPathAllocate", cell, tupleSize, etla);
        asm.jmp(done);
    }

    @HOSTED_ONLY
    private XirTemplate buildTLABAllocate(String templateName, boolean isHybrid) {
        XirOperand result = asm.restart(CiKind.Object);
        XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
        XirParameter tupleSize = asm.createConstantInputParameter("tupleSize", CiKind.Int);
        if (Heap.useOutOfLineStubs) {
            buildTLABAllocate(isHybrid, result, hub, tupleSize);
        } else {
            buildTLABAllocateIn(isHybrid, result, hub, tupleSize);
        }
        return finishTemplate(asm, templateName);
    }

    @HOSTED_ONLY
    private NewInstanceTemplates buildTLABNewInstance() {
        XirTemplate resolved = buildTLABAllocate("new", false);
        XirTemplate resolvedHybrid = buildTLABAllocate("newHybrid", true);
        XirTemplate unresolved;
        {
            // unresolved new instance
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "resolveNew", hub, guard);

            XirOperand tupleSize = asm.createTemp("tupleSize", CiKind.Int);
            asm.pload(CiKind.Int, tupleSize, hub, asm.i(offsetOfTupleSize()), false);
            if (Heap.useOutOfLineStubs) {
                buildTLABAllocate(false, result, hub, tupleSize);
            } else {
                buildTLABAllocateIn(false, result, hub, tupleSize);
            }
            unresolved = finishTemplate(asm, "new-unresolved");
        }
        return new NewInstanceTemplates(resolved, resolvedHybrid, unresolved);
    }

    @HOSTED_ONLY
    private NewInstanceTemplates buildNewInstance() {
        XirTemplate resolved;
        XirTemplate resolvedHybrid;
        XirTemplate unresolved;
        {
            // resolved new instance
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            //XirParameter tupleSize = asm.createConstantInputParameter("tupleSize", CiKind.Int);

            callRuntimeThroughStub(asm, "allocateObject", result, hub);
            resolved = finishTemplate(asm, "new");
        }
        {
            // resolved new hybrid
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            //XirParameter tupleSize = asm.createConstantInputParameter("tupleSize", CiKind.Int);
            callRuntimeThroughStub(asm, "allocateHybrid", result, hub);
            resolvedHybrid = finishTemplate(asm, "newHybrid");
        }
        {
            // unresolved new instance
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "resolveNew", hub, guard);
            callRuntimeThroughStub(asm, "allocateObject", result, hub);
            unresolved = finishTemplate(asm, "new-unresolved");
        }

        return new NewInstanceTemplates(resolved, resolvedHybrid, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildPutFieldTemplates(CiKind kind, boolean genWriteBarrier, boolean isStatic) {
        return new XirPair(buildPutFieldTemplate(kind, genWriteBarrier, isStatic, true), buildPutFieldTemplate(kind, genWriteBarrier, isStatic, false));
    }

    @HOSTED_ONLY
    private XirTemplate buildPutFieldTemplate(CiKind kind, boolean genWriteBarrier, boolean isStatic, boolean resolved) {
        XirWriteBarrierSpecification writeBarrierSpecification = writeBarrierSpecification();

        XirTemplate xirTemplate;
        if (resolved) {
            // resolved case
            asm.restart(CiKind.Void);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter value = asm.createInputParameter("value", kind);
            XirParameter fieldOffset = asm.createConstantInputParameter("fieldOffset", CiKind.Int);
            if (genWriteBarrier) {
                writeBarrierSpecification.barrierGenerator(WriteBarrierSpecification.TUPLE_PRE_BARRIER).genWriteBarrier(asm, object);
            }
            asm.pstore(kind, object, fieldOffset, value, true);
            if (genWriteBarrier) {
                writeBarrierSpecification.barrierGenerator(WriteBarrierSpecification.TUPLE_POST_BARRIER).genWriteBarrier(asm, object);
            }
            xirTemplate = finishTemplate(asm, "putfield<" + kind + ", " + genWriteBarrier + ">");
        } else {
            // unresolved case
            asm.restart(CiKind.Void);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter value = asm.createInputParameter("value", kind);
            XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
            XirOperand fieldOffset = asm.createTemp("fieldOffset", CiKind.Int);
            if (isStatic) {
                callRuntimeThroughStub(asm, "resolvePutStatic", fieldOffset, guard);
            } else {
                callRuntimeThroughStub(asm, "resolvePutField", fieldOffset, guard);
            }
            asm.pstore(kind, object, fieldOffset, value, true);
            if (genWriteBarrier) {
                writeBarrier(asm, object, null, value);
            }
            xirTemplate = finishTemplate(asm, "putfield<" + kind + ", " + genWriteBarrier + ">-unresolved");
        }
        return xirTemplate;
    }

    @HOSTED_ONLY
    private XirPair buildGetFieldTemplates(CiKind kind, boolean isStatic) {
        return new XirPair(buildGetFieldTemplate(kind, isStatic, true), buildGetFieldTemplate(kind, isStatic, false));
    }

    @HOSTED_ONLY
    private XirTemplate buildGetFieldTemplate(CiKind kind, boolean isStatic, boolean resolved) {
        XirTemplate xirTemplate;
        if (resolved) {
            // resolved case
            XirOperand result = asm.restart(kind);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter fieldOffset = asm.createConstantInputParameter("fieldOffset", CiKind.Int);
            asm.pload(kind, result, object, fieldOffset, true);
            xirTemplate = finishTemplate(asm, "getfield<" + kind + ">");
        } else {
            // unresolved case
            XirOperand result = asm.restart(kind);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
            XirOperand fieldOffset = asm.createTemp("fieldOffset", CiKind.Int);
            if (isStatic) {
                callRuntimeThroughStub(asm, "resolveGetStatic", fieldOffset, guard);
            } else {
                callRuntimeThroughStub(asm, "resolveGetField", fieldOffset, guard);
            }
            asm.pload(kind, result, object, fieldOffset, true);
            xirTemplate = finishTemplate(asm, "getfield<" + kind + ">-unresolved");
        }
        return xirTemplate;
    }

    @HOSTED_ONLY
    private XirTemplate buildMonitorExit() {
        asm.restart(CiKind.Void);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        callRuntimeThroughStub(asm, "monitorExit", null, object);
        return finishTemplate(asm, "monitorexit");
    }

    @HOSTED_ONLY
    private XirTemplate buildMonitorEnter() {
        asm.restart(CiKind.Void);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        callRuntimeThroughStub(asm, "monitorEnter", null, object);
        return finishTemplate(asm, "monitorenter");
    }

    @HOSTED_ONLY
    private XirPair buildCheckcastForLeaf(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved checkcast for a leaf class
            asm.restart();
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter checkedHub = asm.createConstantInputParameter("checkedHub", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirLabel pass = asm.createInlineLabel("pass");
            XirLabel fail = asm.createOutOfLineLabel("fail");
            if (!nonnull) {
                // first check against null
                asm.jeq(pass, object, asm.o(null));
            }
            asm.pload(CiKind.Object, hub, object, asm.i(hubOffset()), !nonnull);
            asm.jneq(fail, hub, checkedHub);
            asm.bindInline(pass);
            asm.bindOutOfLine(fail);
            callRuntimeThroughStub(asm, "throwClassCastException", null, checkedHub, object);
            resolved = finishTemplate(asm, object, "checkcast-leaf<" + nonnull + ">");
        }
        {
            // unresolved checkcast
            unresolved = buildUnresolvedCheckcast(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildCheckcastForNonLeaf(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved checkcast against an interface class
            asm.restart();
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter interfaceID = asm.createConstantInputParameter("interfaceID", CiKind.Int);
            XirParameter checkedHub = asm.createConstantInputParameter("checkedHub", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand mtableTemp = asm.createTemp("mtableTemp", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            XirLabel pass = asm.createInlineLabel("pass");
            XirLabel fail = asm.createOutOfLineLabel("fail");
            // XXX: use a cache to check the last successful receiver type
            if (!nonnull) {
                // first check for null
                asm.jeq(pass, object, asm.o(null));
            }
            asm.pload(CiKind.Object, hub, object, asm.i(hubOffset()), !nonnull);
            asm.jeq(pass, hub, checkedHub);
            asm.pload(CiKind.Int, mtableTemp, hub, asm.i(offsetOfMTableLength()), false);
            asm.mod(a, interfaceID, mtableTemp);
            asm.pload(CiKind.Int, mtableTemp, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.add(a, a, mtableTemp);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            asm.jneq(fail, a, interfaceID);
            asm.bindInline(pass);
            asm.bindOutOfLine(fail);
            callRuntimeThroughStub(asm, "throwClassCastException", null, checkedHub, object);
            resolved = finishTemplate(asm, object, "checkcast-interface<" + nonnull + ">");
        }
        {
            unresolved = buildUnresolvedCheckcast(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildUnresolvedCheckcast(boolean nonnull) {
        asm.restart();
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
        XirLabel pass = asm.createInlineLabel("pass");
        if (!nonnull) {
            // XXX: build a version that does not include a null check
            asm.jeq(pass, object, asm.o(null));
        }
        callRuntimeThroughStub(asm, "unresolvedCheckcast", null, object, guard);
        asm.bindInline(pass);
        return finishTemplate(asm, object, "checkcast-unresolved<" + nonnull + ">");
    }

    @HOSTED_ONLY
    private XirPair buildInstanceofForLeaf(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            asm.restart(CiKind.Void);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            XirOperand temp = asm.createTemp("temp", CiKind.Object);

            XirLabel trueSucc = asm.createInlineLabel(XirLabel.TrueSuccessor);
            XirLabel falseSucc = asm.createInlineLabel(XirLabel.FalseSuccessor);

            if (!nonnull) {
                // first check for null
                asm.jeq(falseSucc, object, asm.o(null));
            }
            asm.pload(CiKind.Object, temp, object, asm.i(hubOffset()), !nonnull);
            asm.jeq(trueSucc, temp, hub);

            asm.jmp(falseSucc);

            resolved = finishTemplate(asm, "instanceof-leaf<" + nonnull + ">");
        }
        {
            // unresolved instanceof
            unresolved = buildUnresolvedInstanceOf(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildMaterializeInstanceOf(boolean nonnull, boolean leaf) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            XirOperand result = asm.restart(CiKind.Int);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirOperand checkedHub = asm.createConstantInputParameter("hub", CiKind.Object);
            XirOperand trueValue = asm.createConstantInputParameter("trueValue", CiKind.Int);
            XirOperand falseValue = asm.createConstantInputParameter("falseValue", CiKind.Int);
            XirOperand objHub = asm.createTemp("objHub", CiKind.Object);
            XirLabel trueSucc = asm.createInlineLabel("ok");
            XirLabel falseSucc = asm.createInlineLabel("notOk");
            XirLabel end = asm.createInlineLabel("end");

            if (!nonnull) {
                // null isn't "instanceof" anything
                asm.jeq(falseSucc, object, asm.o(null));
            }

            asm.pload(CiKind.Object, objHub, object, asm.i(hubOffset()), false);
            // if we get an exact match: succeed immediately
            if (!leaf) {
                XirOperand mtableTemp = asm.createTemp("mtableTemp", CiKind.Int);
                XirOperand a = asm.createTemp("a", CiKind.Int);
                XirParameter typeID = asm.createConstantInputParameter("typeID", CiKind.Int);
                asm.jeq(trueSucc, objHub, checkedHub);
                asm.pload(CiKind.Int, mtableTemp, objHub, asm.i(offsetOfMTableLength()), false);
                asm.mod(a, typeID, mtableTemp);
                asm.pload(CiKind.Int, mtableTemp, objHub, asm.i(offsetOfMTableStartIndex()), false);
                asm.add(a, a, mtableTemp);
                asm.pload(CiKind.Int, a, objHub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
                asm.pload(CiKind.Int, a, objHub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
                asm.jneq(falseSucc, a, typeID);
            } else {
                asm.jneq(falseSucc, objHub, checkedHub);
            }
            asm.bindInline(trueSucc);
            asm.mov(result, trueValue);
            asm.jmp(end);

            asm.bindInline(falseSucc);
            asm.mov(result, falseValue);

            asm.bindInline(end);
            resolved = finishTemplate(asm, "materializeInstanceOf<" + nonnull + ", " + leaf + ">");
        }
        {
            // unresolved materializeInstanceOf
            unresolved = buildUnresolvedMaterializeInstanceOf(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildUnresolvedMaterializeInstanceOf(boolean nonnull) {
        XirTemplate unresolved;
        XirOperand result = asm.restart(CiKind.Int);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        XirOperand guard = asm.createConstantInputParameter("hub", CiKind.Object);
        XirOperand trueValue = asm.createConstantInputParameter("trueValue", CiKind.Int);
        XirOperand falseValue = asm.createConstantInputParameter("falseValue", CiKind.Int);
        XirLabel falseSucc = asm.createInlineLabel("notOk");
        XirLabel end = asm.createInlineLabel("end");

        if (!nonnull) {
            // null isn't "instanceof" anything
            asm.jeq(falseSucc, object, asm.o(null));
        }
        callRuntimeThroughStub(asm, "unresolvedInstanceOf", result, object, guard);
        asm.jeq(falseSucc, result, asm.b(false));

        asm.mov(result, trueValue);
        asm.jmp(end);

        asm.bindInline(falseSucc);
        asm.mov(result, falseValue);

        asm.bindInline(end);
        unresolved = finishTemplate(asm, "materializeInstanceOf-unresolved<" + nonnull + ">");
        return unresolved;
    }

    @HOSTED_ONLY
    private XirTemplate buildTypeAssert() {
        asm.restart();
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        XirOperand hub = asm.createConstantInputParameter("hub", CiKind.Object);

        XirOperand objHub = asm.createTemp("objHub", CiKind.Object);
        XirLabel slowPath = asm.createOutOfLineLabel("deopt");

        asm.pload(CiKind.Object, objHub, object, asm.i(hubOffset()), false);
        // if we get an exact match: continue
        asm.jneq(slowPath, objHub, hub);

        // -- out of line -------------------------------------------------------
        asm.bindOutOfLine(slowPath);
        asm.callRuntime(CiRuntimeCall.Deoptimize, null);
        asm.shouldNotReachHere();

        return asm.finishTemplate(object, "typeCheck");
    }

    @HOSTED_ONLY
    private XirPair buildInstanceofForNonLeaf(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved instanceof for an interface or non-leaf class type
            asm.restart(CiKind.Void);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter typeID = asm.createConstantInputParameter("typeID", CiKind.Int);
            XirParameter checkedHub = asm.createConstantInputParameter("checkedHub", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand mtableLength = asm.createTemp("mtableLength", CiKind.Int);
            XirOperand mtableStartIndex = asm.createTemp("mtableStartIndex", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);

            XirLabel trueSucc = asm.createInlineLabel(XirLabel.TrueSuccessor);
            XirLabel falseSucc = asm.createInlineLabel(XirLabel.FalseSuccessor);

            // XXX: use a cache to check the last successful receiver type
            if (!nonnull) {
                // first check for null
                asm.jeq(falseSucc, object, asm.o(null));
            }
            asm.pload(CiKind.Object, hub, object, asm.i(hubOffset()), !nonnull);
            asm.jeq(trueSucc, hub, checkedHub);
            asm.pload(CiKind.Int, mtableLength, hub, asm.i(offsetOfMTableLength()), false);
            asm.pload(CiKind.Int, mtableStartIndex, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.mod(a, typeID, mtableLength);
            asm.add(a, a, mtableStartIndex);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            asm.jeq(trueSucc, a, typeID);

            asm.jmp(falseSucc);
            resolved = finishTemplate(asm, "instanceof-interface<" + nonnull + ">");
        }
        {
            // unresolved instanceof
            unresolved = buildUnresolvedInstanceOf(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildUnresolvedInstanceOf(boolean nonnull) {
        XirTemplate unresolved;
        asm.restart(CiKind.Void);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
        XirOperand result = asm.createTemp("result", CiKind.Boolean);

        XirLabel trueSucc = asm.createInlineLabel(XirLabel.TrueSuccessor);
        XirLabel falseSucc = asm.createInlineLabel(XirLabel.FalseSuccessor);

        if (!nonnull) {
            // first check failed
            asm.jeq(falseSucc, object, asm.o(null));
        }
        callRuntimeThroughStub(asm, "unresolvedInstanceOf", result, object, guard);
        asm.jeq(trueSucc, result, asm.b(true));

        asm.jmp(falseSucc);
        unresolved = finishTemplate(asm, "instanceof-unresolved<" + nonnull + ">");
        return unresolved;
    }

    @HOSTED_ONLY
    private XirTemplate buildExceptionObject() {
        XirOperand result = asm.restart(CiKind.Object);
        // Emit a safepoint
        XirOperand latch = asm.createRegisterTemp("latch", WordUtil.archKind(), this.LATCH_REGISTER);
        asm.safepoint(0);
        asm.pload(WordUtil.archKind(), latch, latch, false);

        callRuntimeThroughStub(asm, "loadException", result);
        return finishTemplate(asm, "load-exception");
    }

    @HOSTED_ONLY
    public XirTemplate finishTemplate(CiXirAssembler asm, XirOperand result, String name) {
        final XirTemplate template = asm.finishTemplate(result, name);
        if (printXirTemplates) {
            template.print(Log.out);
        }
        return template;
    }

    public XirTemplate finishTemplate(CiXirAssembler asm, String name) {
        final XirTemplate template = asm.finishTemplate(name);
        if (printXirTemplates) {
            template.print(Log.out);
        }
        return template;
    }

    private void writeBarrier(CiXirAssembler asm, XirOperand object, XirOperand fieldOffset, XirOperand value) {
        // XXX: add write barrier mechanism
    }

    @HOSTED_ONLY
    private void callRuntimeThroughStub(CiXirAssembler asm, String method, XirOperand result, XirOperand... args) {
        XirTemplate stub = runtimeCallStubs.get(method);
        if (stub == null) {
            stub = addCallRuntimeThroughStub(stubs, runtimeCalls, runtimeCallStubs, asm, method, result, args);
        }
        if (stub == null) {
            throw ProgramError.unexpected("could not find runtime call: " + method);
        }
        asm.callStub(stub, result, args);
    }

    @HOSTED_ONLY
    public XirTemplate getStub(String method) {
        return runtimeCallStubs.get(method);
    }

    @HOSTED_ONLY
    public XirTemplate addCallRuntimeThroughStub(List<XirTemplate> stubs, Class<?> runtimeCalls,
                    HashMap<String, XirTemplate> runtimeCallStubs,
                    CiXirAssembler asm, String method, XirOperand result, XirOperand... args) {
        XirTemplate stub = null;
        // search for the runtime call and create the stub
        for (Method m : runtimeCalls.getMethods()) {
            int flags = m.getModifiers();
            if (Modifier.isStatic(flags) && Modifier.isPublic(flags) && m.getName().equals(method)) {
                // runtime call found. create a compiler stub that calls the runtime method
                MethodActor methodActor = MethodActor.fromJava(m);
                SignatureDescriptor signature = methodActor.descriptor();
                if (result == null) {
                    assert signature.resultKind() == Kind.VOID;
                } else {
                    CiKind ciKind = signature.returnKind(true);
                    assert ciKind == result.kind : "return type mismatch in call to " + method;
                }

                assert signature.numberOfParameters() == args.length : "parameter mismatch in call to " + method;
                CiXirAssembler stubAsm = asm.copy();
                XirOperand resultVariable = stubAsm.restart(WordUtil.ciKind(signature.resultKind(), true));

                XirParameter[] rtArgs = new XirParameter[signature.numberOfParameters()];
                for (int i = 0; i < signature.numberOfParameters(); i++) {
                    // create a parameter for each parameter to the runtime call
                    CiKind ciKind = signature.argumentKindAt(i, true);
                    assert ciKind == args[i].kind : "type mismatch in call to " + method;
                    rtArgs[i] = stubAsm.createInputParameter("rtArgs[" + i + "]", ciKind);
                }
                stubAsm.callRuntime(methodActor, resultVariable, rtArgs);
                stub = stubAsm.finishStub("stub-" + method);

                if (printXirTemplates) {
                    stub.print(Log.out);
                }
                final XirTemplate existing = runtimeCallStubs.put(method, stub);
                assert existing == null : "stub for " + method + "redefined";
            }
        }
        stubs.add(stub);
        return stub;
    }

    private void callRuntime(CiXirAssembler asm, String method, XirOperand result, XirOperand... args) {
        // TODO: make direct runtime calls work in XIR!
        RiMethod rtMethod = runtimeMethods.get(method);
        if (rtMethod == null) {
            // search for the runtime call and create the stub
            for (Method m : runtimeCalls.getMethods()) {
                int flags = m.getModifiers();
                if (Modifier.isStatic(flags) && Modifier.isPublic(flags) && m.getName().equals(method)) {
                    // runtime call found. create a compiler stub that calls the runtime method
                    MethodActor methodActor = MethodActor.fromJava(m);
                    rtMethod = methodActor;
                    runtimeMethods.put(method, rtMethod);
                }
            }
        }
        if (rtMethod == null) {
            throw ProgramError.unexpected("could not find runtime call: " + method);
        }
        asm.callRuntime(rtMethod, result, args);
    }

    @Override
    public XirSnippet genIntrinsic(XirSite site, XirArgument[] arguments, RiMethod method) {
        return null;
    }

    @Override
    public XirSnippet genWriteBarrier(XirArgument object) {
        return null;
    }

    @Override
    public XirSnippet genArrayCopy(XirSite site, XirArgument src, XirArgument srcPos, XirArgument dest, XirArgument destPos, XirArgument length, RiType elementType, boolean inputsDifferent, boolean inputsSame) {
        return null;
    }

    @Override
    public XirSnippet genCurrentThread(XirSite site) {
        return null;
    }

    @Override
    public XirSnippet genGetClass(XirSite site, XirArgument xirArgument) {
        return null;
    }

    @Override
    public XirSnippet genNewObjectArrayClone(XirSite site, XirArgument newLength, XirArgument referenceArray) {
        return null;
    }

    public static class RuntimeCalls {
        public static ClassActor resolveClassActor(ResolutionGuard guard) {
            return Snippets.resolveClass(guard);
        }

        public static Class resolveClassObject(ResolutionGuard guard) {
            return Snippets.resolveClass(guard).javaClass();
        }

        public static Object resolveHub(ResolutionGuard guard) {
            return Snippets.resolveClass(guard).dynamicHub();
        }

        public static Object resolveNew(ResolutionGuard guard) {
            ClassActor classActor = Snippets.resolveClassForNew(guard);
            Snippets.makeClassInitialized(classActor);
            return classActor.dynamicHub();
        }

        public static Object resolveNewArray(ResolutionGuard guard) {
            return Snippets.resolveArrayClass(guard).dynamicHub();
        }

        public static int resolveGetField(ResolutionGuard.InPool guard) {
            return Snippets.resolveInstanceFieldForReading(guard).offset();
        }

        public static int resolvePutField(ResolutionGuard.InPool guard) {
            return Snippets.resolveInstanceFieldForWriting(guard).offset();
        }

        public static int resolveGetStatic(ResolutionGuard.InPool guard) {
            FieldActor fieldActor = Snippets.resolveStaticFieldForReading(guard);
            Snippets.makeHolderInitialized(fieldActor);
            return fieldActor.offset();
        }

        public static int resolvePutStatic(ResolutionGuard.InPool guard) {
            FieldActor fieldActor = Snippets.resolveStaticFieldForWriting(guard);
            Snippets.makeHolderInitialized(fieldActor);
            fieldActor.holder().makeInitialized();
            return fieldActor.offset();
        }

        public static Object resolveStaticTuple(ResolutionGuard guard) {
            ClassActor classActor = Snippets.resolveClass(guard);
            Snippets.makeClassInitialized(classActor);
            return classActor.staticTuple();
        }

        public static Word resolveStaticMethod(ResolutionGuard.InPool guard) {
            StaticMethodActor methodActor = Snippets.resolveStaticMethod(guard);
            Snippets.makeHolderInitialized(methodActor);
            return methodActor.makeTargetMethod().getEntryPoint(OPTIMIZED_ENTRY_POINT).toAddress();
        }

        public static int resolveVirtualMethod(ResolutionGuard.InPool guard) {
            return Snippets.resolveVirtualMethod(guard).vTableIndex() * Word.size() + vmConfig().layoutScheme().hybridLayout.headerSize();
        }

        public static Word resolveSpecialMethod(ResolutionGuard.InPool guard) {
            return Snippets.resolveSpecialMethod(guard).makeTargetMethod().getEntryPoint(OPTIMIZED_ENTRY_POINT).toAddress();
        }

        public static int resolveInterfaceMethod(ResolutionGuard.InPool guard) {
            return Snippets.resolveInterfaceMethod(guard).iIndexInInterface();
        }

        public static int resolveInterfaceID(ResolutionGuard.InPool guard) {
            return Snippets.resolveInterfaceMethod(guard).holder().id;
        }

        public static Word invokeHandle(ClassMethodActor actor) {
            return Snippets.makeEntrypoint(actor, OPTIMIZED_ENTRY_POINT);
        }

        public static Word linkToSpecial(Object memberName) {
            VMTarget target = VMTarget.fromMemberName(memberName);
            return Snippets.makeEntrypoint(UnsafeCast.asClassMethodActor(target.getVmTarget()), OPTIMIZED_ENTRY_POINT);
        }

        public static Word linkToStatic(Object memberName) {
            VMTarget target = VMTarget.fromMemberName(memberName);
            return Snippets.makeEntrypoint(UnsafeCast.asClassMethodActor(target.getVmTarget()), OPTIMIZED_ENTRY_POINT);
        }

        public static Word linkToInterface(Object memberName, Object receiver) {
            VMTarget target = VMTarget.fromMemberName(memberName);
            Address vTableEntrypoint = Snippets.selectInterfaceMethod(receiver, UnsafeCast.asInterfaceMethodActor(target.getVmTarget()));
            return vTableEntrypoint.plus(OPTIMIZED_ENTRY_POINT.offset() - VTABLE_ENTRY_POINT.offset());
        }

        public static Word linkToVirtual(Object memberName, Object receiver) {
            VMTarget target = VMTarget.fromMemberName(memberName);
            Address vTableEntrypoint = Snippets.selectNonPrivateVirtualMethod(receiver, target.getVMindex());
            return vTableEntrypoint.plus(OPTIMIZED_ENTRY_POINT.offset() - VTABLE_ENTRY_POINT.offset());
        }

        public static Object allocatePrimitiveArray(DynamicHub hub, int length) {
            if (length < 0) {
                throw new NegativeArraySizeException(String.valueOf(length));
            }
            return Heap.createArray(hub, length);
        }

        public static Object allocateObjectArray(DynamicHub hub, int length) {
            if (length < 0) {
                throw new NegativeArraySizeException(String.valueOf(length));
            }
            return Heap.createArray(hub, length);
        }

        public static Object allocateObject(DynamicHub hub) {
            return Heap.createTuple(hub);
        }

        public static Object allocateHybrid(DynamicHub hub) {
            return Heap.createHybrid(hub);
        }

        /**
         * Runtime entry point for failed tlab allocation.
         * @param etla Pointer to the TLA for enabled safepoints.
         * @param size amount of space requested
         * @return pointer to an unformatted chunk of allocated memory.
         */
        public static Pointer slowPathAllocate(int size, Pointer etla) {
            if (MaxineVM.isDebug()) {
                FatalError.check(vmConfig().heapScheme().usesTLAB(), "HeapScheme must use TLAB");
            }
            return ((HeapSchemeWithTLAB) vmConfig().heapScheme()).c1xSlowPathAllocate(Size.fromInt(size), etla);
        }

        /**
         * Runtime entry point for object allocation profiling.
         * @param size of the profiled object.
         * @param hub object hub to obtain the type of the profiled object.
         */
        public static void callProfiler(int size, Hub hub, Pointer cell) {
            if (MaxineVM.isDebug()) {
                FatalError.check(vmConfig().heapScheme().usesTLAB(), "HeapScheme must use TLAB");
            }

            if (MaxineVM.profileThatObject(hub)) {
                ((HeapSchemeWithTLAB) vmConfig().heapScheme()).profile(size, hub, cell);
            }
        }

        public static void callProfilerArray(int size, Hub hub, Pointer cell) {
            if (MaxineVM.isDebug()) {
                FatalError.check(vmConfig().heapScheme().usesTLAB(), "HeapScheme must use TLAB");
            }

            if (MaxineVM.profileThatObject(hub)) {
                ((HeapSchemeWithTLAB) vmConfig().heapScheme()).profileArray(size, hub, cell);
            }
        }

        public static Pointer flushLog(Pointer logTail) {
            return TLABLog.flushAndGetStart(logTail);
        }

        public static int[] allocateIntArray(int length) {
            return new int[length];
        }

        public static Object allocateMultiArray1(DynamicHub hub, int l1) {
            if (l1 < 0) {
                throw new NegativeArraySizeException(String.valueOf(l1));
            }
            return createArray(hub, l1);
        }

        public static Object allocateMultiArray2(DynamicHub hub1, int l1, int l2) {
            if (l1 < 0 | l2 < 0) {
                throw new NegativeArraySizeException();
            }
            Object[] result = UnsafeCast.asObjectArray(createObjectArray(hub1, l1));
            DynamicHub hub2 = UnsafeCast.asDynamicHub(hub1.componentHub);
            for (int i1 = 0; i1 < l1; i1++) {
                ArrayAccess.setObject(result, i1, createArray(hub2, l2));
            }
            return result;
        }

        public static Object allocateMultiArray3(DynamicHub hub1, int l1, int l2, int l3) {
            if (l1 < 0 | l2 < 0 | l3 < 0) {
                throw new NegativeArraySizeException();
            }
            Object[] result = UnsafeCast.asObjectArray(createObjectArray(hub1, l1));
            DynamicHub hub2 = UnsafeCast.asDynamicHub(hub1.componentHub);
            DynamicHub hub3 = UnsafeCast.asDynamicHub(hub2.componentHub);
            for (int i1 = 0; i1 < l1; i1++) {
                Object[] result2 = createObjectArray(hub2, l2);
                safeArrayStore(result, i1, result2);
                for (int i2 = 0; i2 < l2; i2++) {
                    safeArrayStore(result2, i2, createArray(hub3, l3));
                }
            }
            return result;
        }

        public static Object allocateMultiArrayN(ResolutionGuard guard, int[] lengths) {
            for (int length : lengths) {
                if (length < 0) {
                    Throw.throwNegativeArraySizeException(length);
                }
            }
            ClassActor actor = Snippets.resolveClass(guard);
            return recursiveNewMultiArray(0, actor, lengths);
        }

        private static Object recursiveNewMultiArray(int index, ClassActor arrayClassActor, int[] lengths) {
            final int length = lengths[index];
            final Object result = createArray(arrayClassActor.dynamicHub(), length);
            if (length > 0) {
                final int nextIndex = index + 1;
                if (nextIndex < lengths.length) {
                    Object[] array = (Object[]) result;
                    final ClassActor subArrayClassActor = arrayClassActor.componentClassActor();
                    for (int i = 0; i < length; i++) {
                        safeArrayStore(array, i, recursiveNewMultiArray(nextIndex, subArrayClassActor, lengths));
                    }
                }
            }
            return result;
        }

        @INLINE
        private static Object[] createObjectArray(DynamicHub hub, int length) {
            if (MaxineVM.isHosted()) {
                return (Object[]) Array.newInstance(hub.classActor.componentClassActor().toJava(), length);
            }
            return UnsafeCast.asObjectArray(Heap.createArray(hub, length));
        }

        @INLINE
        private static Object createArray(DynamicHub hub, int length) {
            if (MaxineVM.isHosted()) {
                return Array.newInstance(hub.classActor.componentClassActor().toJava(), length);
            }
            return Heap.createArray(hub, length);
        }

        @INLINE
        private static void safeArrayStore(Object[] array, int index, Object val) {
            if (MaxineVM.isHosted()) {
                array[index] = val;
            } else {
                ArrayAccess.setObject(array, index, val);
            }
        }

        public static void unresolvedCheckcast(Object object, ResolutionGuard guard) {
            final ClassActor classActor = Snippets.resolveClass(guard);
            if (!ObjectAccess.readHub(object).isSubClassHub(classActor)) {
                Throw.throwClassCastException(classActor, object);
            }
        }

        public static boolean unresolvedInstanceOf(Object object, ResolutionGuard guard) {
            final ClassActor classActor = Snippets.resolveClass(guard);
            return ObjectAccess.readHub(object).isSubClassHub(classActor);
        }

        public static void arrayHubStoreCheck(DynamicHub componentHub, DynamicHub valueHub) {
            if (!valueHub.isSubClassHub(componentHub.classActor)) {
                throw new ArrayStoreException(valueHub.classActor + " is not assignable to " + componentHub.classActor);
            }
        }

        public static void throwClassCastException(DynamicHub hub, Object object) {
            Throw.throwClassCastException(hub.classActor, object);
        }

        public static void throwArrayIndexOutOfBoundsException(Object array, int index) {
            Throw.throwArrayIndexOutOfBoundsException(array, index);
        }

        public static void throwNegativeArraySizeException(int length) {
            Throw.throwNegativeArraySizeException(length);
        }

        public static void monitorEnter(Object o) {
            vmConfig().monitorScheme().monitorEnter(o);
        }

        public static void monitorExit(Object o) {
            vmConfig().monitorScheme().monitorExit(o);
        }

        public static Throwable loadException() {
            return VmThread.current().loadExceptionForHandler();
        }
    }
}
