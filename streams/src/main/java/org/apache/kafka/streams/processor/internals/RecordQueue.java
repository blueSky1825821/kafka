/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.internals.metrics.TaskMetrics;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;

import java.util.ArrayDeque;

/**
 * RecordQueue is a FIFO queue of {@link StampedRecord} (ConsumerRecord + timestamp). It also keeps track of the
 * partition timestamp defined as the largest timestamp seen on the partition so far; this is passed to the
 * timestamp extractor.
 */
public class RecordQueue {

    public static final long UNKNOWN = ConsumerRecord.NO_TIMESTAMP;

    private final Logger log;
    private final SourceNode<?, ?> source;
    private final TopicPartition partition;
    private final ProcessorContext<?, ?> processorContext;
    private final TimestampExtractor timestampExtractor;
    private final RecordDeserializer recordDeserializer;
    private final ArrayDeque<ConsumerRecord<byte[], byte[]>> fifoQueue;

    private StampedRecord headRecord = null;
    private long partitionTime = UNKNOWN;

    private final Sensor droppedRecordsSensor;
    private long totalBytesBuffered;
    private long headRecordSizeInBytes;

    RecordQueue(final TopicPartition partition,
                final SourceNode<?, ?> source,
                final TimestampExtractor timestampExtractor,
                final DeserializationExceptionHandler deserializationExceptionHandler,
                final InternalProcessorContext<?, ?> processorContext,
                final LogContext logContext) {
        this.source = source;
        this.partition = partition;
        this.fifoQueue = new ArrayDeque<>();
        this.timestampExtractor = timestampExtractor;
        this.processorContext = processorContext;
        droppedRecordsSensor = TaskMetrics.droppedRecordsSensor(
            Thread.currentThread().getName(),
            processorContext.taskId().toString(),
            processorContext.metrics()
        );
        recordDeserializer = new RecordDeserializer(
            source,
            deserializationExceptionHandler,
            logContext,
            droppedRecordsSensor
        );
        this.log = logContext.logger(RecordQueue.class);
        this.totalBytesBuffered = 0L;
        this.headRecordSizeInBytes = 0L;
    }

    void setPartitionTime(final long partitionTime) {
        this.partitionTime = partitionTime;
    }

    /**
     * Returns the corresponding source node in the topology
     *
     * @return SourceNode
     */
    public SourceNode<?, ?> source() {
        return source;
    }

    /**
     * Returns the partition with which this queue is associated
     *
     * @return TopicPartition
     */
    public TopicPartition partition() {
        return partition;
    }

    private long sizeInBytes(final ConsumerRecord<byte[], byte[]> record) {
        long headerSizeInBytes = 0L;

        for (final Header header: record.headers().toArray()) {
            headerSizeInBytes += Utils.utf8(header.key()).length;
            if (header.value() != null) {
                headerSizeInBytes += header.value().length;
            }
        }

        return record.serializedKeySize() +
                record.serializedValueSize() +
                8L + // timestamp
                8L + // offset
                Utils.utf8(record.topic()).length +
                4L + // partition
                headerSizeInBytes;
    }

    /**
     * Add a batch of {@link ConsumerRecord} into the queue
     *
     * @param rawRecords the raw records
     * @return the size of this queue
     */
    int addRawRecords(final Iterable<ConsumerRecord<byte[], byte[]>> rawRecords) {
        for (final ConsumerRecord<byte[], byte[]> rawRecord : rawRecords) {
            fifoQueue.addLast(rawRecord);
            this.totalBytesBuffered += sizeInBytes(rawRecord);
        }

        updateHead();

        return size();
    }

    /**
     * Get the next {@link StampedRecord} from the queue
     *
     * @return StampedRecord
     */
    public StampedRecord poll() {
        final StampedRecord recordToReturn = headRecord;
        totalBytesBuffered -= headRecordSizeInBytes;
        headRecord = null;
        headRecordSizeInBytes = 0L;
        partitionTime = Math.max(partitionTime, recordToReturn.timestamp);

        updateHead();

        return recordToReturn;
    }

    /**
     * Returns the number of records in the queue
     *
     * @return the number of records
     */
    public int size() {
        // plus one deserialized head record for timestamp tracking
        return fifoQueue.size() + (headRecord == null ? 0 : 1);
    }

    /**
     * Tests if the queue is empty
     *
     * @return true if the queue is empty, otherwise false
     */
    public boolean isEmpty() {
        return fifoQueue.isEmpty() && headRecord == null;
    }

    /**
     * Returns the head record's timestamp
     *
     * @return timestamp
     */
    public long headRecordTimestamp() {
        return headRecord == null ? UNKNOWN : headRecord.timestamp;
    }

    public Long headRecordOffset() {
        return headRecord == null ? null : headRecord.offset();
    }

    /**
     * Clear the fifo queue of its elements
     */
    public void clear() {
        fifoQueue.clear();
        headRecord = null;
        headRecordSizeInBytes = 0L;
        partitionTime = UNKNOWN;
    }

    private void updateHead() {
        ConsumerRecord<byte[], byte[]> lastCorruptedRecord = null;

        while (headRecord == null && !fifoQueue.isEmpty()) {
            final ConsumerRecord<byte[], byte[]> raw = fifoQueue.pollFirst();
            final ConsumerRecord<Object, Object> deserialized =
                recordDeserializer.deserialize(processorContext, raw);

            if (deserialized == null) {
                // this only happens if the deserializer decides to skip. It has already logged the reason.
                lastCorruptedRecord = raw;
                continue;
            }

            final long timestamp;
            try {
                timestamp = timestampExtractor.extract(deserialized, partitionTime);
            } catch (final StreamsException internalFatalExtractorException) {
                throw internalFatalExtractorException;
            } catch (final Exception fatalUserException) {
                throw new StreamsException(
                        String.format("Fatal user code error in TimestampExtractor callback for record %s.", deserialized),
                        fatalUserException);
            }
            log.trace("Source node {} extracted timestamp {} for record {}", source.name(), timestamp, deserialized);

            // drop message if TS is invalid, i.e., negative
            if (timestamp < 0) {
                log.warn(
                        "Skipping record due to negative extracted timestamp. topic=[{}] partition=[{}] offset=[{}] extractedTimestamp=[{}] extractor=[{}]",
                        deserialized.topic(), deserialized.partition(), deserialized.offset(), timestamp, timestampExtractor.getClass().getCanonicalName()
                );
                droppedRecordsSensor.record();
                continue;
            }
            headRecord = new StampedRecord(deserialized, timestamp);
            headRecordSizeInBytes = sizeInBytes(raw);
        }

        // if all records in the FIFO queue are corrupted, make the last one the headRecord
        // This record is used to update the offsets. See KAFKA-6502 for more details.
        if (headRecord == null && lastCorruptedRecord != null) {
            headRecord = new CorruptedRecord(lastCorruptedRecord);
        }
    }

    /**
     * @return the local partitionTime for this particular RecordQueue
     */
    long partitionTime() {
        return partitionTime;
    }

    /**
     * @return the total bytes buffered for this particular RecordQueue
     */
    long getTotalBytesBuffered() {
        return totalBytesBuffered;
    }
}