/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.instrumentation.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import com.newrelic.agent.TransactionData;
import com.newrelic.agent.TransactionListener;
import com.newrelic.agent.service.ServiceFactory;
import com.newrelic.agent.stats.Stats;
import com.newrelic.agent.stats.StatsEngine;
import com.newrelic.agent.stats.TransactionStats;
import com.newrelic.agent.tracers.Tracer;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import com.newrelic.api.agent.TransactionNamePriority;

import info.batey.kafka.unit.KafkaUnitRule;
import test.newrelic.EnvironmentHolderSettingsGenerator;
import test.newrelic.test.agent.EnvironmentHolder;

public class KafkaTest {

    private static final String CONFIG_FILE = "configs/span_events_test.yml";
    private static final ClassLoader CLASS_LOADER = KafkaTest.class.getClassLoader();

    @Rule
    public KafkaUnitRule kafkaUnitRule = new KafkaUnitRule();

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final String testTopic = "TestTopic";

    @Test
    public void produceConsumeTest() throws Exception {
        EnvironmentHolderSettingsGenerator envHolderSettings = new EnvironmentHolderSettingsGenerator(CONFIG_FILE, "all_enabled_test", CLASS_LOADER);
        EnvironmentHolder holder = new EnvironmentHolder(envHolderSettings);
        holder.setupEnvironment();
        kafkaUnitRule.getKafkaUnit().createTopic(testTopic, 1);
        final KafkaConsumer<String, String> consumer = setupConsumer();

        final CountDownLatch latch = new CountDownLatch(1);
        final ConcurrentLinkedQueue<TransactionData> finishedTransactions = new ConcurrentLinkedQueue<>();
        TransactionListener transactionListener = new TransactionListener() {
            @Override
            public void dispatcherTransactionFinished(TransactionData transactionData, TransactionStats transactionStats) {
                finishedTransactions.add(transactionData);
                latch.countDown();
            }
        };
        ServiceFactory.getTransactionService().addTransactionListener(transactionListener);

        try {
            produceMessage();
            final Future<?> submit = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    consumeMessage(consumer);
                }
            });
            submit.get(30, TimeUnit.SECONDS);
            latch.await(30, TimeUnit.SECONDS);

            Assert.assertEquals(2, finishedTransactions.size());

            Thread.sleep(1000); // Wait for the metrics reporter

            // Assert on the kafka metrics that we're expecting
            StatsEngine statsEngine = ServiceFactory.getStatsService()
                    .getStatsEngineForHarvest(ServiceFactory.getConfigService().getDefaultAgentConfig().getApplicationName());
            Stats messagesConsumed = statsEngine.getStats("MessageBroker/Kafka/Internal/consumer-fetch-manager-metrics/records-consumed-rate");
            Assert.assertNotNull(messagesConsumed);
            Assert.assertTrue(messagesConsumed.getCallCount() > 1);
            Assert.assertTrue(messagesConsumed.getTotal() > 0);

            Stats bytesConsumed = statsEngine.getStats("MessageBroker/Kafka/Internal/consumer-metrics/incoming-byte-rate");
            Assert.assertNotNull(bytesConsumed);
            Assert.assertTrue(bytesConsumed.getCallCount() > 1);
            Assert.assertTrue(bytesConsumed.getTotal() > 0);

            Stats rebalanceAssignedPartition = statsEngine.getStats("MessageBroker/Kafka/Rebalance/Assigned/" + testTopic + "/0");
            Assert.assertNotNull(rebalanceAssignedPartition);
            Assert.assertEquals(1, rebalanceAssignedPartition.getCallCount());

            Stats serializationByTopic = statsEngine.getStats("MessageBroker/Kafka/Serialization/TestTopic");
            Assert.assertNotNull(serializationByTopic);
            Assert.assertEquals(2, serializationByTopic.getCallCount()); // One for the key, one for the value
            Assert.assertTrue(serializationByTopic.getTotal() > 1);

            Stats deserializationByTopic = statsEngine.getStats("MessageBroker/Kafka/Deserialization/TestTopic");
            Assert.assertNotNull(deserializationByTopic);
            Assert.assertEquals(2, deserializationByTopic.getCallCount()); // One for the key, one for the value
            Assert.assertTrue(deserializationByTopic.getTotal() > 1);

            // external reporting test
            TransactionData prodTxn = finishedTransactions.poll();
            Collection<Tracer> tracers = prodTxn.getTracers();
            Iterator<Tracer> iterator = tracers.iterator();
            Assert.assertTrue(iterator.hasNext());
            Tracer tracer = iterator.next();
            Assert.assertEquals("MessageBroker/Kafka/Topic/Produce/Named/TestTopic", tracer.getMetricName());

            TransactionData conTxn = finishedTransactions.poll();
            Tracer rootTracer = conTxn.getRootTracer();
            Assert.assertEquals("MessageBroker/Kafka/Topic/Consume/Named/TestTopic", rootTracer.getMetricName());
            Assert.assertNotNull(conTxn.getInboundDistributedTracePayload());
        } finally {
            ServiceFactory.getTransactionService().removeTransactionListener(transactionListener);
            consumer.close();
        }
    }

    @Trace(dispatcher = true)
    private void produceMessage() {
        ProducerRecord<String, String> keyedMessage = new ProducerRecord<>(testTopic, "key", "value");
        kafkaUnitRule.getKafkaUnit().sendMessages(keyedMessage);
    }

    @Trace(dispatcher = true)
    private void consumeMessage(KafkaConsumer<String, String> consumer) {
        final ConsumerRecords<String, String> records = consumer.poll(1000);
        Assert.assertEquals(1, records.count());

        for (ConsumerRecord<String, String> record : records) {
            processRecord(record);
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        NewRelic.getAgent().getTransaction().setTransactionName(TransactionNamePriority.CUSTOM_HIGH,
                true, "kafka", "processRecord");
        final Iterator<Header> nrIterator = record.headers().headers("newrelic").iterator();
        if (nrIterator.hasNext()) {
            final Header nrHeader = nrIterator.next();
            NewRelic.getAgent().getTransaction().acceptDistributedTracePayload(new String(nrHeader.value(), StandardCharsets.UTF_8));
        }
        else {
            Assert.fail("DT header wasn't added to all messages");
        }
    }

    private KafkaConsumer<String, String> setupConsumer() {
        final Properties props = new Properties();
        props.put("bootstrap.servers", kafkaUnitRule.getKafkaUnit().getKafkaConnect());
        props.put("group.id", "test");
        props.put("enable.auto.commit", "true");
        props.put("auto.commit.interval.ms", "1000");
        props.put("session.timeout.ms", "30000");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("max.poll.records", String.valueOf(Integer.MAX_VALUE));

        final KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props);
        kafkaConsumer.subscribe(Collections.singletonList(testTopic));
        kafkaConsumer.poll(0);
        kafkaConsumer.seekToBeginning(Collections.singletonList(new TopicPartition(testTopic, 0)));

        return kafkaConsumer;
    }

}
