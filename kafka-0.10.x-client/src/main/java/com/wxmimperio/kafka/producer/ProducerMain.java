package com.wxmimperio.kafka.producer;

/**
 * Created by wxmimperio on 2016/12/5.
 */
public class ProducerMain {
    public static void main(String args[]) {
        KafkaNewProducer producer = new KafkaNewProducer("test_001");
        producer.execute(3);

        KafkaNewProducer producer1 = new KafkaNewProducer("test_2");
        producer1.execute(3);
    }
}
