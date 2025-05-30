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
package io.delta.kernel.defaults.internal.parquet;

import static io.delta.kernel.defaults.internal.parquet.ParquetIOUtils.createParquetOutputFile;
import static io.delta.kernel.defaults.internal.parquet.ParquetStatsReader.readDataFileStatistics;
import static io.delta.kernel.internal.util.Preconditions.checkArgument;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static org.apache.parquet.hadoop.ParquetOutputFormat.*;

import io.delta.kernel.Meta;
import io.delta.kernel.data.*;
import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.defaults.internal.parquet.ParquetColumnWriters.ColumnWriter;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.internal.fs.Path;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.statistics.DataFileStatistics;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.hadoop.ParquetOutputFormat;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;

/**
 * Implements writing data given as {@link FilteredColumnarBatch} to Parquet files.
 *
 * <p>It makes use of the `parquet-mr` library to write the data in Parquet format. The main class
 * used is {@link ParquetWriter} which is used to write the data row by row to the Parquet file.
 * Supporting interface for this writer is {@link WriteSupport} (in this writer implementation, it
 * is {@link BatchWriteSupport}). {@link BatchWriteSupport}, on call back from {@link
 * ParquetWriter}, reads the contents of {@link ColumnarBatch} and passes the contents to {@link
 * ParquetWriter} through {@link RecordConsumer}.
 */
public class ParquetFileWriter {
  public static final String TARGET_FILE_SIZE_CONF =
      "delta.kernel.default.parquet.writer.targetMaxFileSize";
  public static final long DEFAULT_TARGET_FILE_SIZE = 128 * 1024 * 1024; // 128MB

  private final FileIO fileIO;
  private final boolean writeAsSingleFile;
  private final String location;
  private final boolean atomicWrite;
  private final long targetMaxFileSize;
  private final List<Column> statsColumns;

  private long currentFileNumber; // used to generate the unique file names.

  /**
   * Create writer to write data into one or more files depending upon the {@code
   * delta.kernel.default.parquet.writer.targetMaxFileSize} value and the given data.
   *
   * @param fileIO File IO implementation to use for reading and writing files.
   * @param location Location to write the data. Should be a directory.
   * @param statsColumns List of columns to collect statistics for. The statistics collection is
   *     optional.
   */
  public static ParquetFileWriter multiFileWriter(
      FileIO fileIO, String location, List<Column> statsColumns) {
    return new ParquetFileWriter(
        fileIO, location, /* writeAsSingleFile = */ false, /* atomicWrite = */ false, statsColumns);
  }

  /**
   * Create writer to write the data exactly into one file.
   *
   * @param fileIO File IO implementation to use for reading and writing files.
   * @param location Location to write the data. Shouldn't be a directory.
   * @param atomicWrite If true, write the file is written atomically (i.e. either the entire
   *     content is written or none, but won't create a file with the partial contents).
   * @param statsColumns List of columns to collect statistics for. The statistics collection is
   *     optional.
   */
  public static ParquetFileWriter singleFileWriter(
      FileIO fileIO, String location, boolean atomicWrite, List<Column> statsColumns) {
    return new ParquetFileWriter(
        fileIO, location, /* writeAsSingleFile = */ true, atomicWrite, statsColumns);
  }

  /**
   * Private constructor to create the writer. Use {@link #multiFileWriter} or {@link
   * #singleFileWriter} to create the writer.
   */
  private ParquetFileWriter(
      FileIO fileIO,
      String location,
      boolean writeAsSingleFile,
      boolean atomicWrite,
      List<Column> statsColumns) {
    this.fileIO = requireNonNull(fileIO, "fileIO is null");
    this.writeAsSingleFile = writeAsSingleFile;
    this.location = requireNonNull(location, "location is null");
    this.atomicWrite = atomicWrite;
    this.statsColumns = requireNonNull(statsColumns, "statsColumns is null");
    this.targetMaxFileSize =
        fileIO.getConf(TARGET_FILE_SIZE_CONF).map(Long::valueOf).orElse(DEFAULT_TARGET_FILE_SIZE);
    checkArgument(targetMaxFileSize > 0, "Invalid target Parquet file size: %s", targetMaxFileSize);
  }

  /**
   * Write the given data to Parquet files.
   *
   * @param dataIter Iterator of data to write.
   * @return an iterator of {@link DataFileStatus} where each entry contains the metadata of the
   *     data file written. It is the responsibility of the caller to close the iterator.
   */
  public CloseableIterator<DataFileStatus> write(
      CloseableIterator<FilteredColumnarBatch> dataIter) {
    return new CloseableIterator<DataFileStatus>() {
      // Last written file output.
      private Optional<DataFileStatus> lastWrittenFileOutput = Optional.empty();

      // Current batch of data that is being written, updated in {@link #hasNextRow()}.
      private FilteredColumnarBatch currentBatch = null;

      // Which record in the `currentBatch` is being written,
      // initialized in {@link #hasNextRow()} and updated in {@link #consumeNextRow}.
      private int currentBatchCursor = 0;

      // BatchWriteSupport is initialized when the first batch is read and reused for
      // subsequent batches with the same schema. `ParquetWriter` can use this write support
      // to consume data from `ColumnarBatch` and write it to Parquet files.
      private BatchWriteSupport batchWriteSupport = null;

      private StructType dataSchema = null;

      @Override
      public void close() {
        Utils.closeCloseables(dataIter);
      }

      @Override
      public boolean hasNext() {
        if (lastWrittenFileOutput.isPresent()) {
          return true;
        }
        lastWrittenFileOutput = writeNextFile();
        return lastWrittenFileOutput.isPresent();
      }

      @Override
      public DataFileStatus next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        DataFileStatus toReturn = lastWrittenFileOutput.get();
        lastWrittenFileOutput = Optional.empty();
        return toReturn;
      }

      private Optional<DataFileStatus> writeNextFile() {
        if (!hasNextRow()) {
          return Optional.empty();
        }

        org.apache.parquet.io.OutputFile parquetOutputFile =
            createParquetOutputFile(generateNextOutputFile(), atomicWrite);
        assert batchWriteSupport != null : "batchWriteSupport is not initialized";
        long currentFileRowCount = 0; // tracks the number of rows written to the current file
        try (ParquetWriter<Integer> writer = createWriter(parquetOutputFile, batchWriteSupport)) {
          boolean maxFileSizeReached;
          do {
            if (consumeNextRow(writer)) {
              // If the row was written, increment the row count
              currentFileRowCount++;
            }
            // If we are writing a single file, then don't need to check for the current
            // file size. Otherwise see if the current file size reached the target file
            // size.
            maxFileSizeReached = !writeAsSingleFile && writer.getDataSize() >= targetMaxFileSize;
            // Keep writing until max file is reached or no more data to write
          } while (!maxFileSizeReached && hasNextRow());
        } catch (IOException e) {
          throw new UncheckedIOException(
              "Failed to write the Parquet file: " + parquetOutputFile.getPath(), e);
        }

        return Optional.of(
            constructDataFileStatus(parquetOutputFile.getPath(), dataSchema, currentFileRowCount));
      }

      /**
       * Returns true if there is data to write.
       *
       * <p>Internally it traverses the rows in one batch after the other. Whenever a batch is fully
       * consumed, moves to the next input batch and updates the column writers in
       * `batchWriteSupport`.
       */
      boolean hasNextRow() {
        boolean hasNextRowInCurrentBatch =
            currentBatch != null
                &&
                // Is current batch is fully read?
                currentBatchCursor < currentBatch.getData().getSize();

        if (hasNextRowInCurrentBatch) {
          return true;
        }

        // loop until we find a non-empty batch or there are no more batches
        do {
          if (!dataIter.hasNext()) {
            return false;
          }
          currentBatch = dataIter.next();
          currentBatchCursor = 0;
        } while (currentBatch.getData().getSize() == 0); // skip empty batches

        // Initialize the batch support and create writers for each column
        ColumnarBatch inputBatch = currentBatch.getData();
        dataSchema = inputBatch.getSchema();
        BatchWriteSupport writeSupport = createOrGetWriteSupport(dataSchema);

        ColumnWriter[] columnWriters = ParquetColumnWriters.createColumnVectorWriters(inputBatch);

        writeSupport.setColumnVectorWriters(columnWriters);

        return true;
      }

      /**
       * Consume the next row of data to write. If the row is selected, write it. Otherwise, skip
       * it. At the end move the cursor to the next row.
       *
       * @return true if the row was written, false if it was skipped
       */
      boolean consumeNextRow(ParquetWriter<Integer> writer) throws IOException {
        Optional<ColumnVector> selectionVector = currentBatch.getSelectionVector();
        boolean isRowSelected =
            !selectionVector.isPresent()
                || (!selectionVector.get().isNullAt(currentBatchCursor)
                    && selectionVector.get().getBoolean(currentBatchCursor));

        if (isRowSelected) {
          writer.write(currentBatchCursor);
        }
        currentBatchCursor++;
        return isRowSelected;
      }

      /**
       * Create a {@link BatchWriteSupport} if it does not exist or return the existing one for
       * given schema.
       */
      BatchWriteSupport createOrGetWriteSupport(StructType inputSchema) {
        if (batchWriteSupport == null) {
          MessageType parquetSchema = ParquetSchemaUtils.toParquetSchema(inputSchema);
          batchWriteSupport = new BatchWriteSupport(inputSchema, parquetSchema);
          return batchWriteSupport;
        }
        // Ensure the new input schema matches the one used to create the write support
        if (!batchWriteSupport.inputSchema.equals(inputSchema)) {
          throw new IllegalArgumentException(
              "Input data has columnar batches with "
                  + "different schemas:\n schema 1: "
                  + batchWriteSupport.inputSchema
                  + "\n schema 2: "
                  + inputSchema);
        }
        return batchWriteSupport;
      }
    };
  }

  /**
   * Implementation of {@link WriteSupport} to write the {@link ColumnarBatch} to Parquet files.
   * {@link ParquetWriter} makes use of this interface to consume the data row by row and write to
   * the Parquet file. Call backs from the {@link ParquetWriter} includes:
   *
   * <ul>
   *   <li>{@link #init(Configuration)}: Called once to init and get {@link WriteContext} which
   *       includes the schema and extra properties.
   *   <li>{@link #prepareForWrite(RecordConsumer)}: Called once to prepare for writing the data.
   *       {@link RecordConsumer} is a way for this batch support to write data for each column in
   *       the current row.
   *   <li>{@link #write(Integer)}: Called for each row to write the data. In this method, column
   *       values are passed to the {@link RecordConsumer} through series of calls.
   * </ul>
   */
  private static class BatchWriteSupport extends WriteSupport<Integer> {
    final StructType inputSchema;
    final MessageType parquetSchema;

    private ColumnWriter[] columnWriters;
    private RecordConsumer recordConsumer;

    BatchWriteSupport(
        StructType inputSchema, // WriteSupport created for this specific schema
        MessageType parquetSchema) { // Parquet equivalent schema
      this.inputSchema = requireNonNull(inputSchema, "inputSchema is null");
      this.parquetSchema = requireNonNull(parquetSchema, "parquetSchema is null");
    }

    void setColumnVectorWriters(ColumnWriter[] columnWriters) {
      this.columnWriters = requireNonNull(columnWriters, "columnVectorWriters is null");
    }

    @Override
    public String getName() {
      return "delta-kernel-default-parquet-writer";
    }

    @Override
    public WriteContext init(Configuration configuration) {
      Map<String, String> extraProps =
          Collections.singletonMap(
              "io.delta.kernel.default-parquet-writer", "Kernel-Defaults-" + Meta.KERNEL_VERSION);
      return new WriteContext(parquetSchema, extraProps);
    }

    @Override
    public void prepareForWrite(RecordConsumer recordConsumer) {
      this.recordConsumer = recordConsumer;
    }

    @Override
    public void write(Integer rowId) {
      // Use java asserts which are disabled in prod to reduce the overhead
      // and enabled in tests with `-ea` argument.
      assert (recordConsumer != null) : "Parquet record consumer is null";
      assert (columnWriters != null) : "Column writers are not set";
      recordConsumer.startMessage();
      for (int i = 0; i < columnWriters.length; i++) {
        columnWriters[i].writeRowValue(recordConsumer, rowId);
      }
      recordConsumer.endMessage();
    }
  }

  /** Generate the next file path to write the data. */
  private OutputFile generateNextOutputFile() {
    if (writeAsSingleFile) {
      checkArgument(currentFileNumber++ == 0, "expected to write just one file");
      return fileIO.newOutputFile(location);
    }
    String fileName = String.format("%s-%03d.parquet", UUID.randomUUID(), currentFileNumber++);
    String filePath = new Path(location, fileName).toString();
    return fileIO.newOutputFile(filePath);
  }

  /**
   * Helper method to create {@link ParquetWriter} for given file path and write support. It makes
   * use of configuration options in `configuration` to configure the writer. Different available
   * configuration options are defined in {@link ParquetOutputFormat}.
   */
  private ParquetWriter<Integer> createWriter(
      org.apache.parquet.io.OutputFile outputFile, WriteSupport<Integer> writeSupport)
      throws IOException {
    ParquetRowDataBuilder rowDataBuilder = new ParquetRowDataBuilder(outputFile, writeSupport);

    fileIO
        .getConf(COMPRESSION)
        .ifPresent(
            compression ->
                rowDataBuilder.withCompressionCodec(CompressionCodecName.fromConf(compression)));

    fileIO.getConf(BLOCK_SIZE).map(Long::parseLong).ifPresent(rowDataBuilder::withRowGroupSize);

    fileIO.getConf(PAGE_SIZE).map(Integer::parseInt).ifPresent(rowDataBuilder::withPageSize);

    fileIO
        .getConf(DICTIONARY_PAGE_SIZE)
        .map(Integer::parseInt)
        .ifPresent(rowDataBuilder::withDictionaryPageSize);

    fileIO
        .getConf(MAX_PADDING_BYTES)
        .map(Integer::parseInt)
        .ifPresent(rowDataBuilder::withMaxPaddingSize);

    fileIO
        .getConf(ENABLE_DICTIONARY)
        .map(Boolean::parseBoolean)
        .ifPresent(rowDataBuilder::withDictionaryEncoding);

    fileIO.getConf(VALIDATION).map(Boolean::parseBoolean).ifPresent(rowDataBuilder::withValidation);

    fileIO
        .getConf(WRITER_VERSION)
        .map(WriterVersion::fromString)
        .ifPresent(rowDataBuilder::withWriterVersion);

    return rowDataBuilder.build();
  }

  private static class ParquetRowDataBuilder
      extends ParquetWriter.Builder<Integer, ParquetRowDataBuilder> {
    private final WriteSupport<Integer> writeSupport;

    protected ParquetRowDataBuilder(
        org.apache.parquet.io.OutputFile outputFile, WriteSupport<Integer> writeSupport) {
      super(outputFile);
      this.writeSupport = requireNonNull(writeSupport, "writeSupport is null");
    }

    @Override
    protected ParquetRowDataBuilder self() {
      return this;
    }

    @Override
    protected WriteSupport<Integer> getWriteSupport(Configuration conf) {
      return writeSupport;
    }
  }

  /**
   * Construct the {@link DataFileStatus} for the given file path. It reads the file status and
   * Parquet footer to compute the statistics for the file.
   *
   * <p>Potential improvement in future to directly compute the statistics while writing the file if
   * this becomes a sufficiently large part of the write operation time.
   *
   * @param path the path of the file
   * @param dataSchema the schema of the data in the file
   * @param numRows the number of rows in the file. If no column stats are required, this is used to
   *     construct the {@link DataFileStatistics}. Otherwise, the stats are read from the file.
   * @return the {@link DataFileStatus} for the file
   */
  private DataFileStatus constructDataFileStatus(String path, StructType dataSchema, long numRows) {
    try {
      // Get the FileStatus to figure out the file size and modification time
      FileStatus fileStatus = fileIO.getFileStatus(path);
      String resolvedPath = fileIO.resolvePath(path);

      DataFileStatistics stats;
      if (statsColumns.isEmpty()) {
        stats =
            new DataFileStatistics(
                numRows,
                emptyMap() /* minValues */,
                emptyMap() /* maxValues */,
                emptyMap() /* nullCount */);
      } else {
        stats =
            readDataFileStatistics(
                fileIO.newInputFile(resolvedPath, fileStatus.getSize()), dataSchema, statsColumns);
      }

      return new DataFileStatus(
          resolvedPath,
          fileStatus.getSize(),
          fileStatus.getModificationTime(),
          Optional.ofNullable(stats));
    } catch (IOException ioe) {
      throw new UncheckedIOException("Failed to read the stats for: " + path, ioe);
    }
  }
}
