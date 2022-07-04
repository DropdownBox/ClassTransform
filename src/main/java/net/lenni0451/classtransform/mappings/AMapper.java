package net.lenni0451.classtransform.mappings;

import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.mappings.annotation.AnnotationRemap;
import net.lenni0451.classtransform.mappings.annotation.RemapType;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.MapRemapper;
import net.lenni0451.classtransform.utils.MemberDeclaration;
import net.lenni0451.classtransform.utils.Remapper;
import net.lenni0451.classtransform.utils.annotations.AnnotationParser;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public abstract class AMapper {

    private static final String ANNOTATION_PACKAGE = CTransformer.class.getPackage().getName().replace(".", "/");

    private final MapperConfig config;
    protected final MapRemapper remapper;

    public AMapper(final MapperConfig config) {
        this.config = config;
        this.remapper = new MapRemapper();
    }

    public final void load() {
        try {
            this.init();
        } catch (Throwable t) {
            throw new RuntimeException("Unable to initialize mappings", t);
        }
    }

    public final String mapClassName(final String className) {
        return this.dot(this.remapper.mapType(this.slash(className)));
    }

    public final ClassNode mapClass(final ClassNode target, final ClassNode transformer) {
        List<AnnotationHolder> annotationsToRemap = new ArrayList<>();
        this.checkAnnotations(transformer, transformer.visibleAnnotations, annotationsToRemap);
        this.checkAnnotations(transformer, transformer.invisibleAnnotations, annotationsToRemap);
        for (FieldNode field : transformer.fields) {
            this.checkAnnotations(field, field.visibleAnnotations, annotationsToRemap);
            this.checkAnnotations(field, field.invisibleAnnotations, annotationsToRemap);
        }
        for (MethodNode method : transformer.methods) {
            this.checkAnnotations(method, method.visibleAnnotations, annotationsToRemap);
            this.checkAnnotations(method, method.invisibleAnnotations, annotationsToRemap);
        }
        for (AnnotationHolder annotation : annotationsToRemap) {
            try {
                Class<?> annotationClass = Class.forName(Type.getType(annotation.annotation.desc).getClassName());
                Map<String, Object> annotationMap = AnnotationParser.listToMap(annotation.annotation.values);
                this.mapAnnotation(annotation.holder, annotationClass, annotationMap, target, transformer);
                annotation.annotation.values = AnnotationParser.mapToList(annotationMap);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Unable to remap annotation '" + annotation.annotation.desc + "' from transformer '" + transformer.name + "'", e);
            }
        }
        if (this.config.remapTransformer) return Remapper.remap(transformer, this.remapper);
        else return transformer;
    }


    protected abstract void init() throws Throwable;


    protected List<String> readLines(final File f) throws FileNotFoundException {
        List<String> out = new ArrayList<>();
        try (Scanner s = new Scanner(f)) {
            while (s.hasNextLine()) out.add(s.nextLine());
        }
        return out;
    }

    protected String slash(final String s) {
        return s.replace('.', '/');
    }

    protected String dot(final String s) {
        return s.replace('/', '.');
    }


    private void mapAnnotation(final Object holder, final Class<?> annotation, final Map<String, Object> values, final ClassNode target, final ClassNode transformer) throws ClassNotFoundException {
        for (Method method : annotation.getDeclaredMethods()) {
            AnnotationRemap remap = method.getDeclaredAnnotation(AnnotationRemap.class);
            if (remap == null) continue;
            InfoFiller.fillInfo(holder, remap, method, values, target, transformer);
            if (this.remapper.isEmpty()) continue;

            Object value = values.get(method.getName());

            if (remap.value().equals(RemapType.ANNOTATION)) {
                if (value instanceof AnnotationNode) {
                    AnnotationNode node = (AnnotationNode) value;
                    Type type = Type.getType(node.desc);
                    Map<String, Object> nodeMap = AnnotationParser.listToMap(node.values);
                    this.mapAnnotation(holder, Class.forName(type.getClassName()), nodeMap, target, transformer);
                    node.values = AnnotationParser.mapToList(nodeMap);
                } else if (value instanceof AnnotationNode[]) {
                    AnnotationNode[] nodes = (AnnotationNode[]) value;
                    for (AnnotationNode node : nodes) {
                        Type type = Type.getType(node.desc);
                        Map<String, Object> nodeMap = AnnotationParser.listToMap(node.values);
                        this.mapAnnotation(holder, Class.forName(type.getClassName()), nodeMap, target, transformer);
                        node.values = AnnotationParser.mapToList(nodeMap);
                    }
                } else if (value instanceof List) {
                    List<AnnotationNode> nodes = (List<AnnotationNode>) value;
                    for (AnnotationNode node : nodes) {
                        Type type = Type.getType(node.desc);
                        Map<String, Object> nodeMap = AnnotationParser.listToMap(node.values);
                        this.mapAnnotation(holder, Class.forName(type.getClassName()), nodeMap, target, transformer);
                        node.values = AnnotationParser.mapToList(nodeMap);
                    }
                }
            } else {
                if (value instanceof String) {
                    String s = (String) value;
                    values.put(method.getName(), remap(remap.value(), s, target, transformer));
                } else if (value instanceof String[]) {
                    String[] strings = (String[]) value;
                    for (int i = 0; i < strings.length; i++) strings[i] = remap(remap.value(), strings[i], target, transformer);
                } else if (value instanceof List) {
                    List<String> list = (List<String>) value;
                    list.replaceAll(s -> remap(remap.value(), s, target, transformer));
                }
            }
        }
    }

    private String remap(final RemapType type, String s, final ClassNode target, final ClassNode transformer) {
        switch (type) {
            case SHORT_MEMBER:
                if (!s.contains("(") && !s.contains(":")) {
                    List<String> members = this.remapper.getStartingMappings(target.name + "." + s + "(", target.name + "." + s + ":");
                    if (members.isEmpty()) throw new IllegalStateException("No target member found for '" + s + "' from transformer '" + transformer.name + "'");
                    else if (members.size() != 1) throw new IllegalStateException("Multiple target members found for '" + s + "' from transformer '" + transformer.name + "'");

                    s = members.get(0).substring(target.name.length() + 1);
                }
                String name;
                String desc;
                if (s.contains("(")) {
                    name = s.substring(0, s.indexOf("("));
                    desc = s.substring(s.indexOf("("));
                } else if (s.contains(":")) {
                    name = s.substring(0, s.indexOf(":"));
                    desc = s.substring(s.indexOf(":"));
                } else {
                    throw new IllegalStateException("Invalid member '" + s + "' from transformer '" + transformer.name + "'");
                }
                return this.remapper.mapMethodName(target.name, name, desc) + this.remapper.mapMethodDesc(desc);

            case MEMBER:
                MemberDeclaration member = ASMUtils.splitMemberDeclaration(s);
                String owner = this.remapper.mapType(member.getOwner());
                if (member.isFieldMapping()) {
                    name = this.remapper.mapFieldName(owner, member.getName(), member.getDesc());
                    desc = this.remapper.mapDesc(member.getDesc());
                } else {
                    name = this.remapper.mapMethodName(owner, member.getName(), member.getDesc());
                    desc = this.remapper.mapMethodDesc(member.getDesc());
                }
                return Type.getType(owner).getDescriptor() + name + (member.isFieldMapping() ? ":" : "") + desc;

            case CLASS:
                return this.dot(this.remapper.mapType(this.slash(s)));

            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    private void checkAnnotations(final Object holder, final List<AnnotationNode> annotations, final List<AnnotationHolder> out) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.startsWith("L" + ANNOTATION_PACKAGE)) out.add(new AnnotationHolder(holder, annotation));
        }
    }


    private static class AnnotationHolder {
        private final Object holder;
        private final AnnotationNode annotation;

        private AnnotationHolder(final Object holder, final AnnotationNode annotation) {
            this.holder = holder;
            this.annotation = annotation;
        }
    }

}
