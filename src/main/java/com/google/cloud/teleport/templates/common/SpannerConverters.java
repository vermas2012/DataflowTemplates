/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.teleport.templates.common;

import com.google.auto.value.AutoValue;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Type.Code;
import com.google.cloud.spanner.Type.StructField;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.gcp.spanner.ExposedSpannerAccessor;
import org.apache.beam.sdk.io.gcp.spanner.ReadOperation;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Transforms & DoFns & Options for SpannerIO. */
public class SpannerConverters {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  /** Options for exporting a Spanner table. */
  public interface SpannerReadOptions extends PipelineOptions {
    @Description("Spanner table to extract")
    ValueProvider<String> getSpannerTable();

    @SuppressWarnings("unused")
    void setSpannerTable(ValueProvider<String> table);

    @Description("GCP Project Id of where the Spanner table lives.")
    ValueProvider<String> getSpannerProjectId();

    @SuppressWarnings("unused")
    void setSpannerProjectId(ValueProvider<String> spannerReadProjectId);

    @Description("Instance with the requested table.")
    ValueProvider<String> getSpannerInstanceId();

    @SuppressWarnings("unused")
    void setSpannerInstanceId(ValueProvider<String> spannerInstanceId);

    @Description("Database with a requested table.")
    ValueProvider<String> getSpannerDatabaseId();

    @SuppressWarnings("unused")
    void setSpannerDatabaseId(ValueProvider<String> spannerDatabaseId);
  }

  /** Factory for Export transform class. */
  public static class ExportTransformFactory {

    public static ExportTransform create(
        ValueProvider<String> table,
        SpannerConfig spannerConfig,
        ValueProvider<String> textWritePrefix) {
      return ExportTransform.builder()
          .table(table)
          .spannerConfig(spannerConfig)
          .textWritePrefix(textWritePrefix)
          .build();
    }
  }

  /** PTransform used to export the table. */
  @AutoValue
  abstract static class ExportTransform extends PTransform<PBegin, PCollection<ReadOperation>> {

    private static final Logger LOG = LoggerFactory.getLogger(ExportTransform.class);

    abstract ValueProvider<String> table();

    abstract SpannerConfig spannerConfig();

    abstract ValueProvider<String> textWritePrefix();

    @Override
    public PCollection<ReadOperation> expand(PBegin begin) {
      // PTransform expand does not have access to template parameters but DoFn does.
      // Spanner parameter values are required to get the table schema information.
      return begin
          .apply("Pipeline start", Create.of(ImmutableList.of("")))
          .apply("ExportFn", ParDo.of(new ExportFn()));
    }

    /** Builder for ExportTransform function. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder table(ValueProvider<String> table);

      public abstract Builder spannerConfig(SpannerConfig spannerConfig);

      public abstract Builder textWritePrefix(ValueProvider<String> textWritePrefix);

      public abstract ExportTransform build();
    }

    public static Builder builder() {
      return new AutoValue_SpannerConverters_ExportTransform.Builder();
    }

    private ExposedSpannerAccessor spannerAccessor;
    private DatabaseClient databaseClient;

    // SpannerAccessor is not serialiazable, thus can't be passed as a mock so we need to pass
    // mocked database client directly instead. We can't generate stub of ExportTransform because
    // AutoValue generates a final class.
    // TODO make SpannerAccessor serializable
    DatabaseClient getDatabaseClient(SpannerConfig spannerConfig) {
      if (databaseClient == null) {
        this.spannerAccessor = ExposedSpannerAccessor.create(spannerConfig);
        return this.spannerAccessor.getDatabaseClient();
      } else {
        return this.databaseClient;
      }
    }

    public void setDatabaseClient(DatabaseClient databaseClient) {
      this.databaseClient = databaseClient;
    }

    void closeSpannerAccessor() {
      if (spannerAccessor != null) {
        this.spannerAccessor.close();
      }
    }

    /** Function used to export the table. */
    public class ExportFn extends DoFn<String, ReadOperation> {

      static final String SCHEMA_SUFFIX = "schema";

      @ProcessElement
      @SuppressWarnings("unused")
      public void processElement(ProcessContext processContext) {
        // Save schema to GCS so it can be saved along with the exported file.
        LOG.info("Creating database client for schema read");

        LinkedHashMap<String, String> columns;
        try {
          DatabaseClient databaseClient = getDatabaseClient(spannerConfig());

          try (ReadOnlyTransaction context = databaseClient.readOnlyTransaction()) {
            LOG.info("Reading schema information");
            columns = getAllColumns(context, table().get());
            String columnJson = SpannerConverters.GSON.toJson(columns);
            LOG.info("Saving schema information");
            saveSchema(columnJson, textWritePrefix().get() + SCHEMA_SUFFIX);
          }
        } finally {
          closeSpannerAccessor();
        }

        processContext.output(
            ReadOperation.create()
                .withColumns(new ArrayList<>(columns.keySet()))
                .withTable(table().get()));
      }

      private void saveSchema(String content, String schemaPath) {
        LOG.info("Schema: " + content);

        try {
          WritableByteChannel chan =
              FileSystems.create(FileSystems.matchNewResource(schemaPath, false), "text/plain");
          try (OutputStream stream = Channels.newOutputStream(chan)) {
            stream.write(content.getBytes());
          }
        } catch (IOException e) {
          throw new RuntimeException("Failed to write schema", e);
        }
      }
    }

    /** Function to get all column names from the table. */
    private LinkedHashMap<String, String> getAllColumns(ReadContext context, String tableName) {
      LinkedHashMap<String, String> columns = Maps.newLinkedHashMap();
      ResultSet resultSet =
          context.executeQuery(
              Statement.newBuilder(
                      "SELECT COLUMN_NAME, SPANNER_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                          + "WHERE TABLE_NAME=@table_name ORDER BY ORDINAL_POSITION")
                  .bind("table_name")
                  .to(tableName)
                  .build());
      LOG.info("Got schema information. Reading columns.");
      while (resultSet.next()) {
        Struct currentRow = resultSet.getCurrentRowAsStruct();
        columns.put(currentRow.getString(0), currentRow.getString(1));
      }
      return columns;
    }
  }

  /**
   * Struct printer for converting a Spanner Struct to CSV. See {@link this#parseArrayValue(Struct,
   * String)} for data type conversions. It uses a standard comma separated format as for RFC4180
   * but allowing empty lines.
   */
  public static class StructCsvPrinter {

    /**
     * Prints Struct as a CSV String.
     *
     * @param struct Spanner Struct.
     * @return Spanner Struct encoded as a CSV String.
     */
    public String print(Struct struct) {
      StringWriter stringWriter = new StringWriter();
      try {
        CSVPrinter printer =
            new CSVPrinter(stringWriter, CSVFormat.DEFAULT.withRecordSeparator("")
                .withQuoteMode(QuoteMode.ALL_NON_NULL));
        LinkedHashMap<String, BiFunction<Struct, String, String>> parsers = Maps.newLinkedHashMap();
        parsers.putAll(mapColumnParsers(struct.getType().getStructFields()));
        List<String> values = parseResultSet(struct, parsers);
        printer.printRecord(values);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return stringWriter.toString();
    }

    /**
     * Parse results given by table read. The function returns a list of strings, where each string
     * represents a single column. The list constitutes the entire result set (columns in all of the
     * records).
     */
    private static List<String> parseResultSet(
        Struct struct, LinkedHashMap<String, BiFunction<Struct, String, String>> parsers) {
      List<String> result = Lists.newArrayList();

      for (String columnName : parsers.keySet()) {
        if (!parsers.containsKey(columnName)) {
          throw new RuntimeException("No parser for column: " + columnName);
        }
        result.add(parsers.get(columnName).apply(struct, columnName));
      }
      return result;
    }
  }

  /** Function to map columns to their corresponding parsing function. */
  private static LinkedHashMap<String, BiFunction<Struct, String, String>> mapColumnParsers(
      List<StructField> fields) {
    LinkedHashMap<String, BiFunction<Struct, String, String>> columnParsers =
        Maps.newLinkedHashMap();
    for (StructField field : fields) {
      columnParsers.put(field.getName(), getColumnParser(field.getType().getCode()));
    }
    return columnParsers;
  }

  private static BiFunction<Struct, String, String> nullSafeColumnParser(
      BiFunction<Struct, String, String> columnParser) {
    return (currentRow, columnName) ->
        currentRow.isNull(columnName) ? null : columnParser.apply(currentRow, columnName);
  }

  /**
   * Function with a series of switch cases to determine the parsing function for a specific column
   * type:
   *
   * <p>- Primitive types such as Boolean, Long, Int, and String are converted using toString
   * parser for primitive types.
   * - Date and Timestamp use toString method, for example 2018-03-26 and 1970-01-01T00:00:00Z
   * - Byte arrays use base64 encoding, so for example "test" transforms to "dGVzdA=="
   */
  private static BiFunction<Struct, String, String> getColumnParser(Type.Code columnType) {
    switch (columnType) {
      case BOOL:
        return nullSafeColumnParser(
            (currentRow, columnName) -> Boolean.toString(currentRow.getBoolean(columnName)));
      case INT64:
        return nullSafeColumnParser(
            (currentRow, columnName) -> Long.toString(currentRow.getLong(columnName)));
      case FLOAT64:
        return nullSafeColumnParser(
            ((currentRow, columnName) -> Double.toString(currentRow.getDouble(columnName))));
      case STRING:
        return nullSafeColumnParser(Struct::getString);
      case BYTES:
        return nullSafeColumnParser(
            (currentRow, columnName) ->
                Base64.getEncoder().encodeToString(currentRow.getBytes(columnName).toByteArray()));
      case DATE:
        return nullSafeColumnParser(
            (currentRow, columnName) -> currentRow.getDate(columnName).toString());
      case TIMESTAMP:
        return nullSafeColumnParser(
            (currentRow, columnName) -> currentRow.getTimestamp(columnName).toString());
      case ARRAY:
        return nullSafeColumnParser(SpannerConverters::parseArrayValue);
      default:
        throw new RuntimeException("Unsupported type: " + columnType);
    }
  }

  /**
   * Helper to parse array types. Arrays are converted to JSON representation. Array inner types use
   * the same serialization algorithm as in {@link this#getColumnParser(Code)}, for example [test,
   * foo] is transformed to [""test"", ""foo""]
   */
  private static String parseArrayValue(Struct currentRow, String columnName) {
    Code code = currentRow.getColumnType(columnName).getArrayElementType().getCode();
    switch (code) {
      case BOOL:
        return GSON.toJson(currentRow.getBooleanArray(columnName));
      case INT64:
        return GSON.toJson(currentRow.getLongArray(columnName));
      case FLOAT64:
        return GSON.toJson(currentRow.getDoubleArray(columnName));
      case STRING:
        return GSON.toJson(currentRow.getStringList(columnName));
      case BYTES:
        return GSON.toJson(
            currentRow
                .getBytesList(columnName)
                .stream()
                .map(byteArray -> Base64.getEncoder().encodeToString(byteArray.toByteArray()))
                .collect(Collectors.toList()));
      case DATE:
        return GSON.toJson(
            currentRow
                .getDateList(columnName)
                .stream()
                .map(Date::toString)
                .collect(Collectors.toList()));
      case TIMESTAMP:
        return GSON.toJson(
            currentRow
                .getTimestampList(columnName)
                .stream()
                .map(Timestamp::toString)
                .collect(Collectors.toList()));
      default:
        throw new RuntimeException("Unsupported type: " + code);
    }
  }
}
