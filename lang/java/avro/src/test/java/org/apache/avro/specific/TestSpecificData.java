/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.avro.specific;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericData;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.junit.Before;
import org.junit.Test;

/*
 * If integerClass is primitive, reflection to find method will
 * result in a NoSuchMethodException in the case of a UNION schema
 */
public class TestSpecificData {

  private Class<?> intClass;
  private Class<?> integerClass;

  @Before
  public void setUp() {
    Schema intSchema = Schema.create(Type.INT);
    intClass = SpecificData.get().getClass(intSchema);
    Schema nullSchema = Schema.create(Type.NULL);
    Schema nullIntUnionSchema = Schema.createUnion(Arrays.asList(nullSchema, intSchema));
    integerClass = SpecificData.get().getClass(nullIntUnionSchema);
  }

  @Test
  public void testClassTypes() {
    assertTrue(intClass.isPrimitive());
    assertFalse(integerClass.isPrimitive());
  }

  @Test
  public void testPrimitiveParam() throws Exception {
    assertNotNull(Reflection.class.getMethod("primitive", intClass));
  }

  @Test(expected = NoSuchMethodException.class)
  public void testPrimitiveParamError() throws Exception {
    Reflection.class.getMethod("primitiveWrapper", intClass);
  }

  @Test
  public void testPrimitiveWrapperParam() throws Exception {
    assertNotNull(Reflection.class.getMethod("primitiveWrapper", integerClass));
  }

  @Test(expected = NoSuchMethodException.class)
  public void testPrimitiveWrapperParamError() throws Exception {
    Reflection.class.getMethod("primitive", integerClass);
  }

  static class Reflection {
    public void primitive(int i) {
    }

    public void primitiveWrapper(Integer i) {
    }
  }

  public static class TestRecord extends SpecificRecordBase {
    private static final Schema SCHEMA = Schema.createRecord("TestRecord", null, null, false);
    static {
      List<Field> fields = new ArrayList<>();
      fields.add(new Field("x", Schema.create(Type.INT), null, null));
      Schema stringSchema = Schema.create(Type.STRING);
      GenericData.setStringType(stringSchema, GenericData.StringType.String);
      fields.add(new Field("y", stringSchema, null, null));
      SCHEMA.setFields(fields);
    }
    private int x;
    private String y;

    @Override
    public void put(int i, Object v) {
      switch (i) {
      case 0:
        x = (Integer) v;
        break;
      case 1:
        y = (String) v;
        break;
      default:
        throw new RuntimeException();
      }
    }

    @Override
    public Object get(int i) {
      switch (i) {
      case 0:
        return x;
      case 1:
        return y;
      }
      throw new RuntimeException();
    }

    @Override
    public Schema getSchema() {
      return SCHEMA;
    }

  }

  @Test
  public void testSpecificRecordBase() {
    final TestRecord record = new TestRecord();
    record.put("x", 1);
    record.put("y", "str");
    assertEquals(1, record.get("x"));
    assertEquals("str", record.get("y"));
  }

  @Test
  public void testExternalizeable() throws Exception {
    final TestRecord before = new TestRecord();
    before.put("x", 1);
    before.put("y", "str");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream(bytes);
    out.writeObject(before);
    out.close();

    ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    TestRecord after = (TestRecord) in.readObject();

    assertEquals(before, after);
  }

  /** Tests that non Stringable datum are rejected by specific writers. */
  @Test
  public void testNonStringable() throws Exception {
    final Schema string = Schema.create(Type.STRING);
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final Encoder encoder = EncoderFactory.get().directBinaryEncoder(baos, null);
    final DatumWriter<Object> writer = new SpecificDatumWriter<>(string);
    try {
      writer.write(new Object(), encoder);
      fail("Non stringable object should be rejected.");
    } catch (ClassCastException cce) {
      // Expected error
    }
  }

  private static final String DENIED_CLASS = "javax.management.BadAttributeValueExpException";

  private static Object read(SpecificDatumReader<Object> reader, String javaClass, String value) throws IOException {
    String json = "{\"type\":\"string\",\"avro.java.string\":\"String\",\"java-class\":\"" + javaClass + "\"}";
    Schema schema = new Schema.Parser().parse(json);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Encoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
    encoder.writeString(value);
    encoder.flush();
    reader.setSchema(schema);
    return reader.read(null, DecoderFactory.get().binaryDecoder(baos.toByteArray(), null));
  }

  private static void assertForbidden(SpecificDatumReader<Object> reader, String javaClass) throws IOException {
    try {
      read(reader, javaClass, "value");
      fail("Expected SecurityException for java-class " + javaClass);
    } catch (SecurityException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(javaClass));
    }
  }

  @Test
  public void testDeniedClassDefaultConstructor() throws Exception {
    assertForbidden(new SpecificDatumReader<>(), DENIED_CLASS);
  }

  @Test
  public void testDeniedClassSpecificDataConstructor() throws Exception {
    SpecificDatumReader<Object> reader = new SpecificDatumReader<>(SpecificData.get());
    assertTrue(reader.getUntrustedPackages().contains(DENIED_CLASS));
    assertForbidden(reader, DENIED_CLASS);
  }

  @Test
  public void testDeniedPackageEntry() throws Exception {
    // org.mozilla.javascript is a package entry; the probe lives under it
    String probe = "org.mozilla.javascript.DenyListProbe";
    assertTrue(Arrays.asList(SpecificDatumReader.DENY_LIST).contains("org.mozilla.javascript"));
    assertForbidden(new SpecificDatumReader<>(), probe);
    assertForbidden(new SpecificDatumReader<>(SpecificData.get()), probe);
  }

  @Test
  public void testUrlClassLoaderEntry() throws Exception {
    assertTrue(Arrays.asList(SpecificDatumReader.DENY_LIST).contains("java.net.URLClassLoader"));
    assertForbidden(new SpecificDatumReader<>(), "java.net.URLClassLoader");
  }

  @Test
  public void testAllowedClassStillDeserializes() throws Exception {
    SpecificDatumReader<Object> dataReader = new SpecificDatumReader<>(SpecificData.get());
    assertEquals(new BigDecimal("1.5"), read(new SpecificDatumReader<>(), "java.math.BigDecimal", "1.5"));
    assertEquals(new BigDecimal("2.5"), read(dataReader, "java.math.BigDecimal", "2.5"));
  }
}
