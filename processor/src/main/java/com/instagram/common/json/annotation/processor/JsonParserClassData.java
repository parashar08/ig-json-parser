// Copyright 2004-present Facebook. All Rights Reserved.

package com.instagram.common.json.annotation.processor;

import javax.annotation.processing.Messager;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.instagram.common.json.JsonAnnotationProcessorConstants;
import com.instagram.common.json.annotation.JsonField;
import com.instagram.common.json.annotation.JsonType;
import com.instagram.common.json.annotation.util.Console;
import com.instagram.common.json.annotation.util.ProcessorClassData;
import com.instagram.common.json.annotation.util.TypeUtils;

import com.squareup.javawriter.JavaWriter;

import static javax.lang.model.element.Modifier.*;

/**
 * This collects the data about the fields of a class, and generates the java code to parse the
 * object.
 */
public class JsonParserClassData extends ProcessorClassData<String, TypeData> {

  private final boolean mAbstractClass;
  private final boolean mPostprocessingEnabled;
  private final String mParentInjectedClassName;

  public JsonParserClassData(
      String classPackage, String className, String injectedClassName,
      AnnotationRecordFactory<String, TypeData> factory,
      boolean abstractClass,
      boolean postprocessingEnabled,
      String parentInjectedClassName) {
    super(classPackage, className, injectedClassName, factory);
    mAbstractClass = abstractClass;
    mPostprocessingEnabled = postprocessingEnabled;
    mParentInjectedClassName = parentInjectedClassName;
  }

  @Override
  public String getJavaCode(final Messager messager) {
    StringWriter sw = new StringWriter();
    JavaWriter writer = new JavaWriter(sw);

    try {
      writer.emitPackage(mClassPackage);

      writer.emitImports(
          "java.io.IOException",
          "java.io.StringWriter",
          "java.util.ArrayDeque",
          "java.util.ArrayList",
          "java.util.List",
          "java.util.Queue",
          "com.fasterxml.jackson.core.JsonGenerator",
          "com.fasterxml.jackson.core.JsonParser",
          "com.fasterxml.jackson.core.JsonToken",
          "com.instagram.common.json.JsonFactoryHolder"
      );

      writer.beginType(mInjectedClassName, "class", EnumSet.of(PUBLIC, FINAL));

      String returnValue = mPostprocessingEnabled ?
          ("instance." + JsonType.POSTPROCESSING_METHOD_NAME + "()") : "instance";

      if (!mAbstractClass) {
        writer
              .beginMethod(
                  mClassName,
                  "parseFromJson",
                  EnumSet.of(PUBLIC, STATIC, FINAL),
                  Arrays.asList("JsonParser", "jp"),
                  Arrays.asList("IOException"))
                .emitStatement("%s instance = new %s()", mClassName, mClassName)
                .emitSingleLineComment("validate that we're on the right token")
                .beginControlFlow("if (jp.getCurrentToken() != JsonToken.START_OBJECT)")
                  .emitStatement("jp.skipChildren()")
                  .emitStatement("return null")
                .endControlFlow()
                .beginControlFlow("while (jp.nextToken() != JsonToken.END_OBJECT)")
                  .emitStatement("String fieldName = jp.getCurrentName()")
                  .emitStatement("jp.nextToken()")
                  .emitStatement("processSingleField(instance, fieldName, jp)")
                  // always skip children.  if we expected an array or an object, we would have
                  // consumed the START_ARRAY or START_OBJECT.  therefore, we would only skip
                  // forward if we're seeing something unexpected.
                  .emitStatement("jp.skipChildren()")
                .endControlFlow()
                .emitStatement("return %s", returnValue)
              .endMethod()
              .emitEmptyLine();
      }

      writer
          .beginMethod(
              "boolean",
              "processSingleField",
              EnumSet.of(PUBLIC, STATIC, FINAL),
              Arrays.asList(mClassName, "instance", "String", "fieldName", "JsonParser", "jp"),
              Arrays.asList("IOException"))
          .emitWithGenerator(
              new JavaWriter.JavaGenerator() {
                @Override
                public void emitJava(JavaWriter writer) throws IOException {
                  JsonParserClassData.this.writeFields(messager, writer);

                  // if we reached here, we need to call the superclasses processSingleField
                  // method.
                  if (mParentInjectedClassName != null) {
                    writer.emitStatement("return %s.processSingleField(instance, fieldName, jp)",
                        mParentInjectedClassName);
                  } else {
                    writer.emitStatement("return false");
                  }
                }
              })
          .endMethod()
          .emitEmptyLine();

      if (!mAbstractClass) {
        writer
              .beginMethod(
                  mClassName,
                  "parseFromJson",
                  EnumSet.of(PUBLIC, STATIC, FINAL),
                  Arrays.asList("String", "inputString"),
                  Arrays.asList("IOException"))
              .emitStatement(
                  "JsonParser jp = JsonFactoryHolder.APP_FACTORY.createParser(inputString)")
              .emitStatement("jp.nextToken()")
              .emitStatement("return parseFromJson(jp)")
              .endMethod()
              .emitEmptyLine();
      }

      writer
            .beginMethod(
                "void",
                "serializeToJson",
                EnumSet.of(PUBLIC, STATIC, FINAL),
                Arrays.asList("JsonGenerator", "generator",
                    mClassName, "object",
                    "boolean", "writeStartAndEnd"),
                Arrays.asList("IOException"))
              .beginControlFlow("if (writeStartAndEnd)")
                .emitStatement("generator.writeStartObject()")
              .endControlFlow()
              .emitWithGenerator(
                  new JavaWriter.JavaGenerator() {
                    @Override
                    public void emitJava(JavaWriter writer) throws IOException {
                      JsonParserClassData.this.writeSerializeCalls(messager, writer);

                      // if we have a superclass, we need to call its serialize method.
                      if (mParentInjectedClassName != null) {
                        writer.emitStatement(mParentInjectedClassName +
                                ".serializeToJson(generator, object, false)");
                      }

                    }
                  })
              .beginControlFlow("if (writeStartAndEnd)")
                .emitStatement("generator.writeEndObject()")
              .endControlFlow()
            .endMethod()
            .emitEmptyLine();

      if (!mAbstractClass) {
        writer
            .beginMethod(
                "String",
                "serializeToJson",
                EnumSet.of(PUBLIC, STATIC, FINAL),
                Arrays.asList(mClassName, "object"),
                Arrays.asList("IOException"))
            .emitStatement("StringWriter stringWriter = new StringWriter()")
            .emitStatement(
                "JsonGenerator generator = " +
                    "JsonFactoryHolder.APP_FACTORY.createGenerator(stringWriter)")
            .emitStatement("serializeToJson(generator, object, true)")
            .emitStatement("generator.close()")
            .emitStatement("return stringWriter.toString()")
            .endMethod()
            .emitEmptyLine();
      }

      writer.endType();
    } catch (IOException ex) {
      Console.error(
          messager, "IOException while generating %s: %s",
          mInjectedClassName, ex.toString());
    }

    return sw.toString();
  }

  /**
   * This writes the if-else block for the fields in this class.
   * <p/>
   * NOTE: This could be optimized further by building a radix trie, and building out the if-else
   * block from traversing the radix trie.
   */
  private void writeFields(Messager messager, JavaWriter writer) throws IOException {
    boolean firstEntry = true;
    for (Map.Entry<String, TypeData> entry : getIterator()) {
      TypeData data = entry.getValue();

      if (firstEntry) {
        writer.beginControlFlow("if (\"" + data.getFieldName() + "\".equals(fieldName))");
      } else {
        writer.nextControlFlow("else if (\"" + data.getFieldName() + "\".equals(fieldName))");
      }

      if (data.getCollectionType() != TypeUtils.CollectionType.NOT_A_COLLECTION) {
        generateArrayParser(messager, writer, data);
        String assignmentFormatter = data.getAssignmentFormatter();
        if (StringUtil.isNullOrEmpty(assignmentFormatter)) {
          assignmentFormatter = DEFAULT_ASSIGNMENT_FORMATTER;
        }
        writer.emitStatement(
            StrFormat.createStringFormatter(assignmentFormatter)
                .addParam("object_varname", "instance")
                .addParam("field_varname", entry.getKey())
                .addParam("extracted_value", "results")
                .format());
      } else {
        String rValue = generateExtractRvalue(data);
        String assignmentFormatter = data.getAssignmentFormatter();
        if (StringUtil.isNullOrEmpty(assignmentFormatter)) {
          assignmentFormatter = DEFAULT_ASSIGNMENT_FORMATTER;
        }
        writer.emitStatement(
            StrFormat.createStringFormatter(assignmentFormatter)
                .addParam("object_varname", "instance")
                .addParam("field_varname", entry.getKey())
                .addParam("extracted_value", rValue)
                .format());
      }

      writer.emitStatement("return true");

      firstEntry = false;
    }

    if (firstEntry == false) {
      writer.endControlFlow();
    }
  }

  /**
   * This writes the code to properly parse an array.
   */
  private void generateArrayParser(Messager messager, JavaWriter writer, TypeData data)
      throws IOException {
    String innerType = getJavaType(messager, data);
    String interfaceType = mapCollectionTypeToInterfaceType(data.getCollectionType());
    String concreteType = mapCollectionTypeToConcreteType(data.getCollectionType());

    writer.emitStatement("%s<%s> results = null", interfaceType, innerType)
          .beginControlFlow("if (jp.getCurrentToken() == JsonToken.START_ARRAY)")
            .emitStatement("results = new %s<%s>()", concreteType, innerType)
            .beginControlFlow("while (jp.nextToken() != JsonToken.END_ARRAY)")
              .emitStatement("%s parsed = %s", innerType, generateExtractRvalue(data))
              .beginControlFlow("if (parsed != null)")
                .emitStatement("results.add(parsed)")
              .endControlFlow()
            .endControlFlow()
          .endControlFlow();
  }

  /**
   * We allow consumers of this library to override how we interact with the jackson to get the
   * value.  This generates the code to generate the rvalue expression.
   */
  private String generateExtractRvalue(TypeData data) {
    String valueExtractFormatter = data.getValueExtractFormatter();
    if (StringUtil.isNullOrEmpty(valueExtractFormatter)) {
      if (data.getParseType() == TypeUtils.ParseType.PARSABLE_OBJECT) {
        valueExtractFormatter = PARSABLE_OBJECT_VALUE_EXTRACT_FORMATTER;
      } else {
        if (data.getMapping() == JsonField.TypeMapping.EXACT) {
          valueExtractFormatter = sExactFormatters.get(data.getParseType());
        } else if (data.getMapping() == JsonField.TypeMapping.COERCED) {
          valueExtractFormatter = sCoercedFormatters.get(data.getParseType());
        }
      }
    }

    return StrFormat.createStringFormatter(valueExtractFormatter)
        .addParam("parser_object", "jp")
        .addParam("subobject_helper_class",
            data.getParsableTypeParserClass() +
                JsonAnnotationProcessorConstants.HELPER_CLASS_SUFFIX)
        .format();
  }

  private String getJavaType(Messager messager, TypeData type) {
    if (type.getParseType() == TypeUtils.ParseType.PARSABLE_OBJECT) {
      return type.getParsableType();
    }

    String javaType = sJavaTypes.get(type.getParseType());
    if (javaType != null) {
      return javaType;
    }

    throw new IllegalStateException(
        "Could not divine java type for " + type.getFieldName() + " in class " + mClassName);
  }

  // These are all the default formatters.
  private static String DEFAULT_ASSIGNMENT_FORMATTER =
      "${object_varname}.${field_varname} = ${extracted_value}";
  private static String PARSABLE_OBJECT_VALUE_EXTRACT_FORMATTER =
      "${subobject_helper_class}.parseFromJson(${parser_object})";

  private static Map<TypeUtils.ParseType, String> sExactFormatters =
      new HashMap<TypeUtils.ParseType, String>();
  private static Map<TypeUtils.ParseType, String> sCoercedFormatters =
      new HashMap<TypeUtils.ParseType, String>();
  private static Map<TypeUtils.ParseType, String> sJavaTypes =
      new HashMap<TypeUtils.ParseType, String>();

  static {
    sExactFormatters.put(TypeUtils.ParseType.BOOLEAN, "${parser_object}.getBooleanValue()");
    sExactFormatters.put(TypeUtils.ParseType.BOOLEAN_OBJECT,
        "((${parser_object}.getCurrentToken() == JsonToken.VALUE_TRUE || " +
          "${parser_object}.getCurrentToken() == JsonToken.VALUE_FALSE) ? " +
            "Boolean.valueOf(${parser_object}.getValueAsBoolean()) : null)");
    sExactFormatters.put(TypeUtils.ParseType.INTEGER, "${parser_object}.getIntValue()");
    sExactFormatters.put(TypeUtils.ParseType.INTEGER_OBJECT,
        "(${parser_object}.getCurrentToken() == JsonToken.VALUE_NUMBER_INT ? " +
            "Integer.valueOf(${parser_object}.getValueAsInt()) : null)");
    sExactFormatters.put(TypeUtils.ParseType.LONG, "${parser_object}.getLongValue()");
    sExactFormatters.put(TypeUtils.ParseType.LONG_OBJECT,
        "(${parser_object}.getCurrentToken() == JsonToken.VALUE_NUMBER_INT ? " +
            "Long.valueOf(${parser_object}.getValueAsLong()) : null)");
    sExactFormatters.put(TypeUtils.ParseType.FLOAT, "${parser_object}.getFloatValue()");
    sExactFormatters.put(TypeUtils.ParseType.FLOAT_OBJECT,
        "((${parser_object}.getCurrentToken() == JsonToken.VALUE_NUMBER_FLOAT || " +
          "${parser_object}.getCurrentToken() == JsonToken.VALUE_NUMBER_INT) ? " +
            "new Float(${parser_object}.getValueAsDouble()) : null)");
    sExactFormatters.put(TypeUtils.ParseType.DOUBLE, "${parser_object}.getDoubleValue()");
    sExactFormatters.put(TypeUtils.ParseType.DOUBLE_OBJECT,
        "((${parser_object}.getCurrentToken() == JsonToken.VALUE_NUMBER_FLOAT || " +
            "${parser_object}.getCurrentToken() == JsonToken.VALUE_NUMBER_INT) ? " +
            "Double.valueOf(${parser_object}.getValueAsDouble()) : null)");
    sExactFormatters.put(TypeUtils.ParseType.STRING,
        "(${parser_object}.getCurrentToken() == JsonToken.VALUE_STRING ? ${parser_object}.getText() : null)");

    sCoercedFormatters.put(TypeUtils.ParseType.BOOLEAN, "${parser_object}.getValueAsBoolean()");
    sCoercedFormatters.put(
        TypeUtils.ParseType.BOOLEAN_OBJECT, "Boolean.valueOf(${parser_object}.getValueAsBoolean())");
    sCoercedFormatters.put(TypeUtils.ParseType.INTEGER, "${parser_object}.getValueAsInt()");
    sCoercedFormatters.put(TypeUtils.ParseType.INTEGER_OBJECT,
        "Integer.valueOf(${parser_object}.getValueAsInt())");
    sCoercedFormatters.put(TypeUtils.ParseType.LONG, "${parser_object}.getValueAsLong()");
    sCoercedFormatters.put(TypeUtils.ParseType.LONG_OBJECT,
        "Long.valueOf(${parser_object}.getValueAsLong())");
    sCoercedFormatters.put(TypeUtils.ParseType.FLOAT,
        "((float) ${parser_object}.getValueAsDouble())");
    sCoercedFormatters.put(TypeUtils.ParseType.FLOAT_OBJECT,
        "new Float(${parser_object}.getValueAsDouble())");
    sCoercedFormatters.put(TypeUtils.ParseType.DOUBLE, "${parser_object}.getValueAsDouble()");
    sCoercedFormatters.put(
        TypeUtils.ParseType.DOUBLE_OBJECT, "Double.valueOf(${parser_object}.getValueAsDouble())");
    sCoercedFormatters.put(TypeUtils.ParseType.STRING,
        "(${parser_object}.getCurrentToken() == JsonToken.VALUE_NULL ? null : ${parser_object}.getText())");

    sJavaTypes.put(TypeUtils.ParseType.BOOLEAN_OBJECT, "Boolean");
    sJavaTypes.put(TypeUtils.ParseType.INTEGER_OBJECT, "Integer");
    sJavaTypes.put(TypeUtils.ParseType.LONG_OBJECT, "Long");
    sJavaTypes.put(TypeUtils.ParseType.FLOAT_OBJECT, "Float");
    sJavaTypes.put(TypeUtils.ParseType.DOUBLE_OBJECT, "Double");
    sJavaTypes.put(TypeUtils.ParseType.STRING, "String");
  }

  /**
   * This writes the code to serialize this class to a JsonGenerator.
   */
  private void writeSerializeCalls(Messager messager, JavaWriter writer) throws IOException {
    for (Map.Entry<String, TypeData> entry : getIterator()) {
      TypeData data = entry.getValue();
      String serializeCode = data.getSerializeCodeFormatter();

      if (data.getCollectionType() != TypeUtils.CollectionType.NOT_A_COLLECTION) {
        if (StringUtil.isNullOrEmpty(serializeCode)) {
          if (data.getParseType() == TypeUtils.ParseType.PARSABLE_OBJECT) {
            serializeCode = PARSABLE_OBJECT_ARRAY_SERIALIZE_CALL;
          } else {
            serializeCode = mArraySerializeCalls.get(data.getParseType());
          }
        }

        // needed to do a typecast for erased types
        String interfaceType = mapCollectionTypeToInterfaceType(data.getCollectionType());
        String listType = getJavaType(messager, entry.getValue());

        writer
            .beginControlFlow("if (object." + entry.getKey() + " != null)")
              .emitStatement("generator.writeFieldName(\"%s\")", data.getFieldName())
              .emitStatement("generator.writeStartArray()")
              .beginControlFlow("for (" + listType +
                  " element : (" + interfaceType + "<" + listType + ">)" +
                  "object." + entry.getKey() + ")")
                .beginControlFlow("if (element != null)")
                  .emitStatement(
                      StrFormat.createStringFormatter(serializeCode)
                        .addParam("generator_object", "generator")
                        .addParam("iterator", "element")
                        .addParam("subobject_helper_class",
                            data.getParsableTypeParserClass() +
                                JsonAnnotationProcessorConstants.HELPER_CLASS_SUFFIX)
                        .format())
                .endControlFlow()
              .endControlFlow()
              .emitStatement("generator.writeEndArray()")
            .endControlFlow();
      } else {
        if (data.getParseType() == TypeUtils.ParseType.PARSABLE_OBJECT) {
          if (StringUtil.isNullOrEmpty(serializeCode)) {
            serializeCode = PARSABLE_OBJECT_SERIALIZE_CALL;
          }
          writer
              .beginControlFlow("if (object." + entry.getKey() + " != null)")
                .emitStatement("generator.writeFieldName(\"%s\")", data.getFieldName())
                .emitStatement(
                    StrFormat.createStringFormatter(serializeCode)
                        .addParam("generator_object", "generator")
                        .addParam("object_varname", "object")
                        .addParam("field_varname", entry.getKey())
                        .addParam("subobject_helper_class",
                            data.getParsableTypeParserClass() +
                                JsonAnnotationProcessorConstants.HELPER_CLASS_SUFFIX)
                        .format())
              .endControlFlow();
        } else {
          if (StringUtil.isNullOrEmpty(serializeCode)) {
            serializeCode = mScalarSerializeCalls.get(data.getParseType());
          }

          String statement =
              StrFormat.createStringFormatter(serializeCode)
                  .addParam("generator_object", "generator")
                  .addParam("object_varname", "object")
                  .addParam("field_varname", entry.getKey())
                  .addParam("json_fieldname", data.getFieldName())
                  .format();

          switch (data.getParseType()) {
            case BOOLEAN:
            case INTEGER:
            case LONG:
            case FLOAT:
            case DOUBLE:
              writer.emitStatement(statement);
              break;

            default:
              writer
                  .beginControlFlow("if (object." + entry.getKey() + " != null)")
                  .emitStatement(statement)
                  .endControlFlow();
          }
        }
      }
    }
  }

  /**
   * used to write a single instance of a parsable object.
   */
  private static final String PARSABLE_OBJECT_SERIALIZE_CALL =
      "${subobject_helper_class}.serializeToJson(${generator_object}, ${object_varname}.${field_varname}, true)";
  private static final String PARSABLE_OBJECT_ARRAY_SERIALIZE_CALL =
      "${subobject_helper_class}.serializeToJson(${generator_object}, ${iterator}, true)";

  private static Map<TypeUtils.ParseType, String> mScalarSerializeCalls =
      new HashMap<TypeUtils.ParseType, String>();
  private static Map<TypeUtils.ParseType, String> mArraySerializeCalls =
      new HashMap<TypeUtils.ParseType, String>();
  static {
    mScalarSerializeCalls.put(TypeUtils.ParseType.BOOLEAN,
        "${generator_object}.writeBooleanField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.BOOLEAN_OBJECT,
        "${generator_object}.writeBooleanField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.INTEGER,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.INTEGER_OBJECT,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.LONG,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.LONG_OBJECT,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.FLOAT,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.FLOAT_OBJECT,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.DOUBLE,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.DOUBLE_OBJECT,
        "${generator_object}.writeNumberField(\"${json_fieldname}\", ${object_varname}.${field_varname})");
    mScalarSerializeCalls.put(TypeUtils.ParseType.STRING,
        "${generator_object}.writeStringField(\"${json_fieldname}\", ${object_varname}.${field_varname})");

    mArraySerializeCalls.put(TypeUtils.ParseType.BOOLEAN,
        "${generator_object}.writeBoolean(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.BOOLEAN_OBJECT,
        "${generator_object}.writeBoolean(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.INTEGER,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.INTEGER_OBJECT,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.LONG,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.LONG_OBJECT,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.FLOAT,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.FLOAT_OBJECT,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.DOUBLE,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.DOUBLE_OBJECT,
        "${generator_object}.writeNumber(${iterator})");
    mArraySerializeCalls.put(TypeUtils.ParseType.STRING,
        "${generator_object}.writeString(${iterator})");

  }

  private String mapCollectionTypeToInterfaceType(TypeUtils.CollectionType collectionType) {
    switch (collectionType) {
      case LIST:
        return "List";
      case QUEUE:
        return "Queue";
    }
    throw new IllegalStateException("unknown collection type");
  }

  private String mapCollectionTypeToConcreteType(TypeUtils.CollectionType collectionType) {
    switch (collectionType) {
      case LIST:
        return "ArrayList";
      case QUEUE:
        return "ArrayDeque";
    }
    throw new IllegalStateException("unknown collection type");
  }
}
