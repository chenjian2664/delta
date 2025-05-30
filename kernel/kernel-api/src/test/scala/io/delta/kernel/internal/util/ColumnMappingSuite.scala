/*
 * Copyright (2023) The Delta Lake Project Authors.
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
package io.delta.kernel.internal.util

import java.util

import scala.collection.JavaConverters.mapAsJavaMapConverter

import io.delta.kernel.exceptions.KernelException
import io.delta.kernel.expressions.Column
import io.delta.kernel.internal.actions.Metadata
import io.delta.kernel.internal.util.ColumnMapping._
import io.delta.kernel.internal.util.ColumnMapping.ColumnMappingMode._
import io.delta.kernel.types._

import org.assertj.core.api.Assertions.{assertThat, assertThatNoException, assertThatThrownBy}
import org.assertj.core.util.Maps
import org.scalatest.funsuite.AnyFunSuite

class ColumnMappingSuite extends AnyFunSuite with ColumnMappingSuiteBase {
  test("column mapping is only enabled on known mapping modes") {
    assertThat(ColumnMapping.isColumnMappingModeEnabled(null)).isFalse
    assertThat(ColumnMapping.isColumnMappingModeEnabled(NONE)).isFalse
    assertThat(ColumnMapping.isColumnMappingModeEnabled(NAME)).isTrue
    assertThat(ColumnMapping.isColumnMappingModeEnabled(ID)).isTrue
  }

  test("column mapping change with empty config") {
    assertThatNoException.isThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        new util.HashMap(),
        new util.HashMap(),
        true /* isNewTable */ ))
  }

  test("column mapping mode change is allowed") {
    val isNewTable = true
    assertThatNoException.isThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        new util.HashMap(),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NAME.toString),
        isNewTable))

    assertThatNoException.isThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NONE.toString),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NAME.toString),
        isNewTable))

    assertThatNoException.isThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, ID.toString),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, ID.toString),
        isNewTable))

    assertThatNoException.isThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NAME.toString),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NAME.toString),
        isNewTable))
  }

  test("column mapping mode change not allowed on existing table") {
    val isNewTable = false
    assertThatThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NAME.toString),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, ID.toString),
        isNewTable))
      .isInstanceOf(classOf[IllegalArgumentException])
      .hasMessage("Changing column mapping mode from 'name' to 'id' is not supported")

    assertThatThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, ID.toString),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, NAME.toString),
        isNewTable))
      .isInstanceOf(classOf[IllegalArgumentException])
      .hasMessage("Changing column mapping mode from 'id' to 'name' is not supported")

    assertThatThrownBy(() =>
      ColumnMapping.verifyColumnMappingChange(
        new util.HashMap(),
        Maps.newHashMap(COLUMN_MAPPING_MODE_KEY, ID.toString),
        isNewTable))
      .isInstanceOf(classOf[IllegalArgumentException])
      .hasMessage("Changing column mapping mode from 'none' to 'id' is not supported")
  }

  test("finding max column id with different schemas") {
    assertThat(ColumnMapping.findMaxColumnId(new StructType)).isEqualTo(0)

    assertThat(ColumnMapping.findMaxColumnId(
      new StructType()
        .add("a", StringType.STRING, true)
        .add("b", IntegerType.INTEGER, true)))
      .isEqualTo(0)

    assertThat(ColumnMapping.findMaxColumnId(
      new StructType()
        .add("a", StringType.STRING, createMetadataWithFieldId(14))
        .add("b", IntegerType.INTEGER, createMetadataWithFieldId(17))
        .add("c", IntegerType.INTEGER, createMetadataWithFieldId(3))))
      .isEqualTo(17)

    // nested columns are currently not supported
    assertThat(ColumnMapping.findMaxColumnId(
      new StructType().add("a", StringType.STRING, createMetadataWithFieldId(14))
        .add(
          "b",
          new StructType()
            .add("d", IntegerType.INTEGER, true)
            .add("e", IntegerType.INTEGER, true),
          createMetadataWithFieldId(15))
        .add("c", IntegerType.INTEGER, createMetadataWithFieldId(7))))
      .isEqualTo(15)
  }

  test("finding max column id with nested struct type") {
    val nestedStruct = new StructType()
      .add("d", IntegerType.INTEGER, createMetadataWithFieldId(3))
      .add("e", IntegerType.INTEGER, createMetadataWithFieldId(4))

    val schema = new StructType()
      .add("a", StringType.STRING, createMetadataWithFieldId(1))
      .add("b", nestedStruct, createMetadataWithFieldId(2))
      .add("c", IntegerType.INTEGER, createMetadataWithFieldId(5))

    assertThat(ColumnMapping.findMaxColumnId(schema)).isEqualTo(5)
  }

  test("finding max column id with nested struct type and random ids") {
    val nestedStruct = new StructType()
      .add("d", IntegerType.INTEGER, createMetadataWithFieldId(2))
      .add("e", IntegerType.INTEGER, createMetadataWithFieldId(1))

    val schema = new StructType()
      .add("a", StringType.STRING, createMetadataWithFieldId(3))
      .add("b", nestedStruct, createMetadataWithFieldId(4))
      .add("c", IntegerType.INTEGER, createMetadataWithFieldId(5))

    assertThat(ColumnMapping.findMaxColumnId(schema)).isEqualTo(5)
  }

  test("finding max column id with nested array type") {
    val nestedStruct = new StructType()
      .add("e", IntegerType.INTEGER, createMetadataWithFieldId(4))
      .add("f", IntegerType.INTEGER, createMetadataWithFieldId(5))

    val nestedMeta = FieldMetadata.builder()
      .putLong(COLUMN_MAPPING_ID_KEY, 2)
      .putFieldMetadata(
        COLUMN_MAPPING_NESTED_IDS_KEY,
        FieldMetadata.builder().putLong("b.element", 6).build())
      .build()

    val schema = new StructType()
      .add("a", StringType.STRING, createMetadataWithFieldId(1))
      .add("b", new ArrayType(new StructField("d", nestedStruct, false)), nestedMeta)
      .add("c", IntegerType.INTEGER, createMetadataWithFieldId(3))

    assertThat(ColumnMapping.findMaxColumnId(schema)).isEqualTo(6)
  }

  test("finding max column id with nested map type") {
    val nestedStruct = new StructType()
      .add("e", IntegerType.INTEGER, createMetadataWithFieldId(4))
      .add("f", IntegerType.INTEGER, createMetadataWithFieldId(5))

    val nestedMeta = FieldMetadata.builder()
      .putLong(COLUMN_MAPPING_ID_KEY, 2)
      .putFieldMetadata(
        COLUMN_MAPPING_NESTED_IDS_KEY,
        FieldMetadata.builder()
          .putLong("b.key", 11)
          .putLong("b.value", 12).build())
      .build()

    val schema = new StructType()
      .add("a", StringType.STRING, createMetadataWithFieldId(1))
      .add(
        "b",
        new MapType(
          IntegerType.INTEGER,
          new StructField("d", nestedStruct, false).getDataType,
          false),
        nestedMeta)
      .add("c", IntegerType.INTEGER, createMetadataWithFieldId(3))

    assertThat(ColumnMapping.findMaxColumnId(schema)).isEqualTo(12)
  }

  private val testingSchema = new StructType()
    .add("a", StringType.STRING)
    .add(
      "b",
      new StructType()
        .add("c", DoubleType.DOUBLE)
        .add("d", DateType.DATE))
    .add("e", FloatType.FLOAT)
    .add(
      "f",
      new StructType()
        .add(
          "g",
          new StructType()
            .add("h", TimestampNTZType.TIMESTAMP_NTZ)))
    .add("i", new MapType(StringType.STRING, DoubleType.DOUBLE, false))
    .add("j", new ArrayType(StringType.STRING, false))

  Seq(
    (Array("a"), StringType.STRING),
    (Array("b", "c"), DoubleType.DOUBLE),
    (Array("b", "d"), DateType.DATE),
    (Array("e"), FloatType.FLOAT),
    (Array("f", "g", "h"), TimestampNTZType.TIMESTAMP_NTZ),
    (Array("i"), new MapType(StringType.STRING, DoubleType.DOUBLE, false)),
    (Array("j"), new ArrayType(StringType.STRING, false))).foreach {
    case (columnName, expectedType) =>
      test(s"get physical column name and dataType for $columnName") {
        // case 1: column mapping disabled
        val column = new Column(columnName)
        val resultTuple =
          ColumnMapping.getPhysicalColumnNameAndDataType(testingSchema, column)

        val actualColumn = resultTuple._1
        val actualType = resultTuple._2
        assert(actualColumn == column)
        assert(actualType == expectedType)

        // case 2: column mapping disabled
        val metadata: Metadata = updateColumnMappingMetadataIfNeeded(
          testMetadata(testingSchema).withColumnMappingEnabled("id"),
          true).orElseGet(() => fail("Metadata should not be empty"))

        val physicalResultTuple = ColumnMapping.getPhysicalColumnNameAndDataType(
          metadata.getSchema,
          column)
        val actualPhysicalColumn = physicalResultTuple._1
        val actualPhysicalType = physicalResultTuple._2
        assert(actualPhysicalColumn.getNames.length == columnName.length)
        assert(actualPhysicalType == expectedType)
      }
  }

  Seq(
    (Array("A"), Array("a"), StringType.STRING),
    (Array("B", "C"), Array("b", "c"), DoubleType.DOUBLE),
    (Array("B", "D"), Array("b", "d"), DateType.DATE),
    (Array("E"), Array("e"), FloatType.FLOAT),
    (Array("F", "G", "H"), Array("f", "g", "h"), TimestampNTZType.TIMESTAMP_NTZ),
    (Array("I"), Array("i"), new MapType(StringType.STRING, DoubleType.DOUBLE, false)),
    (Array("J"), Array("j"), new ArrayType(StringType.STRING, false))).foreach {
    case (inputColumnName, expectedColumnName, expectedType) =>
      test(s"get physical column name should respect case of table schema, $inputColumnName") {

        val column = new Column(inputColumnName)
        val resultTuple =
          ColumnMapping.getPhysicalColumnNameAndDataType(testingSchema, column)

        val actualColumn = resultTuple._1
        val actualType = resultTuple._2
        assert(actualColumn == new Column(expectedColumnName))
        assert(actualType == expectedType)
      }
  }

  test("getPhysicalColumnNameAndDataType: exception expected when column does not exist") {
    val ex = intercept[KernelException] {
      ColumnMapping.getPhysicalColumnNameAndDataType(
        new StructType()
          .add("A", StringType.STRING)
          .add("b", IntegerType.INTEGER),
        new Column("abc"))
    }
    assert(ex.getMessage.contains("Column 'column(`abc`)' was not found in the table schema"))

    val ex1 = intercept[KernelException] {
      ColumnMapping.getPhysicalColumnNameAndDataType(
        new StructType().add("a", StringType.STRING)
          .add(
            "b",
            new StructType()
              .add("D", IntegerType.INTEGER)
              .add("e", IntegerType.INTEGER))
          .add("c", IntegerType.INTEGER),
        new Column(Array("Bbb", "d")))
    }
    assert(ex1.getMessage.contains("Column 'column(`Bbb`.`d`)' was not found in the table schema"))
  }

  Seq(true, false).foreach { isNewTable =>
    test(s"assign id and physical name to new table: $isNewTable") {
      val schema: StructType = new StructType()
        .add("a", StringType.STRING, true)
        .add("b", StringType.STRING, true)

      val metadata: Metadata = updateColumnMappingMetadataIfNeeded(
        testMetadata(schema).withColumnMappingEnabled("id"),
        isNewTable).orElseGet(() => fail("Metadata should not be empty"))

      assertColumnMapping(metadata.getSchema.get("a"), 1L, if (isNewTable) "UUID" else "a")
      assertColumnMapping(metadata.getSchema.get("b"), 2L, if (isNewTable) "UUID" else "b")

      assertThat(metadata.getConfiguration)
        .containsEntry(ColumnMapping.COLUMN_MAPPING_MAX_COLUMN_ID_KEY, "2")

      // Requesting the same operation on the same schema shouldn't change anything
      // as the schema already has the necessary column mapping info
      assertNoOpOnUpdateColumnMappingMetadataRequest(
        metadata.getSchema,
        enableIcebergCompatV2 = false,
        isNewTable)
    }
  }

  test("none mapping mode returns original schema") {
    val schema = new StructType().add("a", StringType.STRING, true)
    assertThat(updateColumnMappingMetadataIfNeeded(testMetadata(schema), true)).isEmpty
  }

  test("assigning id and physical name preserves field metadata") {
    val schema = new StructType()
      .add(
        "a",
        StringType.STRING,
        FieldMetadata.builder.putString("key1", "val1").putString("key2", "val2").build)

    val metadata = updateColumnMappingMetadataIfNeeded(
      testMetadata(schema).withColumnMappingEnabled(),
      true).orElseGet(() => fail("Metadata should not be empty"))
    val fieldMetadata = metadata.getSchema.get("a").getMetadata.getEntries

    assertThat(fieldMetadata)
      .containsEntry("key1", "val1")
      .containsEntry("key2", "val2")
      .containsEntry(ColumnMapping.COLUMN_MAPPING_ID_KEY, (1L).asInstanceOf[AnyRef])
      .hasEntrySatisfying(
        ColumnMapping.COLUMN_MAPPING_PHYSICAL_NAME_KEY,
        (k: AnyRef) => assertThat(k).asString.startsWith("col-"))
  }

  runWithIcebergCompatComboForNewAndExistingTables(
    "assign id and physical name to schema with nested struct type") {
    (isNewTable, enableIcebergCompatV2, enableIcebergWriterCompatV1) =>
      val schema: StructType =
        new StructType()
          .add("a", StringType.STRING)
          .add(
            "b",
            new StructType()
              .add("d", IntegerType.INTEGER)
              .add("e", IntegerType.INTEGER))
          .add("c", IntegerType.INTEGER)

      var inputMetadata = testMetadata(schema).withColumnMappingEnabled("id")
      if (enableIcebergCompatV2) {
        inputMetadata = inputMetadata.withIcebergCompatV2Enabled
      }
      if (enableIcebergWriterCompatV1) {
        inputMetadata = inputMetadata.withIcebergWriterCompatV1Enabled
      }
      val metadata = updateColumnMappingMetadataIfNeeded(inputMetadata, isNewTable)
        .orElseGet(() => fail("Metadata should not be empty"))

      assertColumnMapping(metadata.getSchema.get("a"), 1L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(metadata.getSchema.get("b"), 2L, isNewTable, enableIcebergWriterCompatV1)
      val innerStruct = metadata.getSchema.get("b").getDataType.asInstanceOf[StructType]
      assertColumnMapping(innerStruct.get("d"), 3L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(innerStruct.get("e"), 4L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(metadata.getSchema.get("c"), 5L, isNewTable, enableIcebergWriterCompatV1)

      assertThat(metadata.getConfiguration)
        .containsEntry(ColumnMapping.COLUMN_MAPPING_MAX_COLUMN_ID_KEY, "5")

      // Requesting the same operation on the same schema shouldn't change anything
      // as the schema already has the necessary column mapping info
      assertNoOpOnUpdateColumnMappingMetadataRequest(
        metadata.getSchema,
        enableIcebergCompatV2,
        isNewTable)
  }

  runWithIcebergCompatComboForNewAndExistingTables(
    "assign id and physical name to schema with array type") {
    (isNewTable, enableIcebergCompatV2, enableIcebergWriterCompatV1) =>
      val schema: StructType =
        new StructType()
          .add("a", StringType.STRING)
          .add("b", new ArrayType(IntegerType.INTEGER, false))
          .add("c", IntegerType.INTEGER)

      var inputMetadata = testMetadata(schema).withColumnMappingEnabled("id")
      if (enableIcebergCompatV2) {
        inputMetadata = inputMetadata.withIcebergCompatV2Enabled
      }
      if (enableIcebergWriterCompatV1) {
        inputMetadata = inputMetadata.withIcebergWriterCompatV1Enabled
      }
      val metadata = updateColumnMappingMetadataIfNeeded(inputMetadata, isNewTable)
        .orElseGet(() => fail("Metadata should not be empty"))

      assertColumnMapping(metadata.getSchema.get("a"), 1L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(metadata.getSchema.get("b"), 2L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(metadata.getSchema.get("c"), 3L, isNewTable, enableIcebergWriterCompatV1)

      if (enableIcebergCompatV2) {
        val colPrefix = if (enableIcebergWriterCompatV1) {
          "col-2."
        } else if (isNewTable) {
          "col-"
        } else {
          "b."
        }
        // verify nested ids
        assertThat(metadata.getSchema.get("b").getMetadata.getEntries
          .get(COLUMN_MAPPING_NESTED_IDS_KEY).asInstanceOf[FieldMetadata].getEntries)
          .hasSize(1)
          .anySatisfy((k: AnyRef, v: AnyRef) => {
            assertThat(k).asString.startsWith(colPrefix)
            assertThat(k).asString.endsWith(".element")
            assertThat(v).isEqualTo(4L)
          })

        assertThat(metadata.getConfiguration)
          .containsEntry(ColumnMapping.COLUMN_MAPPING_MAX_COLUMN_ID_KEY, "4")
      } else {
        assertThat(metadata.getSchema.get("b").getMetadata.getEntries)
          .doesNotContainKey(COLUMN_MAPPING_NESTED_IDS_KEY)

        assertThat(metadata.getConfiguration)
          .containsEntry(ColumnMapping.COLUMN_MAPPING_MAX_COLUMN_ID_KEY, "3")
      }

      // Requesting the same operation on the same schema shouldn't change anything
      // as the schema already has the necessary column mapping info
      assertNoOpOnUpdateColumnMappingMetadataRequest(
        metadata.getSchema,
        enableIcebergCompatV2,
        isNewTable)
  }

  runWithIcebergCompatComboForNewAndExistingTables(
    "assign id and physical name to schema with map type") {
    (isNewTable, enableIcebergCompatV2, enableIcebergWriterCompatV1) =>
      val schema: StructType =
        new StructType()
          .add("a", StringType.STRING)
          .add("b", new MapType(IntegerType.INTEGER, StringType.STRING, false))
          .add("c", IntegerType.INTEGER)

      var inputMetadata = testMetadata(schema).withColumnMappingEnabled("id")
      if (enableIcebergCompatV2) {
        inputMetadata = inputMetadata.withIcebergCompatV2Enabled
      }
      if (enableIcebergWriterCompatV1) {
        inputMetadata = inputMetadata.withIcebergWriterCompatV1Enabled
      }
      val metadata = updateColumnMappingMetadataIfNeeded(inputMetadata, isNewTable)
        .orElseGet(() => fail("Metadata should not be empty"))

      assertColumnMapping(metadata.getSchema.get("a"), 1L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(metadata.getSchema.get("b"), 2L, isNewTable, enableIcebergWriterCompatV1)
      assertColumnMapping(metadata.getSchema.get("c"), 3L, isNewTable, enableIcebergWriterCompatV1)

      if (enableIcebergCompatV2) {
        val colPrefix = if (enableIcebergWriterCompatV1) {
          "col-2."
        } else if (isNewTable) {
          "col-"
        } else {
          "b."
        }
        assert(
          metadata.getSchema.get(
            "b").getMetadata.getMetadata(COLUMN_MAPPING_NESTED_IDS_KEY) != null,
          s"${metadata.getSchema}")
        // verify nested ids
        assertThat(metadata.getSchema.get("b").getMetadata.getEntries
          .get(COLUMN_MAPPING_NESTED_IDS_KEY).asInstanceOf[FieldMetadata].getEntries)
          .hasSize(2)
          .anySatisfy((k: AnyRef, v: AnyRef) => {
            assertThat(k).asString.startsWith(colPrefix)
            assertThat(k).asString.endsWith(".key")
            assertThat(v).isEqualTo(4L)
          })
          .anySatisfy((k: AnyRef, v: AnyRef) => {
            assertThat(k).asString.startsWith(colPrefix)
            assertThat(k).asString.endsWith(".value")
            assertThat(v).isEqualTo(5L)
          })
      } else {
        assertThat(metadata.getSchema.get("b").getMetadata.getEntries)
          .doesNotContainKey(COLUMN_MAPPING_NESTED_IDS_KEY)
      }

      assertThat(metadata.getConfiguration).containsEntry(
        ColumnMapping.COLUMN_MAPPING_MAX_COLUMN_ID_KEY,
        if (enableIcebergCompatV2) "5" else "3")

      // Requesting the same operation on the same schema shouldn't change anything
      // as the schema already has the necessary column mapping info
      assertNoOpOnUpdateColumnMappingMetadataRequest(
        metadata.getSchema,
        enableIcebergCompatV2,
        isNewTable)
  }

  Seq(false, true).foreach { isNewTable =>
    val baseSchema: StructType =
      new StructType()
        .add(
          "l",
          new ArrayType(
            new ArrayType(
              new MapType(
                new ArrayType(
                  StringType.STRING,
                  /* nullable= */ false),
                new MapType(
                  StringType.STRING,
                  new StructType().add(
                    "leaf",
                    StringType.STRING,
                    false,
                    FieldMetadata.builder().putBoolean("k1", true).build()),
                  /* valuesContainNull= */ false),
                /* valuesContainNull= */ false),
              /* nullableElements= */ false),
            /* nullableElements= */ false),
          /* nullable= */ false,
          FieldMetadata.builder().putBoolean("k2", true).build())
    Seq(
      (baseSchema, 1, (md: Metadata) => md.getSchema.get("l")),
      (
        new StructType().add(
          "p",
          baseSchema,
          /* nullable= */ false),
        2,
        (md: Metadata) =>
          md.getSchema.get("p").getDataType.asInstanceOf[StructType].get("l"))).foreach {
      case (schemaToTest, base, getter) =>

        test(s"Deeply nested values don't assign more field IDs then necessary $isNewTable $base") {

          var inputMetadata = testMetadata(schemaToTest).withColumnMappingEnabled("id")
          inputMetadata = inputMetadata.withIcebergCompatV2Enabled
          inputMetadata = inputMetadata.withIcebergWriterCompatV1Enabled
          val metadata = updateColumnMappingMetadataIfNeeded(inputMetadata, isNewTable)
            .orElseGet(() => fail("Metadata should not be empty"))

          assertThat(metadata.getConfiguration).containsEntry(
            ColumnMapping.COLUMN_MAPPING_MAX_COLUMN_ID_KEY,
            (base + 8).toString)
          val prefix = s"col-$base"
          // Values are offset by base.  All Ids are assigned in depth first order to
          // StructField's first and then intermediate nested fields are added after.
          val nestedColumnMappingValues = FieldMetadata.builder()
            .putLong(s"$prefix.element", base + 2)
            .putLong(s"$prefix.element.element", base + 3)
            .putLong(s"$prefix.element.element.key", base + 4)
            .putLong(s"$prefix.element.element.key.element", base + 5)
            .putLong(s"$prefix.element.element.value", base + 6)
            .putLong(s"$prefix.element.element.value.key", base + 7)
            .putLong(s"$prefix.element.element.value.value", base + 8).build()
          val expectedMetadata = FieldMetadata.builder().putFieldMetadata(
            COLUMN_MAPPING_NESTED_IDS_KEY,
            nestedColumnMappingValues)
            .putString("delta.columnMapping.physicalName", prefix)
            .putLong("delta.columnMapping.id", base)
            .putBoolean("k2", true).build()
          val firstColumnMetadata = getter(metadata).getMetadata
          assertThat(firstColumnMetadata.getMetadata(
            COLUMN_MAPPING_NESTED_IDS_KEY).getEntries).containsExactlyInAnyOrderEntriesOf(
            nestedColumnMappingValues.getEntries)
          assertThat(firstColumnMetadata.getEntries).containsExactlyInAnyOrderEntriesOf(
            expectedMetadata.getEntries)

          // TODO: It would be nice to have visitor pattern on schema so we can assert
          // all metadata for nested fields
          // are empty but this at least provides a sanity check.
          assert(getter(metadata).getDataType.asInstanceOf[
            ArrayType].getElementField.getMetadata == FieldMetadata.empty())

          // Requesting the same operation on the same schema shouldn't change anything
          // as the schema already has the necessary column mapping info. This includes both
          // IDs and maxFieldId.
          assertNoOpOnUpdateColumnMappingMetadataRequest(
            metadata.getSchema,
            /* enableIcebergCompatV2= */ true,
            isNewTable)
        }
    }
  }

  runWithIcebergCompatComboForNewAndExistingTables(
    "assign id and physical name to schema with nested schema") {
    (isNewTable, enableIcebergCompatV2, enableIcebergWriterCompatV1) =>
      val schema: StructType = cmTestSchema()

      var inputMetadata = testMetadata(schema).withColumnMappingEnabled("id")
      if (enableIcebergCompatV2) {
        inputMetadata = inputMetadata.withIcebergCompatV2Enabled
      }
      if (enableIcebergWriterCompatV1) {
        inputMetadata = inputMetadata.withIcebergWriterCompatV1Enabled
      }
      val metadata = updateColumnMappingMetadataIfNeeded(inputMetadata, isNewTable)
        .orElseGet(() => fail("Metadata should not be empty"))

      verifyCMTestSchemaHasValidColumnMappingInfo(
        metadata,
        isNewTable,
        enableIcebergCompatV2,
        enableIcebergWriterCompatV1)

      // Requesting the same operation on the same schema shouldn't change anything
      // as the schema already has the necessary column mapping info
      assertNoOpOnUpdateColumnMappingMetadataRequest(
        metadata.getSchema,
        enableIcebergCompatV2,
        isNewTable)
  }

  Seq(true, false).foreach { icebergCompatV2Enabled =>
    test(s"assign id and physical name to only to the fields that don't have " +
      s"with icebergCompatV2=$icebergCompatV2Enabled") {
      val schema: StructType =
        new StructType().add("a", StringType.STRING)

      val inputMetadata = testMetadata(schema).withColumnMappingEnabled("id")
      val updatedMetadata = updateColumnMappingMetadataIfNeeded(
        if (icebergCompatV2Enabled) inputMetadata.withIcebergCompatV2Enabled else inputMetadata,
        true).orElseGet(() => fail("Metadata should not be empty"))

      assertColumnMapping(updatedMetadata.getSchema.get("a"), 1L)

      // Now add few more fields to the same schema
      val updateSchema = updatedMetadata.getSchema
        .add("b", StringType.STRING)
        .add("c", new ArrayType(IntegerType.INTEGER, false))
        .add("d", new MapType(IntegerType.INTEGER, StringType.STRING, false))
        .add("e", new StructType().add("h", IntegerType.INTEGER))

      val inputMetadata2 = testMetadata(updateSchema).withColumnMappingEnabled("id")
      val updatedMetadata2 = updateColumnMappingMetadataIfNeeded(
        if (icebergCompatV2Enabled) inputMetadata2.withIcebergCompatV2Enabled else inputMetadata2,
        false).orElseGet(() => fail("Metadata should not be empty"))

      var fieldId = 0L

      def nextFieldId: Long = {
        fieldId += 1
        fieldId
      }

      assertColumnMapping(updatedMetadata2.getSchema.get("a"), nextFieldId)
      // newly added columns will have the physical names same as logical names
      assertColumnMapping(updatedMetadata2.getSchema.get("b"), nextFieldId, "b")
      assertColumnMapping(updatedMetadata2.getSchema.get("c"), nextFieldId, "c")
      assertColumnMapping(updatedMetadata2.getSchema.get("d"), nextFieldId, "d")
      assertColumnMapping(updatedMetadata2.getSchema.get("e"), nextFieldId, "e")
      assertColumnMapping(
        updatedMetadata2.getSchema.get("e")
          .getDataType.asInstanceOf[StructType].get("h"),
        nextFieldId,
        "h")

      if (icebergCompatV2Enabled) {
        // verify nested ids
        assertThat(updatedMetadata2.getSchema.get("c").getMetadata.getEntries
          .get(COLUMN_MAPPING_NESTED_IDS_KEY).asInstanceOf[FieldMetadata].getEntries)
          .hasSize(1)
          .anySatisfy((k: AnyRef, v: AnyRef) => {
            assertThat(k).asString.startsWith("c.")
            assertThat(k).asString.endsWith(".element")
            assertThat(v).isEqualTo(nextFieldId)
          })

        assertThat(updatedMetadata2.getSchema.get("d").getMetadata.getEntries
          .get(COLUMN_MAPPING_NESTED_IDS_KEY).asInstanceOf[FieldMetadata].getEntries)
          .hasSize(2)
          .anySatisfy((k: AnyRef, v: AnyRef) => {
            assertThat(k).asString.startsWith("d.")
            assertThat(k).asString.endsWith(".key")
            assertThat(v).isEqualTo(nextFieldId)
          })
          .anySatisfy((k: AnyRef, v: AnyRef) => {
            assertThat(k).asString.startsWith("d.")
            assertThat(k).asString.endsWith(".value")
            assertThat(v).isEqualTo(nextFieldId)
          })
      } else {
        assertThat(updatedMetadata2.getSchema.get("c").getMetadata.getEntries)
          .doesNotContainKey(COLUMN_MAPPING_NESTED_IDS_KEY)
        assertThat(updatedMetadata2.getSchema.get("d").getMetadata.getEntries)
          .doesNotContainKey(COLUMN_MAPPING_NESTED_IDS_KEY)
      }

      assertNoOpOnUpdateColumnMappingMetadataRequest(
        updatedMetadata2.getSchema,
        icebergCompatV2Enabled,
        isNewTable = false)
    }
  }

  test("both id and physical name must be provided if one is provided") {
    val schemaWithoutPhysicalName = new StructType()
      .add(
        new StructField(
          "col1",
          StringType.STRING,
          true,
          FieldMetadata.builder()
            .putLong(ColumnMapping.COLUMN_MAPPING_ID_KEY, 0)
            .build()))
    val schemaWithoutId = new StructType()
      .add(
        new StructField(
          "col1",
          StringType.STRING,
          true,
          FieldMetadata.builder()
            .putString(ColumnMapping.COLUMN_MAPPING_PHYSICAL_NAME_KEY, "physical-name-col1")
            .build()))

    Seq(schemaWithoutId, schemaWithoutPhysicalName).foreach { schema =>
      val e = intercept[IllegalArgumentException] {
        updateColumnMappingMetadataIfNeeded(testMetadata(schema).withColumnMappingEnabled(), true)
      }
      assert(e.getMessage.contains(
        "Both columnId and physicalName must be present if one is present"))
    }
  }

  /**
   * A struct type with all necessary CM info won't cause metadata change by
   * [[updateColumnMappingMetadataIfNeeded]]
   */
  def assertNoOpOnUpdateColumnMappingMetadataRequest(
      schemaWithCMInfo: StructType,
      enableIcebergCompatV2: Boolean,
      isNewTable: Boolean): Unit = {

    var metadata = testMetadata(schemaWithCMInfo).withColumnMappingEnabled("id")
    if (enableIcebergCompatV2) {
      metadata = metadata.withIcebergCompatV2Enabled
    }
    if (!metadata.getConfiguration.containsKey(COLUMN_MAPPING_MAX_COLUMN_ID_KEY)) {
      // A hack, if the metadata doesn't have max column ID in it,
      // then new metadata is always returned.
      metadata =
        metadata.withMergedConfiguration(Map(COLUMN_MAPPING_MAX_COLUMN_ID_KEY -> "100").asJava)
    }

    assertThat(updateColumnMappingMetadataIfNeeded(metadata, isNewTable)).isEmpty
  }

  def runWithIcebergCompatComboForNewAndExistingTables(testName: String)(f: (
      Boolean,
      Boolean,
      Boolean) => Unit): Unit = {
    for {
      isNewTable <- Seq(true, false)
      enableIcebergCompatV2 <- Seq(true, false)
    } {
      // We only test icebergWriterCompatV1 when icebergCompatV2 is enabled
      val icebergWriterCompatV1Modes = if (enableIcebergCompatV2) {
        Seq(true, false)
      } else {
        Seq(false)
      }
      icebergWriterCompatV1Modes.foreach { enableIcebergWriterCompatV1 =>
        test(s"$testName, enableIcebergCompatV2=$enableIcebergCompatV2, " +
          s"isNewTable=$isNewTable, enableIcebergWriterCompatV1=$enableIcebergWriterCompatV1") {
          f(isNewTable, enableIcebergCompatV2, enableIcebergWriterCompatV1)
        }
      }
    }
  }
}
