/*
 *  Copyright (C) 2010-2015 JPEXS, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.jpexs.decompiler.flash.abc;

import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.ConvertException;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instructions;
import com.jpexs.decompiler.flash.abc.types.ConvertData;
import com.jpexs.decompiler.flash.abc.types.MethodBody;
import com.jpexs.decompiler.flash.abc.types.Multiname;
import com.jpexs.decompiler.flash.abc.types.Namespace;
import com.jpexs.decompiler.flash.abc.types.traits.Trait;
import com.jpexs.decompiler.flash.abc.types.traits.TraitClass;
import com.jpexs.decompiler.flash.abc.types.traits.TraitFunction;
import com.jpexs.decompiler.flash.abc.types.traits.TraitMethodGetterSetter;
import com.jpexs.decompiler.flash.abc.types.traits.Traits;
import com.jpexs.decompiler.flash.configuration.Configuration;
import com.jpexs.decompiler.flash.exporters.modes.ScriptExportMode;
import com.jpexs.decompiler.flash.exporters.settings.ScriptExportSettings;
import com.jpexs.decompiler.flash.helpers.FileTextWriter;
import com.jpexs.decompiler.flash.helpers.GraphTextWriter;
import com.jpexs.decompiler.flash.helpers.NulWriter;
import com.jpexs.decompiler.flash.helpers.hilight.Highlighting;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.treeitems.AS3ClassTreeItem;
import com.jpexs.decompiler.graph.DottedChain;
import com.jpexs.decompiler.graph.ScopeStack;
import com.jpexs.helpers.CancellableWorker;
import com.jpexs.helpers.Helper;
import com.jpexs.helpers.Path;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author JPEXS
 */
public class ScriptPack extends AS3ClassTreeItem {

    private static final Logger logger = Logger.getLogger(ScriptPack.class.getName());

    public final ABC abc;

    public List<ABC> allABCs;

    public final int scriptIndex;

    public final List<Integer> traitIndices;

    private final ClassPath path;

    public boolean isSimple = false;

    public boolean scriptInitializerIsEmpty = false;

    @Override
    public SWF getSwf() {
        return abc.getSwf();
    }

    public ClassPath getClassPath() {
        return path;
    }

    public ScriptPack(ClassPath path, ABC abc, List<ABC> allAbcs, int scriptIndex, List<Integer> traitIndices) {
        super(path.className, path);
        this.abc = abc;
        this.scriptIndex = scriptIndex;
        this.traitIndices = traitIndices;
        this.path = path;
        this.allABCs = allAbcs;
    }

    public DottedChain getPathPackage() {
        DottedChain packageName = DottedChain.TOPLEVEL;
        for (int t : traitIndices) {
            Multiname name = abc.script_info.get(scriptIndex).traits.traits.get(t).getName(abc);
            Namespace ns = name.getNamespace(abc.constants);
            if ((ns.kind == Namespace.KIND_PACKAGE) || (ns.kind == Namespace.KIND_PACKAGE_INTERNAL)) {
                packageName = ns.getName(abc.constants); // assume not null
            }
        }
        return packageName;
    }

    public String getPathScriptName() {
        String scriptName = "";
        for (int t : traitIndices) {
            Multiname name = abc.script_info.get(scriptIndex).traits.traits.get(t).getName(abc);
            Namespace ns = name.getNamespace(abc.constants);
            if ((ns.kind == Namespace.KIND_PACKAGE) || (ns.kind == Namespace.KIND_PACKAGE_INTERNAL)) {
                scriptName = name.getName(abc.constants, null, false);
            }
        }
        return scriptName;
    }

    public File getExportFile(String directory, ScriptExportSettings exportSettings) {
        if (exportSettings.singleFile) {
            return null;
        }

        String scriptName = getPathScriptName();
        DottedChain packageName = getPathPackage();
        File outDir = new File(directory + File.separatorChar + packageName.toFilePath());
        String fileName = outDir.toString() + File.separator + Helper.makeFileName(scriptName) + exportSettings.getFileExtension();
        return new File(fileName);
    }

    /*public String getPath() {
     String packageName = "";
     String scriptName = "";
     for (int t : traitIndices) {
     Multiname name = abc.script_info[scriptIndex].traits.traits.get(t).getName(abc);
     Namespace ns = name.getNamespace(abc.constants);
     if ((ns.kind == Namespace.KIND_PACKAGE) || (ns.kind == Namespace.KIND_PACKAGE_INTERNAL)) {
     packageName = ns.getName(abc.constants);
     scriptName = name.getName(abc.constants, new ArrayList<>());
     }
     }
     return packageName.equals("") ? scriptName : packageName + "." + scriptName;
     }*/
    public void convert(final NulWriter writer, final List<Trait> traits, final ConvertData convertData, final ScriptExportMode exportMode, final boolean parallel) throws InterruptedException {

        int sinit_index = abc.script_info.get(scriptIndex).init_index;
        int sinit_bodyIndex = abc.findBodyIndex(sinit_index);
        if (sinit_bodyIndex != -1) {
            List<Traits> ts = new ArrayList<>();
            //initialize all classes traits
            for (Trait t : traits) {
                if (t instanceof TraitClass) {
                    ts.add(abc.class_info.get(((TraitClass) t).class_info).static_traits);
                }
            }
            ts.add(abc.script_info.get(scriptIndex).traits);
            writer.mark();
            abc.bodies.get(sinit_bodyIndex).convert(convertData, path +/*packageName +*/ "/.scriptinitializer", exportMode, true, sinit_index, scriptIndex, -1, abc, null, new ScopeStack(), GraphTextWriter.TRAIT_SCRIPT_INITIALIZER, writer, new ArrayList<>(), ts, true);
            scriptInitializerIsEmpty = !writer.getMark();

        }
        for (int t : traitIndices) {
            Trait trait = traits.get(t);
            Multiname name = trait.getName(abc);
            Namespace ns = name.getNamespace(abc.constants);
            if ((ns.kind == Namespace.KIND_PACKAGE) || (ns.kind == Namespace.KIND_PACKAGE_INTERNAL)) {
                trait.convertPackaged(null, convertData, "", abc, false, exportMode, scriptIndex, -1, writer, new ArrayList<>(), parallel);
            } else {
                trait.convert(null, convertData, "", abc, false, exportMode, scriptIndex, -1, writer, new ArrayList<>(), parallel);
            }
        }
    }

    private void appendTo(GraphTextWriter writer, List<Trait> traits, ConvertData convertData, ScriptExportMode exportMode, boolean parallel) throws InterruptedException {
        boolean first = true;
        //script initializer
        int script_init = abc.script_info.get(scriptIndex).init_index;
        int bodyIndex = abc.findBodyIndex(script_init);
        if (bodyIndex != -1 && Configuration.enableScriptInitializerDisplay.get()) {
            //Note: There must be trait/method highlight even if the initializer is empty to TraitList in GUI to work correctly
            //TODO: handle this better in GUI(?)
            writer.startTrait(GraphTextWriter.TRAIT_SCRIPT_INITIALIZER);
            writer.startMethod(script_init);
            if (!scriptInitializerIsEmpty) {
                writer.startBlock();
                abc.bodies.get(bodyIndex).toString(path +/*packageName +*/ "/.scriptinitializer", exportMode, abc, null, writer, new ArrayList<>());
                writer.endBlock();
            } else {
                writer.append(" ");
            }
            writer.endMethod();
            writer.endTrait();
            if (!scriptInitializerIsEmpty) {
                writer.newLine();
            }
            first = false;
        } else {
            //"/*classInitializer*/";
        }

        for (int t : traitIndices) {
            if (!first) {
                writer.newLine();
            }

            Trait trait = traits.get(t);
            Multiname name = trait.getName(abc);
            Namespace ns = name.getNamespace(abc.constants);
            if ((ns.kind == Namespace.KIND_PACKAGE) || (ns.kind == Namespace.KIND_PACKAGE_INTERNAL)) {
                trait.toStringPackaged(null, convertData, "", abc, false, exportMode, scriptIndex, -1, writer, new ArrayList<>(), parallel);
            } else {
                trait.toString(null, convertData, "", abc, false, exportMode, scriptIndex, -1, writer, new ArrayList<>(), parallel);
            }

            first = false;
        }
    }

    public void toSource(GraphTextWriter writer, final List<Trait> traits, final ConvertData convertData, final ScriptExportMode exportMode, final boolean parallel) throws InterruptedException {
        writer.suspendMeasure();
        int timeout = Configuration.decompilationTimeoutFile.get();
        try {
            CancellableWorker.call(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    convert(new NulWriter(), traits, convertData, exportMode, parallel);
                    return null;
                }
            }, timeout, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            writer.continueMeasure();
            logger.log(Level.SEVERE, "Decompilation error", ex);
            Helper.appendTimeoutCommentAs3(writer, timeout, 0);
            return;
        } catch (ExecutionException ex) {
            writer.continueMeasure();
            logger.log(Level.SEVERE, "Decompilation error", ex);
            Helper.appendErrorComment(writer, ex);
            return;
        }
        writer.continueMeasure();

        appendTo(writer, traits, convertData, exportMode, parallel);
    }

    public File export(File file, ScriptExportSettings exportSettings, boolean parallel) throws IOException, InterruptedException {
        if (!exportSettings.singleFile) {
            if (file.exists() && !Configuration.overwriteExistingFiles.get()) {
                return file;
            }
        }

        Path.createDirectorySafe(file.getParentFile());

        try (FileTextWriter writer = exportSettings.singleFile ? null : new FileTextWriter(Configuration.getCodeFormatting(), new FileOutputStream(file))) {
            FileTextWriter writer2 = exportSettings.singleFile ? exportSettings.singleFileWriter : writer;
            toSource(writer2, abc.script_info.get(scriptIndex).traits.traits, new ConvertData(), exportSettings.mode, parallel);
        } catch (FileNotFoundException ex) {
            logger.log(Level.SEVERE, "The file path is probably too long", ex);
        }

        return file;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 79 * hash + Objects.hashCode(abc);
        hash = 79 * hash + scriptIndex;
        hash = 79 * hash + Objects.hashCode(path);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ScriptPack other = (ScriptPack) obj;
        if (!Objects.equals(abc, other.abc)) {
            return false;
        }
        if (scriptIndex != other.scriptIndex) {
            return false;
        }
        if (!Objects.equals(path, other.path)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isModified() {
        return abc.script_info.get(scriptIndex).isModified();
    }

    /**
     * Injects debugfile, debugline instructions into the code
     *
     * Based on idea of Jacob Thompson
     * http://securityevaluators.com/knowledge/flash/
     */
    public void injectDebugInfo(File directoryPath) {
        Map<Integer, Map<Integer, Integer>> bodyToPosToLine = new HashMap<>();
        try {
            CachedDecompilation decompiled = SWF.getCached(this);
            int line = 1;
            String txt = decompiled.text;
            txt = txt.replace("\r", "");
            for (int i = 0; i < txt.length(); i++) {
                if (txt.charAt(i) == '\n') {
                    line++;
                }
                Highlighting cls = Highlighting.searchPos(decompiled.classHilights, i);
                if (cls == null) {
                    continue;
                }
                Highlighting trt = Highlighting.searchPos(decompiled.traitHilights, i);
                if (trt == null) {
                    continue;
                }
                Highlighting method = Highlighting.searchPos(decompiled.methodHilights, i);
                if (method == null) {
                    continue;
                }
                Highlighting instr = Highlighting.searchPos(decompiled.instructionHilights, i);
                if (instr == null) {
                    continue;
                }
                int classIndex = (int) cls.getProperties().index;
                int methodIndex = (int) method.getProperties().index;
                int bodyIndex = abc.findBodyIndex(methodIndex);
                if (bodyIndex == -1) {
                    continue;
                }
                long instrOffset = instr.getProperties().offset;
                int traitIndex = (int) trt.getProperties().index;

                Trait trait = abc.findTraitByTraitId(classIndex, traitIndex);
                if (((trait instanceof TraitMethodGetterSetter) && (((TraitMethodGetterSetter) trait).method_info != methodIndex))
                        || ((trait instanceof TraitFunction) && (((TraitFunction) trait).method_info != methodIndex))) {
                    continue; //inner anonymous function - ignore. TODO: make work
                }
                int pos = -1;
                try {
                    pos = abc.bodies.get(bodyIndex).getCode().adr2pos(instrOffset);
                } catch (ConvertException cex) {
                    //ignore
                }
                if (pos == -1) {
                    continue;
                }
                if (!bodyToPosToLine.containsKey(bodyIndex)) {
                    bodyToPosToLine.put(bodyIndex, new HashMap<>());
                }
                bodyToPosToLine.get(bodyIndex).put(pos, line);

            }
        } catch (InterruptedException ex) {
            Logger.getLogger(ScriptPack.class.getName()).log(Level.SEVERE, "Cannot decompile", ex);
        }

        //String filepath = path.toString().replace('.', '/') + ".as";
        String pkg = path.packageStr.toString();
        String cls = path.className;
        String filename = new File(directoryPath, path.packageStr.toFilePath()) + ";" + pkg.replace(".", File.separator) + ";" + cls + ".as";

        for (int bodyIndex : bodyToPosToLine.keySet()) {
            MethodBody b = abc.bodies.get(bodyIndex);
            b.insertInstruction(0, new AVM2Instruction(0, AVM2Instructions.DebugFile, new int[]{abc.constants.getStringId(filename, true)}), true);
            List<Integer> pos = new ArrayList<>(bodyToPosToLine.get(bodyIndex).keySet());
            Collections.sort(pos);
            Collections.reverse(pos);
            Set<Integer> addedLines = new HashSet<>();
            for (int i : pos) {
                int line = bodyToPosToLine.get(bodyIndex).get(i);
                if (addedLines.contains(line)) {
                    continue;
                }
                addedLines.add(line);
                Logger.getLogger(ScriptPack.class.getName()).log(Level.WARNING, "Script " + path + ": Insert debugline(" + line + ") at pos " + i + " to body " + bodyIndex);
                b.insertInstruction(i, new AVM2Instruction(0, AVM2Instructions.DebugLine, new int[]{line}));
            }
            b.setModified();
        }

        ((Tag) abc.parentTag).setModified(true);
    }
}
