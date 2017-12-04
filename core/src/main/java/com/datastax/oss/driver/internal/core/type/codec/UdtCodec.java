/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.type.codec;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class UdtCodec implements TypeCodec<UdtValue> {

  private final UserDefinedType cqlType;

  public UdtCodec(UserDefinedType cqlType) {
    this.cqlType = cqlType;
  }

  @Override
  public GenericType<UdtValue> getJavaType() {
    return GenericType.UDT_VALUE;
  }

  @Override
  public DataType getCqlType() {
    return cqlType;
  }

  @Override
  public boolean accepts(Object value) {
    return value instanceof UdtValue && ((UdtValue) value).getType().equals(cqlType);
  }

  @Override
  public boolean accepts(Class<?> javaClass) {
    return UdtValue.class.isAssignableFrom(javaClass);
  }

  @Override
  public ByteBuffer encode(UdtValue value, ProtocolVersion protocolVersion) {
    if (value == null) {
      return null;
    }
    if (!value.getType().equals(cqlType)) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid user defined type, expected %s but got %s", cqlType, value.getType()));
    }
    // Encoding: each field as a [bytes] value ([bytes] = int length + contents, null is
    // represented by -1)
    int toAllocate = 0;
    int size = cqlType.getFieldTypes().size();
    for (int i = 0; i < size; i++) {
      ByteBuffer field = value.getBytesUnsafe(i);
      toAllocate += 4 + (field == null ? 0 : field.remaining());
    }
    ByteBuffer result = ByteBuffer.allocate(toAllocate);
    for (int i = 0; i < value.size(); i++) {
      ByteBuffer field = value.getBytesUnsafe(i);
      if (field == null) {
        result.putInt(-1);
      } else {
        result.putInt(field.remaining());
        result.put(field.duplicate());
      }
    }
    return (ByteBuffer) result.flip();
  }

  @Override
  public UdtValue decode(ByteBuffer bytes, ProtocolVersion protocolVersion) {
    if (bytes == null) {
      return null;
    }
    // empty byte buffers will result in empty values
    try {
      ByteBuffer input = bytes.duplicate();
      UdtValue value = cqlType.newValue();
      int i = 0;
      while (input.hasRemaining()) {
        if (i > cqlType.getFieldTypes().size()) {
          throw new IllegalArgumentException(
              String.format(
                  "Too many fields in encoded UDT value, expected %d",
                  cqlType.getFieldTypes().size()));
        }
        int elementSize = input.getInt();
        ByteBuffer element;
        if (elementSize == -1) {
          element = null;
        } else {
          element = input.slice();
          element.limit(elementSize);
          input.position(input.position() + elementSize);
        }
        value.setBytesUnsafe(i, element);
        i += 1;
      }
      return value;
    } catch (BufferUnderflowException e) {
      throw new IllegalArgumentException("Not enough bytes to deserialize a UDT value", e);
    }
  }

  @Override
  public String format(UdtValue value) {
    if (value == null) {
      return "NULL";
    }

    CodecRegistry registry = cqlType.getAttachmentPoint().codecRegistry();

    StringBuilder sb = new StringBuilder("{");
    int size = cqlType.getFieldTypes().size();
    boolean first = true;
    for (int i = 0; i < size; i++) {
      if (first) {
        first = false;
      } else {
        sb.append(",");
      }
      CqlIdentifier elementName = cqlType.getFieldNames().get(i);
      sb.append(elementName.asCql(true));
      sb.append(":");
      DataType elementType = cqlType.getFieldTypes().get(i);
      TypeCodec<Object> codec = registry.codecFor(elementType);
      sb.append(codec.format(value.get(i, codec)));
    }
    sb.append("}");
    return sb.toString();
  }

  @Override
  public UdtValue parse(String value) {
    if (value == null || value.isEmpty() || value.equalsIgnoreCase("NULL")) {
      return null;
    }

    UdtValue udt = cqlType.newValue();

    int position = ParseUtils.skipSpaces(value, 0);
    if (value.charAt(position++) != '{') {
      throw new IllegalArgumentException(
          String.format(
              "Cannot parse UDT value from \"%s\", at character %d expecting '{' but got '%c'",
              value, position, value.charAt(position)));
    }

    position = ParseUtils.skipSpaces(value, position);

    if (value.charAt(position) == '}') {
      return udt;
    }

    CodecRegistry registry = cqlType.getAttachmentPoint().codecRegistry();

    while (position < value.length()) {
      int n;
      try {
        n = ParseUtils.skipCQLId(value, position);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot parse UDT value from \"%s\", cannot parse a CQL identifier at character %d",
                value, position),
            e);
      }
      CqlIdentifier id = CqlIdentifier.fromInternal(value.substring(position, n));
      position = n;

      if (!cqlType.contains(id))
        throw new IllegalArgumentException(
            String.format("Unknown field %s in value \"%s\"", id, value));

      position = ParseUtils.skipSpaces(value, position);
      if (value.charAt(position++) != ':')
        throw new IllegalArgumentException(
            String.format(
                "Cannot parse UDT value from \"%s\", at character %d expecting ':' but got '%c'",
                value, position, value.charAt(position)));
      position = ParseUtils.skipSpaces(value, position);

      try {
        n = ParseUtils.skipCQLValue(value, position);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot parse UDT value from \"%s\", invalid CQL value at character %d",
                value, position),
            e);
      }

      String fieldValue = value.substring(position, n);
      // This works because ids occur at most once in UDTs
      DataType fieldType = cqlType.getFieldTypes().get(cqlType.firstIndexOf(id));
      TypeCodec<Object> codec = registry.codecFor(fieldType);
      udt.set(id, codec.parse(fieldValue), codec);
      position = n;

      position = ParseUtils.skipSpaces(value, position);
      if (value.charAt(position) == '}') {
        return udt;
      }
      if (value.charAt(position) != ',') {
        throw new IllegalArgumentException(
            String.format(
                "Cannot parse UDT value from \"%s\", at character %d expecting ',' but got '%c'",
                value, position, value.charAt(position)));
      }
      ++position; // skip ','

      position = ParseUtils.skipSpaces(value, position);
    }
    throw new IllegalArgumentException(
        String.format("Malformed UDT value \"%s\", missing closing '}'", value));
  }
}