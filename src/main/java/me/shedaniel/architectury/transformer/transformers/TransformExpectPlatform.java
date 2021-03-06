/*
 * This file is licensed under the MIT License, part of architectury-transformer.
 * Copyright (c) 2020, 2021 shedaniel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.shedaniel.architectury.transformer.transformers;

import me.shedaniel.architectury.transformer.input.OutputInterface;
import me.shedaniel.architectury.transformer.transformers.base.AssetEditTransformer;
import me.shedaniel.architectury.transformer.transformers.base.ClassEditTransformer;
import me.shedaniel.architectury.transformer.transformers.base.edit.TransformerContext;
import me.shedaniel.architectury.transformer.util.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.IOUtils;

import java.io.InputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

public class TransformExpectPlatform implements AssetEditTransformer, ClassEditTransformer {
    @Override
    public void doEdit(TransformerContext context, OutputInterface output) throws Exception {
        if (RemapInjectables.isInjectInjectables() && context.canAddClasses()) {
            try (InputStream stream = TransformExpectPlatform.class.getResourceAsStream("/annotations-inject/injection.jar")) {
                ZipUtil.iterate(stream, (input, entry) -> {
                    if (entry.getName().endsWith(".class")) {
                        String s = entry.getName();
                        if (s.endsWith(".class")) {
                            s = s.substring(0, s.length() - 6);
                        }
                        if (s.indexOf('/') >= 0) {
                            s = s.substring(s.lastIndexOf('/') + 1);
                        }
                        
                        String newName = RemapInjectables.getUniqueIdentifier() + "/" + s;
                        ClassNode node = new ClassNode(Opcodes.ASM8);
                        
                        new ClassReader(IOUtils.toByteArray(input)).accept(node, ClassReader.EXPAND_FRAMES);
                        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        ClassRemapper remapper = new ClassRemapper(writer, new Remapper() {
                            @Override
                            public String map(String internalName) {
                                if (internalName != null && internalName.startsWith("me/shedaniel/architect/plugin/callsite")) {
                                    return internalName.replace("me/shedaniel/architect/plugin/callsite", RemapInjectables.getUniqueIdentifier());
                                }
                                return super.map(internalName);
                            }
                        });
                        node.name = newName;
                        node.accept(remapper);
                        
                        try {
                            output.addClass(newName, writer.toByteArray());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        }
    }
    
    @Override
    public ClassNode doEdit(String name, ClassNode node) {
        for (MethodNode method : node.methods) {
            String platformMethodsClass = null;
            
            if (method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(it -> Objects.equals(it.desc, RemapInjectables.expectPlatform))) {
                platformMethodsClass = "me/shedaniel/architectury/PlatformMethods";
            } else if (method.invisibleAnnotations != null && method.invisibleAnnotations.stream().anyMatch(it -> Objects.equals(it.desc, RemapInjectables.expectPlatformNew))) {
                platformMethodsClass = RemapInjectables.getUniqueIdentifier() + "/PlatformMethods";
            }
            
            if (platformMethodsClass != null) {
                if ((method.access & Opcodes.ACC_STATIC) == 0) {
                    Logger.error("@ExpectPlatform can only apply to static methods!");
                } else {
                    method.instructions.clear();
                    int endOfDesc = method.desc.lastIndexOf(')');
                    String returnValue = method.desc.substring(endOfDesc + 1);
                    String args = method.desc.substring(1, endOfDesc);
                    int cursor = 0;
                    boolean inClass = false;
                    int index = 0;
                    while (cursor < args.length()) {
                        char c = args.charAt(cursor);
                        if (inClass) {
                            if (c == ';') {
                                addLoad(method.instructions, c, index++);
                                inClass = false;
                            }
                        } else switch (c) {
                            case '[':
                                break;
                            case 'L':
                                inClass = true;
                                break;
                            default:
                                int i = index++;
                                if (c == 'J' || c == 'D') {
                                    index++;
                                }
                                addLoad(method.instructions, c, i);
                        }
                        cursor++;
                    }
                    
                    MethodType methodType = MethodType.methodType(
                            CallSite.class,
                            MethodHandles.Lookup.class,
                            String.class,
                            MethodType.class
                    );
                    
                    Handle handle = new Handle(
                            Opcodes.H_INVOKESTATIC,
                            platformMethodsClass,
                            "platform",
                            methodType.toMethodDescriptorString(),
                            false
                    );
                    
                    method.instructions.add(new InvokeDynamicInsnNode(method.name, method.desc, handle));
                    
                    int i = returnValue.chars().filter(it -> it != '[').findFirst().getAsInt();
                    addReturn(method.instructions, (char) i);
                    method.maxStack = -1;
                }
            }
        }
        
        return node;
    }
    
    private void addLoad(InsnList insnList, char type, int index) {
        switch (type) {
            case ';':
                insnList.add(new VarInsnNode(Opcodes.ALOAD, index));
                break;
            case 'I':
            case 'S':
            case 'B':
            case 'C':
            case 'Z':
                insnList.add(new VarInsnNode(Opcodes.ILOAD, index));
                break;
            case 'F':
                insnList.add(new VarInsnNode(Opcodes.FLOAD, index));
                break;
            case 'J':
                insnList.add(new VarInsnNode(Opcodes.LLOAD, index));
                break;
            case 'D':
                insnList.add(new VarInsnNode(Opcodes.DLOAD, index));
                break;
            default:
                throw new IllegalStateException("Invalid Type: " + type);
        }
    }
    
    private void addReturn(InsnList insnList, char type) {
        switch (type) {
            case 'L':
                insnList.add(new InsnNode(Opcodes.ARETURN));
                break;
            case 'I':
            case 'S':
            case 'B':
            case 'C':
            case 'Z':
                insnList.add(new InsnNode(Opcodes.IRETURN));
                break;
            case 'F':
                insnList.add(new InsnNode(Opcodes.FRETURN));
                break;
            case 'J':
                insnList.add(new InsnNode(Opcodes.LRETURN));
                break;
            case 'D':
                insnList.add(new InsnNode(Opcodes.DRETURN));
                break;
            case 'V':
                insnList.add(new InsnNode(Opcodes.RETURN));
                break;
        }
    }
}