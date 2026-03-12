// Copyright 2020 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.runtime.java.compiled.generation.processors.type._class;

import org.eclipse.collections.api.list.ListIterable;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation._class._Class;
import org.finos.legend.pure.m3.navigation.generictype.GenericType;
import org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.navigation.property.Property;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.compiled.compiler.StringJavaSource;
import org.finos.legend.pure.runtime.java.compiled.generation.JavaPackageAndImportBuilder;
import org.finos.legend.pure.runtime.java.compiled.generation.ProcessorContext;
import org.finos.legend.pure.runtime.java.compiled.generation.processors.type.TypeProcessor;

public class ClassLazyImplProcessor
{
    private static final String IMPORTS = "import java.util.concurrent.atomic.AtomicBoolean;\n" +
            "import org.eclipse.collections.api.RichIterable;\n" +
            "import org.eclipse.collections.api.factory.Lists;\n" +
            "import org.eclipse.collections.api.factory.Maps;\n" +
            "import org.eclipse.collections.api.list.ListIterable;\n" +
            "import org.eclipse.collections.api.list.MutableList;\n" +
            "import org.eclipse.collections.api.map.ImmutableMap;\n" +
            "import org.eclipse.collections.api.map.MutableMap;\n" +
            "import org.eclipse.collections.impl.list.mutable.FastList;\n" +
            "import org.finos.legend.pure.m3.coreinstance.KeyIndex;\n" +
            "import org.finos.legend.pure.m3.execution.ExecutionSupport;\n" +
            "import org.finos.legend.pure.m4.ModelRepository;\n" +
            "import org.finos.legend.pure.m4.coreinstance.CoreInstance;\n" +
            "import org.finos.legend.pure.m4.coreinstance.SourceInformation;\n" +
            "import org.finos.legend.pure.m4.coreinstance.factory.CoreInstanceFactory;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.metadata.MetadataLazy;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.CompiledExecutionSupport;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.CompiledProcessorSupport;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.ConsoleCompiled;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.FunctionExecutionCompiledBuilder;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.FunctionExecutionCompiled;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.JavaCompilerEventHandler;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.OutputWriterCompiled;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.sourceInformation.E_;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.execution.sourceInformation.PureCompiledExecutionException;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.Bridge;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.CompiledSupport;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.CoreExtensionCompiled;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.DynamicPureFunctionImpl;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.LambdaCompiledExtended;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.Pure;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.PureStringFormat;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.Reactivator;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.AbstractCompiledCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.AbstractLazyReflectiveCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.AbstractPureCompiledLambda;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.AbstractQuantityCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.BaseJavaModelCoreInstanceFactory;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.GetterOverrideExecutor;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.JavaCompiledCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.PersistentReflectiveCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.QuantityCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.ReflectiveCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.ValCoreInstance;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction0;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction1;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.Procedure3;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.Procedure4;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction0;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction1;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction2Wrapper;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction3;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction0;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction1;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction3;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.SharedPureFunction;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedFunction0;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedFunction2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedFunction;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPredicate;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedProcedure;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureFunction1;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureFunction2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureFunction3;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction0;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction1;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.Enum;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.EnumRef;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.Obj;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.ObjRef;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.Primitive;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.PropertyValueConsumer;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.PropertyValue;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.PropertyValueMany;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.PropertyValueOne;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.PropertyValueVisitor;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.RValueConsumer;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.RValue;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.serialization.model.RValueVisitor;\n";


    private static final String QUALIFIER_IMPORTS = "import org.eclipse.collections.api.block.function.Function0;\n" +
            "import org.eclipse.collections.api.block.function.Function2;\n" +
            "import org.finos.legend.pure.runtime.java.compiled.generation.processors.support.map.PureMap;\n";

    private static final String LAZY_INITIALIZED_SUFFIX = "_initialized";

    public static final String CLASS_LAZYIMPL_SUFFIX = "_LazyImpl";

    static StringJavaSource buildImplementation(String _package, String imports, CoreInstance classGenericType, ProcessorContext processorContext, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        String className = JavaPackageAndImportBuilder.buildImplClassNameFromType(_class, CLASS_LAZYIMPL_SUFFIX, processorSupport);
        String classInterfaceName = TypeProcessor.javaInterfaceForType(_class, processorSupport);
        String typeParams = ClassProcessor.typeParameters(_class);
        String typeParamsString = typeParams.isEmpty() ? "" : "<" + typeParams + ">";
        String classNamePlusTypeParams = className + typeParamsString;
        String interfaceNamePlusTypeParams = classInterfaceName + typeParamsString;
        boolean hasQualifiers = !_Class.getQualifiedProperties(_class, processorContext.getSupport()).isEmpty();

        boolean instanceOfGetterOverride = processorSupport.instance_instanceOf(_class, M3Paths.GetterOverride);

        processorContext.setClassImplSuffix(CLASS_LAZYIMPL_SUFFIX);
        StringBuilder sb = new StringBuilder(8192);
        sb.append(IMPORTS);
        if (hasQualifiers)
        {
            sb.append(QUALIFIER_IMPORTS);
        }
        sb.append(imports);
        sb.append("public class ").append(classNamePlusTypeParams).append(" extends AbstractLazyReflectiveCoreInstance implements ").append(interfaceNamePlusTypeParams).append("\n{\n");
        sb.append(ClassImplProcessor.buildMetaInfo(classGenericType, className, processorSupport, processorContext, true)).append("\n");
        sb.append(buildLazyConstructor(className));
        if (ClassProcessor.isPlatformClass(_class))
        {
            sb.append(buildFactory(className));
        }
        sb.append("\n");
        sb.append(ClassImplProcessor.buildGetKeyIndex());
        if (instanceOfGetterOverride)
        {
            sb.append(lazyGetterOverride(interfaceNamePlusTypeParams));
        }
        sb.append(ClassImplProcessor.buildGetValueForMetaPropertyToOne(classGenericType, processorSupport));
        sb.append(ClassImplProcessor.buildGetValueForMetaPropertyToMany(classGenericType, processorSupport));
        sb.append(ClassImplProcessor.buildSimpleProperties(classGenericType, (property, name, unresolvedReturnType, returnType, returnMultiplicity, returnTypeJava, classOwnerId, ownerClassName, ownerTypeParams, processorContext1) -> "    public final AtomicBoolean _" + name + LAZY_INITIALIZED_SUFFIX + " = new AtomicBoolean(false);\n" +
                (Multiplicity.isToOne(returnMultiplicity, false) ?
                 "    public " + returnTypeJava + " _" + name + ";\n" :
                 "    public RichIterable _" + name + " = Lists.mutable.empty();\n") +
                buildLazyProperty(property, ownerClassName + (ownerTypeParams.isEmpty() ? "" : "<" + ownerTypeParams + ">"), "this", name, returnType, unresolvedReturnType, returnMultiplicity, processorContext1.getSupport(), processorContext1), processorContext, processorSupport));
        sb.append(ClassImplProcessor.buildQualifiedProperties(classGenericType, processorContext, processorSupport));
        sb.append(buildLazyCopy(classGenericType, classInterfaceName, className, false, processorSupport));
        sb.append(ClassImplProcessor.buildEquality(classGenericType, false, processorContext, processorSupport));
        sb.append(ClassImplProcessor.buildGetFullSystemPath());
        if (!ClassProcessor.isPlatformClass(_class))
        {
            sb.append(ClassImplProcessor.validate(true, _class, className, classGenericType, processorContext, processorSupport.class_getSimpleProperties(_class), null, null));
        }
        sb.append("}");
        return StringJavaSource.newStringJavaSource(_package, className, sb.toString());
    }

    static String buildFactory(String className)
    {
        return "\n" + buildFactoryConstructor(className) +
                "    public static final CoreInstanceFactory FACTORY = new BaseJavaModelCoreInstanceFactory()\n" +
                "    {\n" +
                ClassImplProcessor.buildFactoryMethods(className) +
                ClassImplProcessor.buildFactorySupports() +
                "    };\n" +
                "\n";
    }

    static String buildFactoryConstructor(String className)
    {
        return "    public " + className + "(String name, SourceInformation sourceInformation, CoreInstance classifier)\n" +
                "    {\n" +
                "        super(name, sourceInformation, classifier);\n" +
                "    }\n" +
                "\n";
    }


    private static String buildLazyConstructor(String className)
    {
        return "    public " + className + "(Obj instance, MetadataLazy metadataLazy)\n" +
                "    {\n" +
                "        super(instance, metadataLazy);\n" +
                "    }\n" +
                "\n" +
                "    public " + className + "(String id, org.finos.legend.pure.m4.coreinstance.SourceInformation sourceInformation, ImmutableMap<String, Object> vals, MetadataLazy metadataLazy)\n" +
                "    {\n" +
                "        super(id, sourceInformation, metadataLazy, vals);\n" +
                "    }\n" +
                "\n" +
                "    public " + className + "(String id, org.finos.legend.pure.m4.coreinstance.SourceInformation sourceInformation, ImmutableMap<String, Object> vals, MetadataLazy metadataLazy, CoreInstance classifier)\n" +
                "    {\n" +
                "        super(id, sourceInformation, metadataLazy, vals, classifier);\n" +
                "    }" +
                "\n";
    }

    private static String lazyGetterOverride(String classNamePlusTypeParams)
    {
        return "    public " + classNamePlusTypeParams + " __getterOverrideToOneExec(PureFunction2Wrapper f2)\n" +
                "    {\n" +
                "        throw new RuntimeException(\"Not Supported in Lazy Mode!\");\n" +
                "    }\n" +
                "    public " + classNamePlusTypeParams + " __getterOverrideToManyExec(PureFunction2Wrapper f2)\n" +
                "    {\n" +
                "        throw new RuntimeException(\"Not Supported in Lazy Mode!\");\n" +
                "    }\n";

    }

    private static String buildLazyProperty(CoreInstance property, String className, String owner, String name, CoreInstance returnType, CoreInstance unresolvedReturnType, CoreInstance multiplicity, ProcessorSupport processorSupport, ProcessorContext processorContext)
    {
        CoreInstance associationClass = processorSupport.package_getByUserPath(M3Paths.Association);
        CoreInstance propertyOwner = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.owner, processorSupport);
        String reversePropertyName = null;
        if (Instance.instanceOf(propertyOwner, associationClass, processorSupport))
        {
            ListIterable<? extends CoreInstance> associationProperties = Instance.getValueForMetaPropertyToManyResolved(propertyOwner, M3Properties.properties, processorSupport);
            CoreInstance reverseProperty = associationProperties.get((property == associationProperties.get(0)) ? 1 : 0);
            reversePropertyName = Property.getPropertyName(reverseProperty);
        }

        CoreInstance rawType = Instance.getValueForMetaPropertyToOneResolved(returnType, M3Properties.rawType, processorSupport);
        boolean isPrimitive = rawType != null && Instance.instanceOf(rawType, M3Paths.PrimitiveType, processorSupport);
        boolean makePrimitiveIfPossible = GenericType.isGenericTypeConcrete(unresolvedReturnType) && Multiplicity.isToOne(multiplicity, true);

        String typePrimitive = TypeProcessor.pureTypeToJava(returnType, true, makePrimitiveIfPossible, processorSupport);
        String typeObject = TypeProcessor.pureTypeToJava(returnType, true, false, processorSupport);
        String defaultValue = TypeProcessor.defaultValue(rawType);

        if (Multiplicity.isToOne(multiplicity, false))
        {
            return ClassImplProcessor.buildPropertyStandardWriteSeverReverseToOne(name, owner, typePrimitive, isPrimitive, true) +
                    ClassImplProcessor.buildPropertyStandardWriteToOneBuilders(property, returnType, name, owner, className, typeObject, defaultValue, reversePropertyName, typePrimitive, true, processorContext) +
                    ClassImplProcessor.buildPropertyToOneGetterCoreInstance(property, returnType, name, processorContext) +
                    "    public " + typePrimitive + " _" + name + "()\n" +
                    "    {\n" +
                    "        if (!" + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ".get())\n" +
                    "        {\n" +
                    "            synchronized (" + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ")\n" +
                    "            {\n" +
                    "                if (!" + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ".get())\n" +
                    "                {\n" +
                    "                    " + owner + "._" + name + " = loadValueFromMetadata(\"" + name + "\");\n" +
                    "                    " + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ".set(true);\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +
                    "        return " + owner + "._" + name + ";\n" +
                    "    }\n";
        }
        else
        {
            return ClassImplProcessor.buildPropertyStandardSeverReverseToMany(name, owner, typePrimitive, isPrimitive, true) +
                    ClassImplProcessor.buildPropertyStandardWriteToManyBuilders(property, returnType, name, owner, className, typeObject, reversePropertyName, typePrimitive, rawType, true, processorSupport, processorContext) +
                    ClassImplProcessor.buildPropertyToManyGetterCoreInstance(property, returnType, name, processorContext) +
                    "    public RichIterable<? extends " + typeObject + "> _" + name + "()\n" +
                    "    {\n" +
                    "        if (!" + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ".get())\n" +
                    "        {\n" +
                    "            synchronized (" + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ")\n" +
                    "            {\n" +
                    "                if (!" + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ".get())\n" +
                    "                {\n" +
                    "                    " + owner + "._" + name + " = loadValuesFromMetadata(\"" + name + "\");\n" +
                    "                    " + owner + "._" + name + LAZY_INITIALIZED_SUFFIX + ".set(true);\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +
                    "        return " + owner + "._" + name + ";\n" +
                    "    }\n";
        }
    }

    public static String buildLazyCopy(CoreInstance classGenericType, String classInterfaceName, String classImplName, boolean copyGetterOverride, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        String typeParams = ClassProcessor.typeParameters(_class);
        String classNamePlusTypeParams = classInterfaceName + (typeParams.isEmpty() ? "" : "<" + typeParams + "> ");

        String propertyCopy = processorSupport.class_getSimpleProperties(_class).collect(property ->
        {
            String name = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.name, processorSupport).getName();
            CoreInstance multiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);

            CoreInstance associationClass = processorSupport.package_getByUserPath(M3Paths.Association);
            CoreInstance propertyOwner = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.owner, processorSupport);
            String reversePropertyName = null;
            if (Instance.instanceOf(propertyOwner, associationClass, processorSupport))
            {
                ListIterable<? extends CoreInstance> associationProperties = Instance.getValueForMetaPropertyToManyResolved(propertyOwner, M3Properties.properties, processorSupport);
                CoreInstance reverseProperty = (property == associationProperties.get(0)) ? associationProperties.get(1) : associationProperties.get(0);
                reversePropertyName = Property.getPropertyName(reverseProperty);
            }

            CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);
            String typeObject = TypeProcessor.typeToJavaObjectSingle(returnType, true, processorSupport);

            boolean isToOne = Multiplicity.isToOne(multiplicity, false);
            return "        synchronized (((" + classImplName + ")src)._" + name + LAZY_INITIALIZED_SUFFIX + ")\n" +
                    "        {\n" +
                    "            this._" + name + " = " + (isToOne ? "(" + typeObject + ")((" + classImplName + ")src)._" + name : "FastList.newList(((" + classImplName + ")src)._" + name + ")") + ";\n" +
                    "            this._" + name + LAZY_INITIALIZED_SUFFIX + ".set(((" + classImplName + ")src)._" + name + LAZY_INITIALIZED_SUFFIX + ".get());\n" +
                    "        }\n" +
                    (reversePropertyName == null ? "" :
                            (isToOne ?
                                    "        if (this._" + name + " != null)\n" +
                                            "        {\n" +
                                            "            this._" + name + "._reverse_" + reversePropertyName + "(this);\n" +
                                            "        }\n"
                                    :
                                    "        for (" + typeObject + " v : (RichIterable<? extends " + typeObject + ">) this._" + name + ")\n" +
                                            "        {\n" +
                                            "            v._reverse_" + reversePropertyName + "(this);\n" +
                                            "        }\n"));
        }).makeString("");

        return "    public " + classNamePlusTypeParams + " copy()\n" +
                "    {\n" +
                "        return new " + classImplName + "(this);\n" +
                "    }\n" +
                "\n" +
                "    public " + classImplName + "(" + classInterfaceName + (typeParams.isEmpty() ? "" : "<" + typeParams + ">") + " src)\n" +
                "    {\n" +
                "        super((" + classImplName + ")src);\n" +
                propertyCopy +
                "    }\n";
    }
}
