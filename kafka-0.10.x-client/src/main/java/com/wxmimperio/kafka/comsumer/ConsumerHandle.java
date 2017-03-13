package com.wxmimperio.kafka.comsumer;

import com.wxmimperio.kafka.nettyclient.base.EchoClient;
import com.wxmimperio.kafka.pojo.TopicCount;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.util.*;
import java.util.concurrent.*;

/**
 * Created by wxmimperio on 2016/12/5.
 */
public class ConsumerHandle implements Runnable {

    private KafkaConsumer consumer;
    private List<String> topicList;
    private static final int minBatchSize = 10;
    private String group;


    private Long countNum = 0L;

    private Map<String, Long> topicCountNum = new ConcurrentHashMap<>();

    public ConsumerHandle(KafkaConsumer<String, String> consumer, List<String> topicList, String group) {
        this.consumer = consumer;
        this.topicList = topicList;
        this.consumer.subscribe(this.topicList);
        this.group = group;

       /* TopicPartition topicPartition = new TopicPartition(this.topic, 1);

        long offset = this.consumer.position(topicPartition);

        System.out.println("=============" + offset);*/
    }

    @Override
    public void run() {
        long lastOffset = 0L;
        int partition = -1;
        String currentTopic = "";
     /*   TopicPartition topicPartition = new TopicPartition(this.topic, 0);
        List<TopicPartition> list = new ArrayList<>();
        list.add(topicPartition);

        Collection<TopicPartition> collection = list;

       *//* this.consumer.seekToEnd(collection);

        long offset = this.consumer.position(topicPartition);*//*

        Map<TopicPartition, Long> endOffsets = this.consumer.endOffsets(collection);
        System.out.println(endOffsets);*/

        List<ConsumerRecord<String, String>> buffer = new ArrayList<ConsumerRecord<String, String>>();
        /*TopicPartition topicPartition = new TopicPartition(this.topic, 0);

        OffsetAndMetadata offsetAndMetadata = this.consumer.committed(topicPartition);
        System.out.println(offsetAndMetadata.offset());*/

        ExecutorService threadPool = Executors.newSingleThreadExecutor();


        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(Integer.MAX_VALUE);

            for (TopicPartition topicPartition : records.partitions()) {
                List<ConsumerRecord<String, String>> topicPartitionRecords = records.records(new TopicPartition(topicPartition.topic(), topicPartition.partition()));


                //System.out.println("topic = " + topicPartition.topic() + " partition = " + topicPartition.partition() + " size = " + topicPartitionRecords.size());
                String key = "cassandra-" + this.group + "-" + topicPartition.topic() + "-" + topicPartition.partition();
                long oldCount = (topicCountNum.get(key) == null) ? 0L : topicCountNum.get(key);
                topicCountNum.put(key, oldCount + topicPartitionRecords.size());


                for (ConsumerRecord<String, String> record : topicPartitionRecords) {
                    buffer.add(record);
                    record.key();
                    //System.out.println(record.key());

                    //System.out.println(record.serializedValueSize());
                    lastOffset = record.offset();
                    partition = record.partition();
                    currentTopic = record.topic();
/*                    System.out.println("Thread=" + Thread.currentThread().getName() +
                            " value=" + record.value() + " partition=" + record.partition() +
                            " topic" + record.topic() + " offset" + record.offset() + " time=" + record.timestamp() + " group=" + this.group);*/
                }
            }

            /*for (ConsumerRecord<String, String> record : records.records(this.topicList.get(0))) {
            }

            for (ConsumerRecord<String, String> record : records) {
                buffer.add(record);
                lastOffset = record.offset();
                partition = record.partition();
                currentTopic = record.topic();
                System.out.println("Thread=" + Thread.currentThread().getName() +
                        " value=" + record.value() + " partition=" + record.partition() +
                        " topic" + record.topic() + " offset" + record.offset() + " time=" + record.timestamp() + " group=" + this.group);

            }*/

            countNum += buffer.size();

            Calendar nowCal = new GregorianCalendar();
            int nowSecond = nowCal.get(Calendar.SECOND);
            if (nowSecond == 00) {

                Future<Boolean> future = threadPool.submit(new Callable<Boolean>() {
                    public Boolean call() throws Exception {
                        EchoClient echoClient = new EchoClient("127.0.0.1", 65535, topicCountNum);
                        return echoClient.send();
                    }
                });

                try {
                    if(future.get().booleanValue()) {
                        countNum = 0L;
                        System.out.println(topicCountNum);
                        topicCountNum.clear();
                        System.out.println("插入发送count");
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }

                /*EchoClient echoClient = new EchoClient("127.0.0.1", 65535, topicCountNum);
                if (echoClient.send()) {
                    countNum = 0L;
                    System.out.println(topicCountNum);
                    topicCountNum.clear();
                }
                System.out.println("插入发送count");*/
            }

            //System.out.println("Thread=" + Thread.currentThread().getName() + " " + countNum + " group = " + group + " topic" + topicList);

            if (buffer.size() % minBatchSize == 0) {
                consumer.commitAsync(); //批量完成写入后，手工sync offset
                buffer.clear();
            }
            consumer.commitAsync(); //批量完成写入后，手工sync offset
            buffer.clear();
        }
    }
}