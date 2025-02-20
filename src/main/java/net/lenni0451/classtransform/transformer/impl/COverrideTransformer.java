package net.lenni0451.classtransform.transformer.impl;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.lenni0451.classtransform.exceptions.TransformerException;
import net.lenni0451.classtransform.targets.IInjectionTarget;
import net.lenni0451.classtransform.transformer.types.ARemovingTargetTransformer;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.Codifier;
import net.lenni0451.classtransform.utils.mappings.Remapper;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Modifier;
import java.util.Map;

public class COverrideTransformer extends ARemovingTargetTransformer<COverride> {

    public COverrideTransformer() {
        super(COverride.class, COverride::value);
    }

    @Override
    public void transform(COverride annotation, TransformerManager transformerManager, IClassProvider classProvider, Map<String, IInjectionTarget> injectionTargets, ClassNode transformedClass, ClassNode transformer, MethodNode transformerMethod, MethodNode target) {
        if (Modifier.isStatic(target.access) != Modifier.isStatic(transformerMethod.access)) {
            boolean isStatic = Modifier.isStatic(target.access);
            throw new TransformerException(transformerMethod, transformer, "must " + (isStatic ? "" : "not ") + "be static")
                    .help(Codifier.of(transformerMethod).access(isStatic ? transformerMethod.access | Modifier.STATIC : transformerMethod.access & ~Modifier.STATIC));
        }
        if (!ASMUtils.compareTypes(Type.getArgumentTypes(target.desc), Type.getArgumentTypes(transformerMethod.desc))) {
            throw new TransformerException(transformerMethod, transformer, "must have the same arguments as the target method")
                    .help(Codifier.of(target));
        }
        if (ASMUtils.isAccessLower(transformerMethod.access, target.access)) {
            throw new TransformerException(transformerMethod, transformer, "must be higher/equal to original method");
        }
        transformerMethod.name = target.name;
        transformerMethod.desc = target.desc;
        transformedClass.methods.remove(target);
        this.prepareForCopy(transformer, transformerMethod);
        Remapper.remapAndAdd(transformer, transformedClass, transformerMethod);
    }

}
