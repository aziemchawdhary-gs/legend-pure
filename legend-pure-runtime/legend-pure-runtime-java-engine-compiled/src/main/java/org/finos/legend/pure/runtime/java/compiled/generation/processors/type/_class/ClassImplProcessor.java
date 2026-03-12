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

import org.apache.commons.lang3.StringEscapeUtils;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.block.predicate.Predicate2;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.Counter;
import org.eclipse.collections.impl.tuple.Tuples;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement;
import org.finos.legend.pure.m3.navigation.PrimitiveUtilities;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation._class._Class;
import org.finos.legend.pure.m3.navigation.generictype.GenericType;
import org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.navigation.property.Property;
import org.finos.legend.pure.m3.navigation.type.Type;
import org.finos.legend.pure.m3.tools.JavaTools;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.compiled.compiler.StringJavaSource;
import org.finos.legend.pure.runtime.java.compiled.generation.JavaPackageAndImportBuilder;
import org.finos.legend.pure.runtime.java.compiled.generation.ProcessorContext;
import org.finos.legend.pure.runtime.java.compiled.generation.processors.FunctionProcessor;
import org.finos.legend.pure.runtime.java.compiled.generation.processors.type.TypeProcessor;
import org.finos.legend.pure.runtime.java.compiled.generation.processors.valuespecification.ValueSpecificationProcessor;

public class ClassImplProcessor
{
    //DO NOT ADD WIDE * IMPORTS TO THIS LIST IT IMPACTS COMPILE TIMES
    static final ImmutableList<String> IMPORTS_LIST = Lists.immutable.with(
            "org.eclipse.collections.api.RichIterable",
            "org.eclipse.collections.api.factory.Lists",
            "org.eclipse.collections.api.factory.Maps",
            "org.eclipse.collections.api.list.ListIterable",
            "org.eclipse.collections.api.list.MutableList",
            "org.eclipse.collections.api.map.MutableMap",
            "org.finos.legend.pure.m3.coreinstance.KeyIndex",
            "org.finos.legend.pure.m3.execution.ExecutionSupport",
            "org.finos.legend.pure.m4.ModelRepository",
            "org.finos.legend.pure.m4.coreinstance.CoreInstance",
            "org.finos.legend.pure.m4.coreinstance.SourceInformation",
            "org.finos.legend.pure.m4.coreinstance.factory.CoreInstanceFactory",
            "org.finos.legend.pure.runtime.java.compiled.execution.CompiledExecutionSupport",
            "org.finos.legend.pure.runtime.java.compiled.execution.CompiledProcessorSupport",
            "org.finos.legend.pure.runtime.java.compiled.execution.ConsoleCompiled",
            "org.finos.legend.pure.runtime.java.compiled.execution.FunctionExecutionCompiledBuilder",
            "org.finos.legend.pure.runtime.java.compiled.execution.FunctionExecutionCompiled",
            "org.finos.legend.pure.runtime.java.compiled.execution.JavaCompilerEventHandler",
            "org.finos.legend.pure.runtime.java.compiled.execution.OutputWriterCompiled",
            "org.finos.legend.pure.runtime.java.compiled.execution.sourceInformation.E_",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.Bridge",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.CompiledSupport",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.CoreExtensionCompiled",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.DynamicPureFunctionImpl",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.LambdaCompiledExtended",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.Pure",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.PureStringFormat",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.Reactivator",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.QuantityCoreInstance",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.ReflectiveCoreInstance",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.ValCoreInstance",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.GetterOverrideExecutor",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction0",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction1",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.DefaultPureLambdaFunction",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.Procedure3",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.Procedure4",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction0",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction1",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction2Wrapper",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureFunction3",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction0",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction1",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction3",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.PureLambdaFunction",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.SharedPureFunction",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedFunction0",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedFunction2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedFunction",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPredicate",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedProcedure",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureFunction1",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureFunction2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureFunction3",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction0",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction1",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.function.defended.DefendedPureLambdaFunction"
    );

    static final ImmutableList<String> FUNCTION_IMPORTS_LIST = Lists.immutable.with(
            "org.eclipse.collections.api.block.function.Function",
            "org.eclipse.collections.api.block.function.Function0",
            "org.eclipse.collections.api.block.function.Function2",
            "org.finos.legend.pure.runtime.java.compiled.generation.processors.support.map.PureMap"
    );

    static final ImmutableList<String> SERIALIZABLE_IMPORTS_LIST = Lists.immutable.with(
            "java.io.Externalizable",
            "java.io.IOException",
            "java.io.ObjectInput",
            "java.io.ObjectOutput",
            "org.eclipse.collections.api.block.procedure.Procedure"
    );

    public static final String IMPORTS = JavaTools.sortReduceAndPrintImports(IMPORTS_LIST);
    static final String FUNCTION_IMPORTS = JavaTools.sortReduceAndPrintImports(FUNCTION_IMPORTS_LIST);
    static final String SERIALIZABLE_IMPORTS = JavaTools.sortReduceAndPrintImports(SERIALIZABLE_IMPORTS_LIST);

    public static final String CLASS_IMPL_SUFFIX = "_Impl";
    public static final String CLASS_OVERRIDE_IMPL_SUFFIX = "_OverrideImpl";

    @Deprecated
    public static final Predicate2<CoreInstance, ProcessorSupport> IS_TO_ONE = ClassImplProcessor::isToOne;

    public static StringJavaSource buildImplementation(String _package, String imports, CoreInstance classGenericType, ProcessorContext processorContext, ProcessorSupport processorSupport, boolean useJavaInheritance, boolean addJavaSerializationSupport, String pureExternalPackage)
    {
        processorContext.setClassImplSuffix(CLASS_IMPL_SUFFIX);
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        String className = JavaPackageAndImportBuilder.buildImplClassNameFromType(_class, processorSupport);
        String typeParams = ClassProcessor.typeParameters(_class);
        String typeParamsString = typeParams.isEmpty() ? "" : "<" + typeParams + ">";
        String classNamePlusTypeParams = className + typeParamsString;
        String interfaceNamePlusTypeParams = TypeProcessor.javaInterfaceForType(_class, processorSupport) + typeParamsString;

        MutableList<String> defaultValueKeys = _Class.getSimpleProperties(_class, processorSupport).collectIf(p -> p.getValueForMetaPropertyToOne(M3Properties.defaultValue) != null, CoreInstance::getName, Lists.mutable.empty());
        ListIterable<String> defaultValues = DefaultValue.manageDefaultValues((name, value) ->
                "            case \"" + name + "\":\n" +
                        "            {\n" +
                        "                return " + value + ";\n" +
                        "            }\n", _class, true, true, processorContext);

        boolean isGetterOverride = M3Paths.GetterOverride.equals(PackageableElement.getUserPathForPackageableElement(_class)) ||
                M3Paths.ConstraintsGetterOverride.equals(PackageableElement.getUserPathForPackageableElement(_class));

        ListIterable<String> allGeneralizations = ClassInterfaceProcessor.getAllGeneralizations(processorContext, processorSupport, _class, CLASS_IMPL_SUFFIX);

        String _extends = useJavaInheritance ? allGeneralizations.getFirst() : "ReflectiveCoreInstance";
        CoreInstance associationClass = processorSupport.package_getByUserPath(M3Paths.Association);

        boolean hasFunctions = !_Class.getQualifiedProperties(_class, processorContext.getSupport()).isEmpty()
                || !_Class.computeConstraintsInHierarchy(_class, processorContext.getSupport()).isEmpty();

        String validateExtraValues = _class.getValueForMetaPropertyToMany(M3Properties.typeVariables)
                .collect(p -> " _" + PrimitiveUtilities.getStringValue(p.getValueForMetaPropertyToOne(M3Properties.name)), Lists.mutable.empty())
                .with("this")
                .makeString("Lists.mutable.with(", ", ", ")");

        StringBuilder sb = new StringBuilder(8192);
        sb.append(IMPORTS);
        if (hasFunctions)
        {
            sb.append(FUNCTION_IMPORTS);
        }
        if (addJavaSerializationSupport)
        {
            sb.append(SERIALIZABLE_IMPORTS);
        }
        sb.append(imports);
        sb.append("public class ").append(classNamePlusTypeParams).append(" extends ").append(_extends)
                .append(" implements ").append(interfaceNamePlusTypeParams);
        if (isGetterOverride)
        {
            sb.append(", GetterOverrideExecutor");
        }
        if (addJavaSerializationSupport)
        {
            sb.append(", Externalizable");
        }
        sb.append("\n{\n");
        if (addJavaSerializationSupport)
        {
            sb.append("    static final long serialVersionUID = -1L;\n");
        }
        sb.append(buildMetaInfo(classGenericType, className, processorSupport, processorContext, false)).append("\n");
        if (addJavaSerializationSupport)
        {
            sb.append(buildDefaultConstructor(className));
        }
        sb.append(buildSimpleConstructor(_class, className, processorSupport, useJavaInheritance));
        if (addJavaSerializationSupport)
        {
            sb.append(buildSerializationMethods(_class, processorSupport, classGenericType, pureExternalPackage));
        }
        sb.append(ClassProcessor.isPlatformClass(_class) ? buildFactory(className) : buildFactoryConstructor(className));
        if (isGetterOverride)
        {
            sb.append(getterOverrides(interfaceNamePlusTypeParams));
        }
        sb.append(buildGetClassifier()).append("\n");
        sb.append(buildGetKeyIndex());
        sb.append(buildGetValueForMetaPropertyToOne(classGenericType, processorSupport));
        sb.append(buildGetValueForMetaPropertyToMany(classGenericType, processorSupport));
        sb.append(buildSetKeyValues(classGenericType, processorSupport));
        sb.append(buildAddKeyValue(classGenericType, processorSupport));
        sb.append(buildModifyValueForToManyMetaProperty(classGenericType, processorSupport));
        sb.append(buildRemoveProperty(classGenericType, processorSupport));
        sb.append(buildSimpleProperties(classGenericType, (property, name, unresolvedReturnType, returnType, returnMultiplicity, returnTypeJava, classOwnerId, ownerClassName, ownerTypeParams, processorContext1) ->
        {
            CoreInstance propertyOwner = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.owner, processorSupport);

            StringBuilder propertySb = new StringBuilder();
            boolean includeGettor = !useJavaInheritance || propertyOwner == _class || Instance.instanceOf(propertyOwner, associationClass, processorSupport);
            if (includeGettor)
            {
                if (Multiplicity.isToOne(returnMultiplicity, false))
                {
                    propertySb.append("    public ").append(returnTypeJava).append(" _").append(name).append(";\n");
                }
                else
                {
                    propertySb.append("    public RichIterable _").append(name).append(" = Lists.mutable.empty();\n");
                }
            }
            propertySb.append(buildProperty(property, ownerClassName + (ownerTypeParams.isEmpty() ? "" : "<" + ownerTypeParams + ">"), "this", classOwnerId, name, returnType, unresolvedReturnType, returnMultiplicity, processorContext1.getSupport(), includeGettor, processorContext1));
            return propertySb.toString();
        }, processorContext, processorSupport));
        sb.append(buildQualifiedProperties(classGenericType, processorContext, processorSupport));
        sb.append(buildCopy(classGenericType, CLASS_IMPL_SUFFIX, isGetterOverride, processorSupport));
        sb.append(buildEquality(classGenericType, false, processorContext, processorSupport));
        sb.append(buildGetFullSystemPath());
        if (!ClassProcessor.isPlatformClass(_class))
        {
            sb.append(validate(true, _class, className, classGenericType, processorContext, processorSupport.class_getSimpleProperties(_class), null, validateExtraValues));
        }
        if (!defaultValueKeys.isEmpty())
        {
            sb.append("\n    @Override\n")
                    .append("    public ListIterable<String> getDefaultValueKeys()\n")
                    .append("    {\n")
                    .append("        return ").append(defaultValueKeys.makeString("Lists.immutable.with(\"", "\", \"", "\");\n"))
                    .append("    }\n");
        }
        if (!defaultValues.isEmpty())
        {
            sb.append("\n    @Override\n")
                    .append("    public RichIterable<?> getDefaultValue(String property, ExecutionSupport es)\n")
                    .append("    {\n")
                    .append("        switch (property)\n")
                    .append("        {\n")
                    .append(defaultValues.makeString(""))
                    .append("            default:\n")
                    .append("            {\n")
                    .append("                return Lists.immutable.empty();\n")
                    .append("            }\n")
                    .append("        }\n")
                    .append("    }");
        }
        sb.append("}");
        return StringJavaSource.newStringJavaSource(_package, className, sb.toString());
    }

    private static String buildDefaultConstructor(String className)
    {
        return "    public " + className + "()\n" +
                "    {\n" +
                "         this(\"Anonymous_NoCounter\");;\n" +
                "    }\n" +
                "\n";
    }

    private static String buildSerializationMethods(CoreInstance _class, ProcessorSupport processorSupport, CoreInstance classGenericType, String pureExternalPackage)
    {
        StringBuilder writeExternal = new StringBuilder();
        StringBuilder readExternal = new StringBuilder();
        writeExternal.append("   @Override\n    public void writeExternal(final ObjectOutput out) throws IOException\n    {\n");
        readExternal.append("    @Override\n    public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException\n    {\n");
        Counter enumCounter = new Counter(0);
        processorSupport.class_getSimpleProperties(_class).forEach(property ->
        {
            CoreInstance unresolvedReturnType = ClassProcessor.getPropertyUnresolvedReturnType(property, processorSupport);
            CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);

            String name = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.name, processorSupport).getName();
            CoreInstance returnMultiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);

            boolean makePrimitiveIfPossible = GenericType.isGenericTypeConcrete(unresolvedReturnType) && Multiplicity.isToOne(returnMultiplicity, true);
            String returnTypeJava = TypeProcessor.pureTypeToJava(returnType, true, makePrimitiveIfPossible, processorSupport);
            boolean multiplicityOne = Multiplicity.isToOne(returnMultiplicity, false);
            if ("org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Enum".equals(returnTypeJava))
            {
                serializeEnum(name, multiplicityOne, writeExternal, readExternal, enumCounter.getCount(), pureExternalPackage);
                enumCounter.increment();
            }
            else
            {
                writeExternal.append("           out.writeObject(this._").append(name).append(");\n");
                readExternal.append("           this._").append(name).append(" = (").append(!multiplicityOne ? " RichIterable" : returnTypeJava).append(") in.readObject();\n");
            }
        });
        writeExternal.append("   }\n");
        readExternal.append("   }\n");

        return writeExternal.append(readExternal).toString();
    }

    private static void serializeEnum(String propertyName, boolean multiplicityOne, StringBuilder writeExternal, StringBuilder readExternal, int n, String pureExternalPackage)
    {
        if (multiplicityOne)
        {
            writeExternal.append("            out.writeObject(this._").append(propertyName).append(".getFullSystemPath());out.writeObject(this._").append(propertyName).append("._name());\n");
            readExternal.append("try { this._").append(propertyName).append(" = (org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Enum) ((org.finos.legend.pure.runtime.java.compiled.execution.CompiledExecutionSupport)Class.forName(\"").append(pureExternalPackage).append(".PureExternal\").getMethod(\"_getExecutionSupport\").invoke(null)).getMetadata().getEnum(((String)in.readObject()).substring(6), (String)in.readObject()); } ").append("catch (IllegalAccessException | java.lang.reflect.InvocationTargetException | NoSuchMethodException | ClassNotFoundException e ) {\n").append("         throw  new RuntimeException(e);\n").append("     };\n");
        }
        else
        {
            writeExternal.append("            out.writeObject((Integer)this._").append(propertyName).append(".size());\n");
            readExternal.append("             int n").append(n).append(" = (Integer)in.readObject();");
            writeExternal.append("            this._").append(propertyName).append(".forEach(new  DefendedProcedure() ")
                    .append("{\n").append("            @Override\n").append("            public void value(Object anEnum) {\n").append("            try{out.writeObject(((org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Enum)anEnum).getFullSystemPath());out.writeObject(((org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Enum)anEnum)._name());}catch (IOException e){throw new RuntimeException(e);}\n").append("            }});\n");
            readExternal.append("             for (int i = 0; i < n").append(n).append("; i++){\n").append("            try { _").append(propertyName).append("((org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Enum) ((org.finos.legend.pure.runtime.java.compiled.execution.CompiledExecutionSupport)Class.forName(\"").append(pureExternalPackage).append(".PureExternal\").getMethod(\"_getExecutionSupport\").invoke(null)).getMetadata().getEnum(((String)in.readObject()).substring(6), (String)in.readObject()), true); }\n").append("                    catch (IllegalAccessException | java.lang.reflect.InvocationTargetException | NoSuchMethodException | ClassNotFoundException e ) {\n").append("                             throw  new RuntimeException(e);\n").append("                         }\n            }\n");
        }

    }

    private static String deserializeEnum(String propertyName, boolean multiplicityOne, String pureExternalPackage)
    {
        if (multiplicityOne)
        {
            return "try { this._" + propertyName + " = (org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Enum) ((org.finos.legend.pure.runtime.java.compiled.execution.CompiledExecutionSupport)Class.forName(\"" + pureExternalPackage + ".PureExternal\").getMethod(\"_getExecutionSupport\").invoke(null)).getMetadata().getEnum(((String)in.readObject()).substring(6), (String)in.readObject()); } " +
                    "catch (IllegalAccessException | java.lang.reflect.InvocationTargetException | NoSuchMethodException | ClassNotFoundException e ) {\n" +
                    "         throw  new RuntimeException(e);\n" +
                    "     };";
        }
        else
        {
            return "";
        }
    }

    static String buildFactory(String className)
    {
        return buildFactoryConstructor(className) +
                "    public static final CoreInstanceFactory FACTORY = new org.finos.legend.pure.runtime.java.compiled.generation.processors.support.coreinstance.BaseJavaModelCoreInstanceFactory()\n" +
                "    {\n" +
                buildFactoryMethods(className) +
                buildFactorySupports() +
                "    };\n" +
                "\n";
    }

    static String buildFactorySupports()
    {
        return "        @Override\n" +
                "        public boolean supports(String classifierPath)\n" +
                "        {\n" +
                "            return tempFullTypeId.equals(classifierPath);\n" +
                "        }\n";
    }

    static String buildFactoryMethods(String className)
    {
        return "        @Override\n" +
                "        public CoreInstance createCoreInstance(String name, int internalSyntheticId, SourceInformation sourceInformation, CoreInstance classifier, ModelRepository repository, boolean persistent)\n" +
                "        {\n" +
                "            return new " + className + "(name, sourceInformation, classifier);\n" +
                "        }\n" +
                "\n";
    }

    public static String buildMetaInfo(CoreInstance classGenericType, String className, ProcessorSupport processorSupport, ProcessorContext processorContext, boolean lazy)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        StringBuilder builder = appendKeyIndex(appendTempTypeInfo(new StringBuilder(), _class), _class, processorSupport);
        if (!lazy)
        {
            builder.append("    private CoreInstance classifier;\n");
        }

        return appendTypeVariables(builder, _class, className, processorSupport, processorContext).toString();
    }

    static StringBuilder appendTempTypeInfo(StringBuilder builder, CoreInstance _class)
    {
        builder.append("    public static final String tempTypeName = \"").append(PrimitiveUtilities.getStringValue(_class.getValueForMetaPropertyToOne(M3Properties.name))).append("\";\n");
        PackageableElement.writeSystemPathForPackageableElement(builder.append("    private static final String tempFullTypeId = \""), _class).append("\";\n");
        return builder;
    }

    static StringBuilder appendKeyIndex(StringBuilder builder, CoreInstance _class, ProcessorSupport processorSupport)
    {
        MapIterable<String, CoreInstance> simplePropertiesByName = processorSupport.class_getSimplePropertiesByName(_class);
        builder.append("    private static final KeyIndex KEY_INDEX = KeyIndex.builder(").append(simplePropertiesByName.size()).append(")\n");
        MutableMap<CoreInstance, MutableSet<String>> propertiesBySourceType = Maps.mutable.empty();
        simplePropertiesByName.forEachKeyValue((name, property) ->
        {
            CoreInstance sourceType = Property.getSourceType(property, processorSupport);
            propertiesBySourceType.getIfAbsentPut(sourceType, Sets.mutable::empty).add(name);
        });
        MutableList<Pair<String, Pair<MutableList<String>, MutableList<String>>>> list = Lists.mutable.ofInitialCapacity(propertiesBySourceType.size());
        propertiesBySourceType.forEachKeyValue((sourceType, propertyNames) ->
        {
            String sourceTypeExpression = (_class == sourceType) ? "tempFullTypeId" : PackageableElement.writeSystemPathForPackageableElement(new StringBuilder("\""), sourceType).append('"').toString();
            MutableList<String> properties = sourceType.getValueForMetaPropertyToMany(M3Properties.properties).asLazy()
                    .collect(Property::getPropertyName)
                    .select(propertyNames::contains, Lists.mutable.empty());
            MutableList<String> propertiesFromAssociations = sourceType.getValueForMetaPropertyToMany(M3Properties.propertiesFromAssociations).asLazy()
                    .collect(Property::getPropertyName)
                    .select(propertyNames::contains, Lists.mutable.empty());
            if (properties.size() + propertiesFromAssociations.size() != propertyNames.size())
            {
                throw new RuntimeException("Error dividing keys for " + PackageableElement.getUserPathForPackageableElement(sourceType) + " between properties and propertiesFromAssociations: " + propertyNames.toSortedList());
            }
            list.add(Tuples.pair(sourceTypeExpression, Tuples.pair(properties, propertiesFromAssociations)));
        });
        list.sortThisBy(Pair::getOne).forEach(pair ->
        {
            String sourceTypeExpression = pair.getOne();
            MutableList<String> properties = pair.getTwo().getOne();
            MutableList<String> propertiesFromAssociations = pair.getTwo().getTwo();
            if (properties.size() == 1)
            {
                builder.append("           .withKey(").append(sourceTypeExpression).append(", \"").append(properties.get(0)).append("\")\n");
            }
            else if (properties.notEmpty())
            {
                builder.append("           .withKeys(").append(sourceTypeExpression);
                properties.sortThis().appendString(builder, ", \"", "\", \"", "\")\n");
            }
            if (propertiesFromAssociations.size() == 1)
            {
                builder.append("           .withKeyFromAssociation(").append(sourceTypeExpression).append(", \"").append(propertiesFromAssociations.get(0)).append("\")\n");
            }
            else if (propertiesFromAssociations.notEmpty())
            {
                builder.append("           .withKeysFromAssociation(").append(sourceTypeExpression);
                propertiesFromAssociations.sortThis().appendString(builder, ", \"", "\", \"", "\")\n");
            }
        });
        return builder.append("           .build();\n");
    }

    static StringBuilder appendTypeVariables(StringBuilder builder, CoreInstance _class, String className, ProcessorSupport processorSupport, ProcessorContext processorContext)
    {
        _class.getValueForMetaPropertyToMany(M3Properties.typeVariables)
                .toSortedListBy(tv -> PrimitiveUtilities.getStringValue(tv.getValueForMetaPropertyToOne(M3Properties.name)))
                .forEach(typeVar ->
                {
                    String javaType = TypeProcessor.typeToJavaPrimitiveWithMul(typeVar.getValueForMetaPropertyToOne(M3Properties.genericType), Instance.getValueForMetaPropertyToOneResolved(typeVar, M3Properties.multiplicity, processorSupport), false, processorContext);
                    String name = "_" + PrimitiveUtilities.getStringValue(typeVar.getValueForMetaPropertyToOne(M3Properties.name));
                    builder.append("\n    ").append(javaType).append(" ").append(name).append(";\n");
                    builder.append("    public ").append(className).append(" ").append(name).append("(").append(javaType).append(" ").append(name).append(")\n")
                            .append("    {\n        this.").append(name).append(" = ").append(name).append(";\n        return this;\n    }\n");
                });
        return builder;
    }

    public static String buildSimpleConstructor(CoreInstance _class, String className, ProcessorSupport processorSupport, boolean usesInheritance)
    {
        return "    public " + className + "(String id)\n" +
                "    {\n" +
                "        super(id);\n" +
                (Type.isBottomType(_class, processorSupport) ? "        throw new org.finos.legend.pure.m3.exception.PureExecutionException(\"Cannot instantiate " + PackageableElement.getUserPathForPackageableElement(_class, "::") + "\");\n" : "") +
                "    }\n" +
                "\n";
    }

    private static String buildGetClassifier()
    {
        return "    @Override\n" +
                "    public CoreInstance getClassifier()\n" +
                "    {\n" +
                "        return this.classifier;\n" +
                "    }\n";
    }

    static String buildGetKeyIndex()
    {
        return "    @Override\n" +
                "    protected org.finos.legend.pure.m3.coreinstance.KeyIndex getKeyIndex()\n" +
                "    {\n" +
                "        return KEY_INDEX;\n" +
                "    }\n" +
                "\n";
    }

    static String buildFactoryConstructor(String className)
    {
        return "    public " + className + "(String name, SourceInformation sourceInformation, CoreInstance classifier)\n" +
                "    {\n" +
                "        this(name == null ? \"Anonymous_NoCounter\": name);\n" +
                "        this.setSourceInformation(sourceInformation);\n" +
                "        this.classifier = classifier;\n" +
                "    }\n" +
                "\n";
    }

    public static String buildGetValueForMetaPropertyToOne(CoreInstance classGenericType, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MutableList<CoreInstance> toOneProperties = processorSupport.class_getSimpleProperties(_class).select(p -> isToOne(p, processorSupport), Lists.mutable.empty()).sortThisBy(CoreInstance::getName);
        switch (toOneProperties.size())
        {
            case 0:
            {
                return "";
            }
            case 1:
            {
                String propertyName = toOneProperties.get(0).getName();
                return "    @Override\n" +
                        "    public CoreInstance getValueForMetaPropertyToOne(String keyName)\n" +
                        "    {\n" +
                        "        return \"" + propertyName + "\".equals(keyName) ? ValCoreInstance.toCoreInstance(_" + propertyName + "()) : super.getValueForMetaPropertyToOne(keyName);\n" +
                        "    }\n" +
                        "\n";
            }
            default:
            {
                return "    @Override\n" +
                        "    public CoreInstance getValueForMetaPropertyToOne(String keyName)\n" +
                        "    {\n" +
                        "        switch (keyName)\n" +
                        "        {\n" +
                        toOneProperties.collect(property ->
                                "            case \"" + property.getName() + "\":\n" +
                                        "            {\n" +
                                        "                return ValCoreInstance.toCoreInstance(_" + property.getName() + "());\n" +
                                        "            }\n").makeString("") +
                        "            default:\n" +
                        "            {\n" +
                        "                return super.getValueForMetaPropertyToOne(keyName);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n";
            }
        }
    }

    public static String buildGetValueForMetaPropertyToMany(CoreInstance classGenericType, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MutableList<CoreInstance> toManyProperties = processorSupport.class_getSimpleProperties(_class).reject(p -> isToOne(p, processorSupport), Lists.mutable.empty()).sortThisBy(CoreInstance::getName);
        switch (toManyProperties.size())
        {
            case 0:
            {
                return "";
            }
            case 1:
            {
                String propertyName = toManyProperties.get(0).getName();
                return "    @Override\n" +
                        "    public ListIterable<CoreInstance> getValueForMetaPropertyToMany(String keyName)\n" +
                        "    {\n" +
                        "        return \"" + propertyName + "\".equals(keyName) ? ValCoreInstance.toCoreInstances(_" + propertyName + "()) : super.getValueForMetaPropertyToMany(keyName);\n" +
                        "    }\n" +
                        "\n";
            }
            default:
            {
                return "    @Override\n" +
                        "    public ListIterable<CoreInstance> getValueForMetaPropertyToMany(String keyName)\n" +
                        "    {\n" +
                        "        switch (keyName)\n" +
                        "        {\n" +
                        toManyProperties.collect(property ->
                                "            case \"" + property.getName() + "\":\n" +
                                        "            {\n" +
                                        "                return ValCoreInstance.toCoreInstances(_" + property.getName() + "());\n" +
                                        "            }\n").makeString("") +
                        "            default:\n" +
                        "            {\n" +
                        "                return super.getValueForMetaPropertyToMany(keyName);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n";
            }
        }
    }

    public static String buildSetKeyValues(CoreInstance classGenericType, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MutableList<CoreInstance> allProperties = processorSupport.class_getSimpleProperties(_class).toSortedListBy(CoreInstance::getName);
        switch (allProperties.size())
        {
            case 0:
            {
                return "";
            }
            case 1:
            {
                String propertyName = allProperties.get(0).getName();
                return "    @Override\n" +
                        "    public void setKeyValues(ListIterable<String> key, ListIterable<? extends CoreInstance> value)\n" +
                        "    {\n" +
                        "        if (\"" + propertyName + "\".equals(key.getLast()))\n" +
                        "        {\n" +
                        "            _" + propertyName + "((RichIterable) ReflectiveCoreInstance.toJavaForInvocationCollection(value));\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        super.setKeyValues(key, value);\n" +
                        "    }\n" +
                        "\n";
            }
            default:
            {
                return "    @Override\n" +
                        "    public void setKeyValues(ListIterable<String> key, ListIterable<? extends CoreInstance> value)\n" +
                        "    {\n" +
                        "        String propertyName = key.getLast();\n" +
                        "        switch (propertyName)\n" +
                        "        {\n" +
                        allProperties.collect(property ->
                                "            case \"" + property.getName() + "\":\n" +
                                        "            {\n" +
                                        "                _" + property.getName() + "((RichIterable) ReflectiveCoreInstance.toJavaForInvocationCollection(value));\n" +
                                        "                return;\n" +
                                        "            }\n").makeString("") +
                        "            default:\n" +
                        "            {\n" +
                        "                super.setKeyValues(key, value);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n";
            }
        }
    }

    public static String buildAddKeyValue(CoreInstance classGenericType, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MutableList<CoreInstance> allProperties = processorSupport.class_getSimpleProperties(_class).toSortedListBy(CoreInstance::getName);
        switch (allProperties.size())
        {
            case 0:
            {
                return "";
            }
            case 1:
            {
                CoreInstance property = allProperties.get(0);
                String propertyName = property.getName();
                CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);
                String typeObject = TypeProcessor.typeToJavaObjectSingle(returnType, true, processorSupport);
                boolean toOne = isToOne(property, processorSupport);
                String call = toOne ? "_" + propertyName + "((" + typeObject + ") javaValue)" : "_" + propertyName + "Add((" + typeObject + ") javaValue)";
                return "    @Override\n" +
                        "    public void addKeyValue(ListIterable<String> key, CoreInstance value)\n" +
                        "    {\n" +
                        "        if (\"" + propertyName + "\".equals(key.getLast()))\n" +
                        "        {\n" +
                        "            Object javaValue = ReflectiveCoreInstance.toJavaForInvocation(value);\n" +
                        "            " + call + ";\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        super.addKeyValue(key, value);\n" +
                        "    }\n" +
                        "\n";
            }
            default:
            {
                return "    @Override\n" +
                        "    public void addKeyValue(ListIterable<String> key, CoreInstance value)\n" +
                        "    {\n" +
                        "        String propertyName = key.getLast();\n" +
                        "        Object javaValue = ReflectiveCoreInstance.toJavaForInvocation(value);\n" +
                        "        switch (propertyName)\n" +
                        "        {\n" +
                        allProperties.collect(property ->
                        {
                            String name = property.getName();
                            CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);
                            String typeObject = TypeProcessor.typeToJavaObjectSingle(returnType, true, processorSupport);
                            boolean toOne = isToOne(property, processorSupport);
                            String call = toOne ? "_" + name + "((" + typeObject + ") javaValue)" : "_" + name + "Add((" + typeObject + ") javaValue)";
                            return "            case \"" + name + "\":\n" +
                                    "            {\n" +
                                    "                " + call + ";\n" +
                                    "                return;\n" +
                                    "            }\n";
                        }).makeString("") +
                        "            default:\n" +
                        "            {\n" +
                        "                super.addKeyValue(key, value);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n";
            }
        }
    }

    public static String buildModifyValueForToManyMetaProperty(CoreInstance classGenericType, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MutableList<CoreInstance> toManyProperties = processorSupport.class_getSimpleProperties(_class).reject(p -> isToOne(p, processorSupport), Lists.mutable.empty()).sortThisBy(CoreInstance::getName);
        switch (toManyProperties.size())
        {
            case 0:
            {
                return "";
            }
            case 1:
            {
                String propertyName = toManyProperties.get(0).getName();
                return "    @Override\n" +
                        "    public void modifyValueForToManyMetaProperty(String key, int offset, CoreInstance value)\n" +
                        "    {\n" +
                        "        if (\"" + propertyName + "\".equals(key))\n" +
                        "        {\n" +
                        "            Object newValue = ReflectiveCoreInstance.toJavaForInvocation(value);\n" +
                        "            MutableList<Object> newValues;\n" +
                        "            RichIterable current = this._" + propertyName + ";\n" +
                        "            if (current == null || current.isEmpty())\n" +
                        "            {\n" +
                        "                newValues = Lists.mutable.empty();\n" +
                        "            }\n" +
                        "            else\n" +
                        "            {\n" +
                        "                newValues = Lists.mutable.withAll(current);\n" +
                        "            }\n" +
                        "            if (offset == 0 && newValues.isEmpty())\n" +
                        "            {\n" +
                        "                newValues.add(newValue);\n" +
                        "            }\n" +
                        "            else\n" +
                        "            {\n" +
                        "                newValues.set(offset, newValue);\n" +
                        "            }\n" +
                        "            _" + propertyName + "((RichIterable) newValues);\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        super.modifyValueForToManyMetaProperty(key, offset, value);\n" +
                        "    }\n" +
                        "\n";
            }
            default:
            {
                return "    @Override\n" +
                        "    public void modifyValueForToManyMetaProperty(String key, int offset, CoreInstance value)\n" +
                        "    {\n" +
                        "        switch (key)\n" +
                        "        {\n" +
                        toManyProperties.collect(property ->
                        {
                            String name = property.getName();
                            return "            case \"" + name + "\":\n" +
                                    "            {\n" +
                                    "                Object newValue = ReflectiveCoreInstance.toJavaForInvocation(value);\n" +
                                    "                MutableList<Object> newValues;\n" +
                                    "                RichIterable current = this._" + name + ";\n" +
                                    "                if (current == null || current.isEmpty())\n" +
                                    "                {\n" +
                                    "                    newValues = Lists.mutable.empty();\n" +
                                    "                }\n" +
                                    "                else\n" +
                                    "                {\n" +
                                    "                    newValues = Lists.mutable.withAll(current);\n" +
                                    "                }\n" +
                                    "                if (offset == 0 && newValues.isEmpty())\n" +
                                    "                {\n" +
                                    "                    newValues.add(newValue);\n" +
                                    "                }\n" +
                                    "                else\n" +
                                    "                {\n" +
                                    "                    newValues.set(offset, newValue);\n" +
                                    "                }\n" +
                                    "                _" + name + "((RichIterable) newValues);\n" +
                                    "                return;\n" +
                                    "            }\n";
                        }).makeString("") +
                        "            default:\n" +
                        "            {\n" +
                        "                super.modifyValueForToManyMetaProperty(key, offset, value);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n";
            }
        }
    }

    public static String buildRemoveProperty(CoreInstance classGenericType, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MutableList<CoreInstance> allProperties = processorSupport.class_getSimpleProperties(_class).toSortedListBy(CoreInstance::getName);
        switch (allProperties.size())
        {
            case 0:
            {
                return "";
            }
            case 1:
            {
                String propertyName = allProperties.get(0).getName();
                return "    @Override\n" +
                        "    public void removeProperty(String propertyName)\n" +
                        "    {\n" +
                        "        if (\"" + propertyName + "\".equals(propertyName))\n" +
                        "        {\n" +
                        "            _" + propertyName + "Remove();\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        super.removeProperty(propertyName);\n" +
                        "    }\n" +
                        "\n";
            }
            default:
            {
                return "    @Override\n" +
                        "    public void removeProperty(String propertyName)\n" +
                        "    {\n" +
                        "        switch (propertyName)\n" +
                        "        {\n" +
                        allProperties.collect(property ->
                                "            case \"" + property.getName() + "\":\n" +
                                        "            {\n" +
                                        "                _" + property.getName() + "Remove();\n" +
                                        "                return;\n" +
                                        "            }\n").makeString("") +
                        "            default:\n" +
                        "            {\n" +
                        "                super.removeProperty(propertyName);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n";
            }
        }
    }

    public static String buildSimpleProperties(CoreInstance classGenericType, FullPropertyImplementation propertyImpl, ProcessorContext processorContext, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MapIterable<String, CoreInstance> propertiesByName = processorSupport.class_getSimplePropertiesByName(_class);
        if (propertiesByName.isEmpty())
        {
            return "";
        }

        String ownerClassName = TypeProcessor.javaInterfaceForType(_class, processorSupport);
        String ownerTypeParams = ClassProcessor.typeParameters(_class);
        return Lists.mutable.<Pair<String, CoreInstance>>ofInitialCapacity(propertiesByName.size())
                .withAll(propertiesByName.keyValuesView())
                .sortThisBy(Pair::getOne)
                .asLazy()
                .collect(pair ->
                {
                    String name = pair.getOne();
                    CoreInstance property = pair.getTwo();
                    CoreInstance unresolvedReturnType = ClassProcessor.getPropertyUnresolvedReturnType(property, processorSupport);
                    CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);

                    CoreInstance returnMultiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);

                    boolean makePrimitiveIfPossible = GenericType.isGenericTypeConcrete(unresolvedReturnType) && Multiplicity.isToOne(returnMultiplicity, true);
                    String returnTypeJava = TypeProcessor.pureTypeToJava(returnType, true, makePrimitiveIfPossible, processorSupport);
                    CoreInstance classOwner = Instance.getValueForMetaPropertyToOneResolved(property.getValueForMetaPropertyToOne(M3Properties.classifierGenericType).getValueForMetaPropertyToMany(M3Properties.typeArguments).get(0), M3Properties.rawType, processorSupport);
                    String classOwnerId = processorContext.getIdBuilder().buildId(classOwner);
                    return propertyImpl.build(property, name, unresolvedReturnType, returnType, returnMultiplicity, returnTypeJava, classOwnerId, ownerClassName, ownerTypeParams, processorContext);
                })
                .makeString("", "\n", "\n");
    }

    public static String buildQualifiedProperties(CoreInstance classGenericType, ProcessorContext processorContext, ProcessorSupport processorSupport)
    {
        return appendQualifiedProperties(new StringBuilder(), classGenericType, processorContext, processorSupport).toString();
    }

    static StringBuilder appendQualifiedProperties(StringBuilder builder, CoreInstance classGenericType, ProcessorContext processorContext, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        MapIterable<String, CoreInstance> qualifiedPropertiesById = processorSupport.class_getQualifiedPropertiesByName(_class);
        return appendQualifiedProperties(builder, _class, qualifiedPropertiesById, processorContext);
    }

    static StringBuilder appendQualifiedProperties(StringBuilder builder, CoreInstance _class, MapIterable<String, CoreInstance> qualifiedPropertiesById, ProcessorContext processorContext)
    {
        if (qualifiedPropertiesById.notEmpty())
        {
            Lists.mutable.<Pair<String, CoreInstance>>ofInitialCapacity(qualifiedPropertiesById.size())
                    .withAll(qualifiedPropertiesById.keyValuesView())
                    .sortThisBy(Pair::getOne)
                    .forEach(pair -> appendQualifiedProperty(builder, _class, pair.getTwo(), processorContext).append("\n"));
        }
        return builder;
    }

    static StringBuilder appendQualifiedProperty(StringBuilder builder, CoreInstance _class, CoreInstance qualifiedProperty, ProcessorContext processorContext)
    {
        return builder.append("    public ").append(FunctionProcessor.functionSignature(qualifiedProperty, false, false, true, "", processorContext, true)).append("\n")
                .append("    {\n")
                .append("        ").append(FunctionProcessor.processFunctionDefinitionContent(_class, qualifiedProperty, true, processorContext, processorContext.getSupport())).append("\n")
                .append("    }\n");
    }

    public static String buildCopy(CoreInstance classGenericType, String suffix, boolean copyGetterOverride, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        String className = TypeProcessor.javaInterfaceForType(_class, processorSupport);
        String implClassName = JavaPackageAndImportBuilder.buildImplClassNameFromType(_class, suffix, processorSupport);
        String typeParams = ClassProcessor.typeParameters(_class);
        String classNamePlusTypeParams = className + (typeParams.isEmpty() ? "" : "<" + typeParams + "> ");

        StringBuilder sb = new StringBuilder(1024);
        sb.append("    public ").append(classNamePlusTypeParams).append(" copy()\n")
                .append("    {\n")
                .append("        return new ").append(implClassName).append("(this);\n")
                .append("    }\n");
        sb.append("    public ").append(implClassName).append("(").append(className);
        if (!typeParams.isEmpty())
        {
            sb.append("<").append(typeParams).append(">");
        }
        sb.append(" src)\n")
                .append("    {\n")
                .append("        this(\"Anonymous_NoCounter\");\n")
                .append("        this.classifier = ((").append(implClassName).append(")src).classifier;\n");
        if (copyGetterOverride)
        {
            sb.append("        this.__getterOverrideToOneExec = ((").append(implClassName).append(")src).__getterOverrideToOneExec;\n")
                    .append("        this.__getterOverrideToManyExec = ((").append(implClassName).append(")src).__getterOverrideToManyExec;\n");
        }
        CoreInstance associationClass = processorSupport.package_getByUserPath(M3Paths.Association);
        processorSupport.class_getSimpleProperties(_class).forEach(property ->
        {
            String name = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.name, processorSupport).getName();
            CoreInstance multiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);

            CoreInstance propertyOwner = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.owner, processorSupport);
            String reversePropertyName = null;
            if (Instance.instanceOf(propertyOwner, associationClass, processorSupport))
            {
                ListIterable<? extends CoreInstance> associationProperties = Instance.getValueForMetaPropertyToManyResolved(propertyOwner, M3Properties.properties, processorSupport);
                CoreInstance reverseProperty = associationProperties.get(property == associationProperties.get(0) ? 1 : 0);
                reversePropertyName = Property.getPropertyName(reverseProperty);
            }

            CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);
            String typeObject = TypeProcessor.typeToJavaObjectSingle(returnType, true, processorSupport);

            boolean isToOne = Multiplicity.isToOne(multiplicity, false);
            sb.append("        this._").append(name).append(" = ");
            if (isToOne)
            {
                sb.append("(").append(typeObject).append(")((").append(implClassName).append(")src)._").append(name);
            }
            else
            {
                sb.append("Lists.mutable.ofAll(((").append(implClassName).append(")src)._").append(name).append(")");
            }
            sb.append(";\n");
            if (reversePropertyName != null)
            {
                if (isToOne)
                {
                    sb.append("        if (this._").append(name).append(" != null)\n")
                            .append("        {\n")
                            .append("            this._").append(name).append("._reverse_").append(reversePropertyName).append("(this);\n")
                            .append("        }\n");
                }
                else
                {
                    sb.append("        for (").append(typeObject).append(" v : (RichIterable<? extends ").append(typeObject).append(">) this._").append(name).append(")\n")
                            .append("        {\n")
                            .append("            v._reverse_").append(reversePropertyName).append("(this);\n")
                            .append("        }\n");
                }
            }
        });
        sb.append("    }\n");
        return sb.toString();
    }

    static String buildEquality(CoreInstance classGenericType, boolean useMethodForHashcode, ProcessorContext processorContext, ProcessorSupport processorSupport)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        String className = TypeProcessor.javaInterfaceForType(_class, processorSupport);

        MutableList<CoreInstance> equalityProperties = _Class.collectEqualityKeyProperties(_class, processorContext.getSupport(), Lists.mutable.empty());
        if (equalityProperties.isEmpty())
        {
            return "";
        }
        equalityProperties.sortThisBy(CoreInstance::getName);

        StringBuilder sb = new StringBuilder(512);
        sb.append("    public boolean pureEquals(Object o)\n")
                .append("    {\n")
                .append("        if (this == o)\n        {\n            return true;\n        }\n")
                .append("        if (!(o instanceof ").append(className).append("))\n        {\n            return false;\n        }\n")
                .append("        ").append(className).append(" that = (").append(className).append(")o;\n")
                .append("        return this.getFullSystemPath().equals(that.getFullSystemPath())");
        for (CoreInstance property : equalityProperties)
        {
            CoreInstance functionType = processorSupport.function_getFunctionType(property);
            CoreInstance returnType = Instance.getValueForMetaPropertyToOneResolved(functionType, M3Properties.returnType, M3Properties.rawType, processorSupport);
            CoreInstance returnMultiplicity = Instance.getValueForMetaPropertyToOneResolved(functionType, M3Properties.returnMultiplicity, processorSupport);
            String propName = property.getName();
            if (returnType != null &&
                    Multiplicity.isToOne(returnMultiplicity, true) &&
                    Lists.immutable.with(M3Paths.Boolean, M3Paths.Float, M3Paths.Integer).contains(PackageableElement.getUserPathForPackageableElement(returnType)))
            {
                sb.append(" &&\n                this._").append(propName).append("() == that._").append(propName).append("()");
            }
            else
            {
                sb.append(" &&\n                CompiledSupport.equal(this._").append(propName).append("(), that._").append(propName).append("())");
            }
        }
        sb.append(";\n    }\n\n");

        sb.append("    public int pureHashCode()\n    {\n");
        for (int i = 0; i < equalityProperties.size(); i++)
        {
            String propName = equalityProperties.get(i).getName();
            if (i == 0)
            {
                sb.append("        int result = CompiledSupport.safeHashCode(this._").append(propName).append(useMethodForHashcode ? "()" : "").append(");\n");
            }
            else
            {
                sb.append("        result = 31 * result + CompiledSupport.safeHashCode(this._").append(propName).append(useMethodForHashcode ? "()" : "").append(");\n");
            }
        }
        sb.append("        return result;\n    }\n");
        return sb.toString();
    }

    public static String buildPropertyStandardWriteSeverReverseToOne(String name, String owner, String typePrimitive, boolean isPrimitive, boolean setCachedOrMutated)
    {
        return (isPrimitive ? "" :
                "\n" +
                        "    public void _reverse_" + name + "(" + typePrimitive + " val)\n" +
                        "    {\n" +
                        (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                        "        " + owner + "._" + name + " = val;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void _sever_reverse_" + name + "(" + typePrimitive + " val)\n" +
                        "    {\n" +
                        (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                        "        " + owner + "._" + name + " = null;\n" +
                        "    }\n") +
                "\n";
    }

    public static String buildPropertyStandardWriteToOneBuilders(CoreInstance property, CoreInstance propertyReturnGenericType, String name, String owner, String className, String typeObject, String defaultValue, String reversePropertyName, String typePrimitive, boolean setCachedOrMutated, ProcessorContext processorContext)
    {
        StringBuilder sb = new StringBuilder(512);
        sb.append(buildPropertyToOneSetOne(name, owner, className, reversePropertyName, typePrimitive, setCachedOrMutated));
        sb.append(buildPropertyToOneSetMany(name, className, typeObject));
        sb.append(buildPropertyToOneRemove(name, owner, className, defaultValue, setCachedOrMutated));
        sb.append(buildPropertyToOneSetterCoreInstance(property, propertyReturnGenericType, className, name, processorContext));
        return sb.toString();
    }

    private static String buildPropertyToOneSetOne(String name, String owner, String className, String reversePropertyName, String typePrimitive, boolean setCachedOrMutated)
    {
        return "    public " + className + " _" + name + "(" + typePrimitive + " val)\n" +
                "    {\n" +
                (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                (reversePropertyName == null ? "" : "        if (" + owner + "._" + name + " != null) {" + owner + "._" + name + "._sever_reverse_" + reversePropertyName + "(" + owner + ");}\n") +
                "        " + owner + "._" + name + " = val;\n" +
                (reversePropertyName == null ? "" : "        if (val != null) {val._reverse_" + reversePropertyName + "(" + owner + ");}\n") +
                "        return this;\n" +
                "    }\n" +
                "\n";
    }

    private static String buildPropertyToOneSetMany(String name, String className, String typeObject)
    {
        return "    public " + className + " _" + name + "(RichIterable<? extends " + typeObject + "> val)\n" +
                "    {\n" +
                "        return _" + name + "(val.getFirst());\n" +
                "    }\n" +
                "\n";
    }

    private static String buildPropertyToOneRemove(String name, String owner, String className, String defaultValue, boolean setCachedOrMutated)
    {
        return "    public " + className + " _" + name + "Remove()\n" +
                "    {\n" +
                (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                "        " + owner + "._" + name + " = " + defaultValue + ";\n" +
                "        return this;\n" +
                "    }\n" +
                "\n";
    }

    public static String buildPropertyStandardSeverReverseToMany(String name, String owner, String typePrimitive, boolean isPrimitive, boolean setCachedOrMutated)
    {
        return isPrimitive ? "" :
                "\n" +
                        "    public void _reverse_" + name + "(" + typePrimitive + " val)\n" +
                        "    {\n" +
                        (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                        "        if (!(" + owner + "._" + name + " instanceof MutableList))\n" +
                        "        {\n" +
                        "            " + owner + "._" + name + " = " + owner + "._" + name + ".toList();\n" +
                        "        }\n" +
                        "        ((MutableList)" + owner + "._" + name + ").add(val);\n" +
                        "    }\n" +
                        "\n" +
                        "    public void _sever_reverse_" + name + "(" + typePrimitive + " val)\n" +
                        "    {\n" +
                        (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                        "        if (!(" + owner + "._" + name + " instanceof MutableList))\n" +
                        "        {\n" +
                        "            " + owner + "._" + name + " = " + owner + "._" + name + ".toList();\n" +
                        "        }\n" +
                        "        ((MutableList)" + owner + "._" + name + ").remove(val);\n" +
                        "    }\n" +
                        "\n";

    }

    public static String buildPropertyStandardWriteToManyBuilders(CoreInstance property, CoreInstance propertyReturnGenericType, String name, String owner, String className, String typeObject, String reversePropertyName, String typePrimitive, CoreInstance rawType, boolean setCachedOrMutated, ProcessorSupport processorSupport, ProcessorContext processorContext)
    {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("    private ").append(className).append(" _").append(name).append("(").append(typePrimitive).append(" val, boolean add)\n    {\n");
        if (setCachedOrMutated)
        {
            sb.append("        ").append(owner).append("._").append(name).append("();\n");
        }
        if (rawType == null)
        {
            sb.append("if(val instanceof RichIterable){_").append(name).append("((RichIterable<? extends ").append(typeObject).append(">)val, add);}else{");
        }
        if (rawType != null && !processorSupport.type_isPrimitiveType(rawType))
        {
            sb.append("        if (val == null)\n        {\n            if (!add)\n            {\n");
            if (reversePropertyName != null)
            {
                sb.append("                for (").append(typeObject).append(" v : (RichIterable<? extends ").append(typeObject).append(">) ").append(owner).append("._").append(name).append(")\n")
                        .append("                {\n")
                        .append("                    v._sever_reverse_").append(reversePropertyName).append("(").append(owner).append(");\n")
                        .append("                }\n");
            }
            sb.append("                ").append(owner).append("._").append(name).append(" = Lists.mutable.empty();\n")
                    .append("            }\n            return this;\n        }\n");
        }
        sb.append("        if (add)\n        {\n")
                .append("            if (!(").append(owner).append("._").append(name).append(" instanceof MutableList))\n            {\n")
                .append("                ").append(owner).append("._").append(name).append(" = ").append(owner).append("._").append(name).append(".toList();\n")
                .append("            }\n")
                .append("            ((MutableList)").append(owner).append("._").append(name).append(").add(val);\n")
                .append("        }\n        else\n        {\n");
        if (reversePropertyName != null)
        {
            sb.append("            for (").append(typeObject).append(" v : (RichIterable<? extends ").append(typeObject).append(">) ").append(owner).append("._").append(name).append(")\n")
                    .append("            {\n")
                    .append("                v._sever_reverse_").append(reversePropertyName).append("(").append(owner).append(");\n")
                    .append("            }\n");
        }
        sb.append("            ").append(owner).append("._").append(name).append(" = (val == null ? Lists.mutable.empty() : Lists.mutable.with(val));\n")
                .append("        }\n");
        if (reversePropertyName != null)
        {
            sb.append("        val._reverse_").append(reversePropertyName).append("(").append(owner).append(");\n");
        }
        if (rawType == null)
        {
            sb.append("}");
        }
        sb.append("        return this;\n    }\n\n");

        // Second method: RichIterable variant
        sb.append("    private ").append(className).append(" _").append(name).append("(RichIterable<? extends ").append(typeObject).append("> val, boolean add)\n    {\n");
        if (setCachedOrMutated)
        {
            sb.append("        ").append(owner).append("._").append(name).append("();\n");
        }
        sb.append("        if (add)\n        {\n")
                .append("            if (!(").append(owner).append("._").append(name).append(" instanceof MutableList))\n            {\n")
                .append("                ").append(owner).append("._").append(name).append(" = ").append(owner).append("._").append(name).append(".toList();\n")
                .append("            }\n")
                .append("            ((MutableList)").append(owner).append("._").append(name).append(").addAllIterable(val);\n")
                .append("        }\n        else\n        {\n");
        if (reversePropertyName != null)
        {
            sb.append("            for (").append(typeObject).append(" v : (RichIterable<? extends ").append(typeObject).append(">) ").append(owner).append("._").append(name).append(")\n")
                    .append("            {\n")
                    .append("                v._sever_reverse_").append(reversePropertyName).append("(").append(owner).append(");\n")
                    .append("            }\n");
        }
        sb.append("            ").append(owner).append("._").append(name).append(" = val;\n")
                .append("        }\n");
        if (reversePropertyName != null)
        {
            sb.append("        for (").append(typeObject).append(" v : val)\n")
                    .append("        {\n")
                    .append("            v._reverse_").append(reversePropertyName).append("(").append(owner).append(");\n")
                    .append("        }\n");
        }
        sb.append("        return this;\n    }\n\n");

        sb.append(buildPropertyToManySetter(name, owner, className, typeObject));
        sb.append(buildPropertyToManyAdd(name, owner, className, typeObject));
        sb.append(buildPropertyToManyAddAll(name, owner, className, typeObject));
        sb.append(buildPropertyToManyRemove(name, owner, className, setCachedOrMutated));
        sb.append(buildPropertyToManyRemoveItem(name, owner, className, typeObject, setCachedOrMutated));
        if (processorContext.getGenerator().isStubType(property, propertyReturnGenericType))
        {
            sb.append(buildPropertyToManyAddCoreInstance(name, owner, className));
            sb.append(buildPropertyToManyAddAllCoreInstance(name, owner, className));
            sb.append(buildPropertyToManySetterCoreInstance(className, name));
            sb.append(buildPropertyToManyRemoveItemCoreInstance(className, name));
        }
        return sb.toString();
    }

    private static String buildPropertyToManySetter(String name, String owner, String className, String typeObject)
    {
        return "    public " + className + " _" + name + "(RichIterable<? extends " + typeObject + "> val)\n" +
                "    {\n" +
                "        return " + owner + "._" + name + "(val, false);\n" +
                "    }\n" +
                "\n";
    }

    private static String buildPropertyToManyAdd(String name, String owner, String className, String typeObject)
    {
        return "    public " + className + " _" + name + "Add(" + typeObject + " val)\n" +
                "    {\n" +
                "        return " + owner + "._" + name + "(Lists.immutable.with(val), true);\n" +
                "    }\n" +
                "\n";
    }

    private static String buildPropertyToManyAddAll(String name, String owner, String className, String typeObject)
    {
        return "    public " + className + " _" + name + "AddAll(RichIterable<? extends " + typeObject + "> val)\n" +
                "    {\n" +
                "        return " + owner + "._" + name + "(val, true);\n" +
                "    }\n" +
                "\n";
    }


    private static String buildPropertyToManyRemove(String name, String owner, String className, boolean setCachedOrMutated)
    {
        return "    public " + className + " _" + name + "Remove()\n" +
                "    {\n" +
                (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                "        " + owner + "._" + name + " = Lists.mutable.empty();\n" +
                "        return this;\n" +
                "    }\n" +
                "\n";
    }

    private static String buildPropertyToManyRemoveItem(String name, String owner, String className, String typeObject, boolean setCachedOrMutated)
    {
        return "    public " + className + " _" + name + "Remove(" + typeObject + " val)\n" +
                "    {\n" +
                (setCachedOrMutated ? "        " + owner + "._" + name + "();\n" : "") +
                "        if (!(" + owner + "._" + name + " instanceof MutableList))\n" +
                "        {\n" +
                "            " + owner + "._" + name + " = " + owner + "._" + name + ".toList();\n" +
                "        }\n" +
                "        ((MutableList)" + owner + "._" + name + ").remove(val);\n" +
                "        return this;\n" +
                "    }\n" +
                "\n";
    }

    public static String buildProperty(CoreInstance property, String className, String owner, String classOwnerId, String name, CoreInstance returnType, CoreInstance unresolvedReturnType, CoreInstance multiplicity, ProcessorSupport processorSupport, boolean includeGettor, ProcessorContext processorContext)
    {
        CoreInstance associationClass = processorSupport.package_getByUserPath(M3Paths.Association);
        CoreInstance propertyOwner = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.owner, processorSupport);
        String reversePropertyName = null;
        if (Instance.instanceOf(propertyOwner, associationClass, processorSupport))
        {
            ListIterable<? extends CoreInstance> associationProperties = Instance.getValueForMetaPropertyToManyResolved(propertyOwner, M3Properties.properties, processorSupport);
            CoreInstance reverseProperty = associationProperties.get(property == associationProperties.get(0) ? 1 : 0);
            reversePropertyName = Property.getPropertyName(reverseProperty);
        }

        CoreInstance rawType = Instance.getValueForMetaPropertyToOneResolved(returnType, M3Properties.rawType, processorSupport);
        boolean isOverrider = M3Properties.elementOverride.equals(name);
        boolean isClassifierGenericType = "classifierGenericType".equals(name);
        boolean isDataType = rawType != null && Instance.instanceOf(rawType, M3Paths.DataType, processorSupport);
        boolean isPrimitive = rawType != null && Instance.instanceOf(rawType, M3Paths.PrimitiveType, processorSupport);
        boolean makePrimitiveIfPossible = GenericType.isGenericTypeConcrete(unresolvedReturnType) && Multiplicity.isToOne(multiplicity, true);

        String typePrimitive = TypeProcessor.pureTypeToJava(returnType, true, makePrimitiveIfPossible, processorSupport);
        String typeObject = TypeProcessor.pureTypeToJava(returnType, true, false, processorSupport);
        String defaultValue = TypeProcessor.defaultValue(rawType);

        StringBuilder sb = new StringBuilder(1024);
        if (Multiplicity.isToOne(multiplicity, false))
        {
            sb.append(buildPropertyStandardWriteToOneBuilders(property, returnType, name, owner, className, typeObject, defaultValue, reversePropertyName, typePrimitive, false, processorContext));
            if (includeGettor)
            {
                sb.append(buildPropertyStandardWriteSeverReverseToOne(name, owner, typePrimitive, isPrimitive, false));
                sb.append(buildPropertyToOneGetterCoreInstance(property, returnType, name, processorContext));
                sb.append(buildPropertyToOneGetter(owner, classOwnerId, name, isOverrider, isClassifierGenericType, isDataType, typePrimitive));
            }
        }
        else
        {
            sb.append(buildPropertyStandardWriteToManyBuilders(property, returnType, name, owner, className, typeObject, reversePropertyName, typePrimitive, rawType, false, processorSupport, processorContext));
            if (includeGettor)
            {
                sb.append(buildPropertyStandardSeverReverseToMany(name, owner, typePrimitive, isPrimitive, false));
                sb.append(buildPropertyToManyGetter(owner, classOwnerId, name, isOverrider, isClassifierGenericType, isDataType, typePrimitive));
            }
            sb.append(buildPropertyToManyGetterCoreInstance(property, returnType, name, processorContext));
        }
        return sb.toString();
    }

    private static String buildPropertyToManyGetter(String owner, String classOwnerId, String name, boolean isOverrider, boolean isClassifierGenericType, boolean isDataType, String typeObject)
    {
        return "    public RichIterable<? extends " + typeObject + "> _" + name + "()\n" +
                "    {\n" +
                "        return " + owner + "._" + name + ";\n" +
                "    }\n";
    }

    private static String buildPropertyToOneGetter(String owner, String classOwnerId, String name, boolean isOverrider, boolean isClassifierGenericType, boolean isDataType, String typeObject)
    {
        return "    public " + typeObject + " _" + name + "()\n" +
                "    {\n" +
                "        return " + owner + "._" + name + ";\n" +
                "    }\n";
    }

    public static String buildPropertyToOneGetterCoreInstance(CoreInstance property, CoreInstance propertyReturnGenericType, String name, ProcessorContext processorContext)
    {
        return processorContext.getGenerator().requiresCoreInstanceMethods(property, propertyReturnGenericType) ?
                "    public org.finos.legend.pure.m4.coreinstance.CoreInstance _" + name + "CoreInstance()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                        "    }\n" +
                        "\n" : "";
    }

    public static String buildPropertyToManyGetterCoreInstance(CoreInstance property, CoreInstance propertyReturnGenericType, String name, ProcessorContext processorContext)
    {
        return processorContext.getGenerator().requiresCoreInstanceMethods(property, propertyReturnGenericType) ?
                "    public RichIterable<org.finos.legend.pure.m4.coreinstance.CoreInstance> _" + name + "CoreInstance()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                        "    }\n" +
                        "\n" : "";
    }

    public static String buildPropertyToOneSetterCoreInstance(CoreInstance property, CoreInstance propertyReturnGenericType, String className, String name, ProcessorContext processorContext)
    {
        return processorContext.getGenerator().isStubType(property, propertyReturnGenericType) ?
                "    public " + className + " _" + name + "CoreInstance(org.finos.legend.pure.m4.coreinstance.CoreInstance val)\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                        "    }\n" +
                        "\n" : "";
    }

    public static String buildPropertyToManySetterCoreInstance(String className, String name)
    {
        return "    public " + className + " _" + name + "CoreInstance(RichIterable<? extends org.finos.legend.pure.m4.coreinstance.CoreInstance> val)\n" +
                "    {\n" +
                "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                "    }\n" +
                "\n";
    }

    public static String buildPropertyToManyRemoveItemCoreInstance(String className, String name)
    {
        return "    public " + className + " _" + name + "RemoveCoreInstance(CoreInstance val)\n" +
                "    {\n" +
                "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                "    }\n" +
                "\n";
    }

    public static String buildPropertyToManyAddCoreInstance(String name, String owner, String className)
    {
        return "    public " + className + " _" + name + "AddCoreInstance(CoreInstance val)\n" +
                "    {\n" +
                "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                "    }\n" +
                "\n";
    }

    public static String buildPropertyToManyAddAllCoreInstance(String name, String owner, String className)
    {
        return "    public " + className + " _" + name + "AddAllCoreInstance(RichIterable<? extends CoreInstance> val)\n" +
                "    {\n" +
                "        throw new UnsupportedOperationException(\"Not supported in Compiled Mode at this time\");\n" +
                "    }\n" +
                "\n";
    }


    private static String getterOverrides(String classNamePlusTypeParams)
    {
        return "    private PureFunction2Wrapper __getterOverrideToOneExec;\n" +
                "    private PureFunction2Wrapper __getterOverrideToManyExec;\n" +
                "    public " + classNamePlusTypeParams + " __getterOverrideToOneExec(PureFunction2Wrapper f2)\n" +
                "    {\n" +
                "        this.__getterOverrideToOneExec = f2;" +
                "        return this;\n" +
                "    }\n" +
                "    public " + classNamePlusTypeParams + " __getterOverrideToManyExec(PureFunction2Wrapper f2)\n" +
                "    {\n" +
                "        this.__getterOverrideToManyExec = f2;" +
                "        return this;\n" +
                "    }\n" +
                "    public Object executeToOne(CoreInstance instance, String classId, String propertyName)\n" +
                "    {\n" +
                "        return this.__getterOverrideToOneExec.value(instance, Pure.getProperty(classId, propertyName,((CompiledExecutionSupport)__getterOverrideToOneExec.getExecutionSupport()).getMetadataAccessor()), __getterOverrideToOneExec.getExecutionSupport());\n" +
                "    }\n" +
                "    public ListIterable executeToMany(CoreInstance instance, String classId, String propertyName)\n" +
                "    {\n" +
                "        return (ListIterable)this.__getterOverrideToManyExec.value(instance, Pure.getProperty(classId, propertyName,((CompiledExecutionSupport)__getterOverrideToOneExec.getExecutionSupport()).getMetadataAccessor()), __getterOverrideToManyExec.getExecutionSupport());\n" +
                "    }\n";

    }

    public static String validate(boolean stateAndDeep, CoreInstance _class, String className, CoreInstance classGenericType, ProcessorContext processorContext, RichIterable<CoreInstance> properties, String extraParameters, String extraValues)
    {
        ProcessorSupport processorSupport = processorContext.getSupport();
        ListIterable<CoreInstance> allConstraints = _Class.computeConstraintsInHierarchy(_class, processorSupport);
        StringBuilder sb = new StringBuilder(1024);
        StringBuilder validateItems = new StringBuilder();

        sb.append("    public ");
        if (!stateAndDeep)
        {
            sb.append("static ");
        }
        sb.append(className).append(" _validate(");
        if (stateAndDeep)
        {
            sb.append("boolean goDeep,");
        }
        if (extraParameters != null)
        {
            sb.append(extraParameters).append(",");
        }
        sb.append(" SourceInformation sourceInformation, final ExecutionSupport es)\n    {\n");
        if (stateAndDeep)
        {
            sb.append("        if (!this.hasCompileState(CompiledSupport.CONSTRAINTS_VALIDATED))\n        {\n");
        }
        allConstraints.forEachWithIndex((constraint, index) ->
        {
            CoreInstance owner = Instance.getValueForMetaPropertyToOneResolved(constraint, M3Properties.owner, processorSupport);
            if (owner == null || "Global".equals(owner.getName()))
            {
                validateItems.append(validateItem(stateAndDeep, constraint, _class, processorContext, index)).append("\n");
                sb.append("            _validate_").append(index).append("(")
                        .append(extraValues == null ? "Lists.mutable.with(this)" : extraValues)
                        .append(", sourceInformation, es);\n");
            }
        });
        if (stateAndDeep)
        {
            sb.append("            this.addCompileState(CompiledSupport.CONSTRAINTS_VALIDATED);\n")
                    .append("            if (goDeep)\n            {\n");
        }
        properties.toSortedListBy(Property::getPropertyName).forEach(property ->
        {
            CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);
            CoreInstance rawType = Instance.getValueForMetaPropertyToOneResolved(returnType, M3Properties.rawType, processorSupport);
            if (rawType != null && !Instance.instanceOf(rawType, M3Paths.DataType, processorSupport) && !ClassProcessor.isPlatformClass(rawType))
            {
                String name = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.name, processorSupport).getName();
                CoreInstance returnMultiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);
                if (Multiplicity.isToOne(returnMultiplicity, false))
                {
                    sb.append("                if (this._").append(name).append("() != null)\n")
                            .append("                {\n")
                            .append("                    this._").append(name).append("()._validate(goDeep, sourceInformation, es);\n")
                            .append("                }\n");
                }
                else
                {
                    String returnTypeJava = TypeProcessor.pureTypeToJava(returnType, true, false, processorSupport);
                    sb.append("                for (").append(returnTypeJava).append(" o : this._").append(name).append("())\n")
                            .append("                {\n")
                            .append("                    o._validate(goDeep, sourceInformation, es);\n")
                            .append("                }\n");
                }
            }
        });
        if (stateAndDeep)
        {
            sb.append("            }\n        }\n");
        }
        sb.append("        return ").append(stateAndDeep ? "this" : "null").append(";\n    }\n");
        sb.append(validateItems);
        return sb.toString();
    }

    private static String validateItem(boolean stateAndDeep, CoreInstance constraint, CoreInstance _class, ProcessorContext processorContext, int constraintIndex)
    {
        ProcessorSupport processorSupport = processorContext.getSupport();
        CoreInstance owner = Instance.getValueForMetaPropertyToOneResolved(constraint, M3Properties.owner, processorSupport);
        if ((owner != null) && !"Global".equals(owner.getName()))
        {
            return "";
        }

        SetIterable<? extends CoreInstance> localConstraints = Sets.immutable.withAll(_class.getValueForMetaPropertyToMany(M3Properties.constraints));
        boolean registerLambdas = localConstraints.contains(constraint);
        String ruleId = StringEscapeUtils.escapeJava(PrimitiveUtilities.getStringValue(constraint.getValueForMetaPropertyToOne(M3Properties.name)));
        CoreInstance definition = Instance.getValueForMetaPropertyToOneResolved(constraint, M3Properties.functionDefinition, processorSupport);
        String eval = "(Boolean) " + ValueSpecificationProcessor.createFunctionForLambda(constraint, definition, registerLambdas, processorSupport, processorContext) + ".execute(vars,es)";
        CoreInstance message = Instance.getValueForMetaPropertyToOneResolved(constraint, M3Properties.messageFunction, processorSupport);
        String messageJavaFunction = (message == null) ? null : ValueSpecificationProcessor.createFunctionForLambda(constraint, message, registerLambdas, processorSupport, processorContext);

        CoreInstance expression = Instance.getValueForMetaPropertyToOneResolved(definition, M3Properties.expressionSequence, processorSupport);
        CoreInstance constraintClass = Instance.getValueForMetaPropertyToOneResolved(expression, M3Properties.usageContext, processorSupport).getValueForMetaPropertyToOne(M3Properties.type);
        String constraintName = StringEscapeUtils.escapeJava(PrimitiveUtilities.getStringValue(constraintClass.getValueForMetaPropertyToOne(M3Properties.name)));
        String errorMessage = (message == null) ?
                              ("\"Constraint :[" + ruleId + "] violated in the Class " + constraintName + "\"") :
                              ("\"Constraint :[" + ruleId + "] violated in the Class " + constraintName + ", Message: \" + (String) " + messageJavaFunction + ".execute(vars,es)");

        return
                "\n" +
                        "    public " + (stateAndDeep ? "" : "static ") + "void _validate_" + constraintIndex + "(ListIterable<?> vars, org.finos.legend.pure.m4.coreinstance.SourceInformation sourceInformation, final ExecutionSupport es)\n" +
                        "    {\n" +
                        "        if (!(" + eval + "))\n" +
                        "        {\n" +
                        "            throw new org.finos.legend.pure.m3.exception.PureExecutionException(sourceInformation, " + errorMessage + ");\n" +
                        "        }\n" +
                        "    }\n";
    }

    public static String buildGetFullSystemPath()
    {
        return "    @Override\n" +
                "    public String getFullSystemPath()\n" +
                "    {\n" +
                "         return tempFullTypeId;\n" +
                "    }\n";
    }

    private static boolean isToOne(CoreInstance property, ProcessorSupport processorSupport)
    {
        CoreInstance multiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);
        return Multiplicity.isToOne(multiplicity, false);
    }

    public static StringJavaSource buildOverrideImplementation(String _package, String imports, CoreInstance classGenericType, ProcessorContext processorContext, ProcessorSupport processorSupport, boolean useJavaInheritance)
    {
        CoreInstance _class = Instance.getValueForMetaPropertyToOneResolved(classGenericType, M3Properties.rawType, processorSupport);
        String implClassName = JavaPackageAndImportBuilder.buildImplClassNameFromType(_class, CLASS_IMPL_SUFFIX, processorSupport);
        String overrideClassName = JavaPackageAndImportBuilder.buildImplClassNameFromType(_class, CLASS_OVERRIDE_IMPL_SUFFIX, processorSupport);
        String typeParams = ClassProcessor.typeParameters(_class);
        String typeParamsString = typeParams.isEmpty() ? "" : "<" + typeParams + ">";
        String overrideClassNamePlusTypeParams = overrideClassName + typeParamsString;
        String interfaceName = TypeProcessor.javaInterfaceForType(_class, processorSupport);
        String interfaceNamePlusTypeParams = interfaceName + typeParamsString;

        CoreInstance associationClass = processorSupport.package_getByUserPath(M3Paths.Association);

        // Build override getters for properties that need them
        StringBuilder overrideGetters = new StringBuilder();
        MapIterable<String, CoreInstance> propertiesByName = processorSupport.class_getSimplePropertiesByName(_class);
        if (propertiesByName.notEmpty())
        {
            Lists.mutable.<Pair<String, CoreInstance>>ofInitialCapacity(propertiesByName.size())
                    .withAll(propertiesByName.keyValuesView())
                    .sortThisBy(Pair::getOne)
                    .forEach(pair ->
                    {
                        String name = pair.getOne();
                        CoreInstance property = pair.getTwo();
                        CoreInstance unresolvedReturnType = ClassProcessor.getPropertyUnresolvedReturnType(property, processorSupport);
                        CoreInstance returnType = ClassProcessor.getPropertyResolvedReturnType(classGenericType, property, processorSupport);
                        CoreInstance returnMultiplicity = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.multiplicity, processorSupport);

                        CoreInstance rawType = Instance.getValueForMetaPropertyToOneResolved(returnType, M3Properties.rawType, processorSupport);
                        boolean isOverrider = M3Properties.elementOverride.equals(name);
                        boolean isClassifierGenericType = "classifierGenericType".equals(name);
                        boolean isDataType = rawType != null && Instance.instanceOf(rawType, M3Paths.DataType, processorSupport);

                        // Only generate override getters for properties that had the override check before
                        if (isDataType || isOverrider || isClassifierGenericType)
                        {
                            return;
                        }

                        CoreInstance propertyOwner = Instance.getValueForMetaPropertyToOneResolved(property, M3Properties.owner, processorSupport);
                        boolean includeGetter = !useJavaInheritance || propertyOwner == _class || Instance.instanceOf(propertyOwner, associationClass, processorSupport);
                        if (!includeGetter)
                        {
                            return;
                        }

                        CoreInstance classOwner = Instance.getValueForMetaPropertyToOneResolved(property.getValueForMetaPropertyToOne(M3Properties.classifierGenericType).getValueForMetaPropertyToMany(M3Properties.typeArguments).get(0), M3Properties.rawType, processorSupport);
                        String classOwnerId = PackageableElement.getSystemPathForPackageableElement(classOwner);

                        boolean makePrimitiveIfPossible = GenericType.isGenericTypeConcrete(unresolvedReturnType) && Multiplicity.isToOne(returnMultiplicity, true);
                        String typeObject = Multiplicity.isToOne(returnMultiplicity, false) ?
                                TypeProcessor.pureTypeToJava(returnType, true, makePrimitiveIfPossible, processorSupport) :
                                TypeProcessor.pureTypeToJava(returnType, true, false, processorSupport);

                        if (Multiplicity.isToOne(returnMultiplicity, false))
                        {
                            overrideGetters.append(buildOverridePropertyToOneGetter(classOwnerId, name, typeObject));
                        }
                        else
                        {
                            overrideGetters.append(buildOverridePropertyToManyGetter(classOwnerId, name, typeObject));
                        }
                    });
        }

        // Build copy method that returns _OverrideImpl
        String classNamePlusTypeParams = interfaceName + (typeParams.isEmpty() ? "" : "<" + typeParams + "> ");

        StringBuilder sb = new StringBuilder(4096);
        sb.append(IMPORTS).append(imports);
        sb.append("public class ").append(overrideClassNamePlusTypeParams).append(" extends ").append(implClassName).append(typeParamsString).append("\n{\n");
        sb.append("    public ").append(overrideClassName).append("(String id)\n")
                .append("    {\n")
                .append("        super(id);\n")
                .append("    }\n\n");
        sb.append("    public ").append(overrideClassName).append("(").append(interfaceName);
        if (!typeParams.isEmpty())
        {
            sb.append("<").append(typeParams).append(">");
        }
        sb.append(" src)\n")
                .append("    {\n")
                .append("        super(src);\n")
                .append("    }\n\n");
        sb.append("    @Override\n")
                .append("    public ").append(classNamePlusTypeParams).append(" copy()\n")
                .append("    {\n")
                .append("        return new ").append(overrideClassName).append("(this);\n")
                .append("    }\n\n");
        sb.append(overrideGetters);
        sb.append("}");
        return StringJavaSource.newStringJavaSource(_package, overrideClassName, sb.toString());
    }

    private static String buildOverridePropertyToOneGetter(String classOwnerId, String name, String typeObject)
    {
        return "    @Override\n" +
                "    public " + typeObject + " _" + name + "()\n" +
                "    {\n" +
                "        return this._elementOverride() == null || !GetterOverrideExecutor.class.isInstance(this._elementOverride()) ? this._" + name + " : (" + typeObject + ")((GetterOverrideExecutor)this._elementOverride()).executeToOne(this, \"" + classOwnerId + "\", \"" + name + "\");\n" +
                "    }\n";
    }

    private static String buildOverridePropertyToManyGetter(String classOwnerId, String name, String typeObject)
    {
        return "    @Override\n" +
                "    public RichIterable<? extends " + typeObject + "> _" + name + "()\n" +
                "    {\n" +
                "        return this._elementOverride() == null || !GetterOverrideExecutor.class.isInstance(this._elementOverride()) ? this._" + name + " : (RichIterable<? extends " + typeObject + ">)((GetterOverrideExecutor)this._elementOverride()).executeToMany(this, \"" + classOwnerId + "\", \"" + name + "\");\n" +
                "    }\n";
    }

    public interface FullPropertyImplementation
    {
        String build(CoreInstance property, String name, CoreInstance unresolvedReturnType, CoreInstance returnType, CoreInstance returnMultiplicity, String returnTypeJava, String classOwnerId, String ownerClassName, String ownerTypeParams, ProcessorContext processorContext);
    }
}
