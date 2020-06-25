package org.apache.parquet.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.Log;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.io.InvalidRecordException;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link WriteSupport} for writing JSON from Jackson JsonNode.
 */
public class JsonWriteSupport<T extends JsonNode> extends WriteSupport<T> {

  public static final String JS_CLASS_WRITE = "parquet.json.writeClass";
  private static final Logger LOG = LoggerFactory.getLogger(JsonWriteSupport.class);

  private RecordConsumer recordConsumer;
  private ObjectSchema objectSchema;
  private MessageWriter messageWriter;

  public JsonWriteSupport() {

  }

  public JsonWriteSupport(ObjectSchema objSchema) {
    this.objectSchema = objSchema;
  }

  @Override
  public String getName() {
    return "json";
  }

  @Override
  public WriteContext init(Configuration configuration) {
    MessageType rootSchema = new JsonSchemaConverter().convert(objectSchema);
    this.messageWriter = new MessageWriter(objectSchema, rootSchema);
    Map<String, String> extraMetaData = new HashMap<String, String>();
    return new WriteContext(rootSchema, extraMetaData);
  }

  @Override
  public void prepareForWrite(RecordConsumer recordConsumer) {
    this.recordConsumer = recordConsumer;
  }

  @Override
  public void write(T record) {
    recordConsumer.startMessage();

    try {
      messageWriter.writeTopLevelMessage(record);
    } catch (RuntimeException e) {
      LOG.error("Cannot write message " + e.getMessage() + " : " + record);
      throw e;
    }

    recordConsumer.endMessage();
  }

  private FieldWriter unknownType(Schema fieldDescriptor) {
    String exceptionMsg = "Unknown type with descriptor \"" + fieldDescriptor
      + "\" and type \"" + fieldDescriptor.getType() + "\".";
    throw new InvalidRecordException(exceptionMsg);
  }

  class MessageWriter extends FieldWriter {
    final FieldWriter[] fieldWriters;

    @SuppressWarnings("unchecked")
    MessageWriter(ObjectSchema objectSchema, GroupType schema) {
      int fieldsSize = objectSchema.getProperties().entrySet().size();
      fieldWriters = (FieldWriter[]) Array.newInstance(FieldWriter.class, fieldsSize);

      int fieldIndex = 0;
      for (Map.Entry<String, Schema> field : objectSchema.getProperties().entrySet()) {

        String name = field.getKey();
        Type type = schema.getType(name);
        FieldWriter writer = createWriter(field.getValue(), type);

        writer.setFieldName(name);
        writer.setIndex(fieldIndex);

        fieldWriters[fieldIndex] = writer;

        fieldIndex++;
      }

    }

    private FieldWriter createWriter(Schema field, Type type) {

      if (field instanceof StringSchema) {
        return new StringWriter();
        //todo: add check for: date, date-time, uuid
      }
      else if (field instanceof IntegerSchema) {
        return new IntWriter();
      }
      //todo: all other cases

      return unknownType(field); //should not be executed, always throws exception.
    }

    /**
     * Writes top level message. It cannot call startGroup()
     */
    void writeTopLevelMessage(Object value) {
      writeAllFields((JsonNode) value);
    }

    private void writeAllFields(JsonNode pb) {

      int fieldIndex = 0;
      for (Map.Entry<String, Schema> field : objectSchema.getProperties().entrySet()) {

        fieldName = field.getKey();

        LOG.info("Looking for {}", fieldName);
        System.out.println("Looking for "+fieldName);

        if (pb.has(fieldName)) {
          JsonNode node = pb.get(fieldName);

          if (!node.isMissingNode()) {
            LOG.debug("Writting field {}", fieldName);
            fieldWriters[fieldIndex].writeField(pb.get(fieldName));
          }
        }

        // todo: decide if we write default value instead, in which case we need to call the
        // schema part of the entryset
        fieldIndex++; //todo: compute some index based on schema

      }
    }

  }

  class FieldWriter {
    String fieldName;
    int index = -1;

    void setFieldName(String fieldName) {
      this.fieldName = fieldName;
    }

    /**
     * sets index of field inside parquet message.
     */
    //todo: is this always needed?
    void setIndex(int index) {
      this.index = index;
    }

    /**
     * Used for writing repeated fields
     */
    void writeRawValue(Object value) {

    }

    void writeField(Object value) {
      recordConsumer.startField(fieldName, index);
      writeRawValue(value);
      recordConsumer.endField(fieldName, index);
    }

  }

  class StringWriter extends FieldWriter {
    @Override
    final void writeRawValue(Object value) {
      JsonNode node = (JsonNode) value;

      if (node.isTextual()) {
        Binary binaryString = Binary.fromString(node.asText());
        recordConsumer.addBinary(binaryString);
      } else {
        LOG.error("Type {} not expected in StringWriter", node.getNodeType());
      }
    }
  }

  class IntWriter extends FieldWriter {
    @Override
    void writeRawValue(Object value) {
      JsonNode node = (JsonNode) value;

      if (node.isInt()) {
        recordConsumer.addInteger(node.asInt());
      } else {
        LOG.error("Type {} not expected in IntWriter", node.getNodeType());
      }
    }
  }



}
