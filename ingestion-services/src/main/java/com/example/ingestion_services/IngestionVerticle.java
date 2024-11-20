package com.example.ingestion_services;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.amqp.AmqpClient;
import io.vertx.reactivex.amqp.AmqpMessage;
import io.vertx.reactivex.amqp.AmqpReceiver;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.amqp.AmqpReceiverOptions;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.RxHelper;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.kafka.client.consumer.KafkaConsumer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class IngestionVerticle extends AbstractVerticle {
  private static final int HTTP_PORT = 3002;
  private static final Logger logger = LoggerFactory.getLogger(IngestionVerticle.class);

  private KafkaProducer<String, JsonObject> updateProducer;
  private KafkaConsumer<String, JsonObject> kafkaConsumer;
  private AmqpClient amqpClient;

  Map<String, String> kafkaConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("bootstrap.servers", "localhost:9092");
    config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    config.put("value.serializer", "io.vertx.kafka.client.serialization.JsonObjectSerializer");
    config.put("acks", "1");
    return config;
  }

  private AmqpClientOptions amqpConfig() {
    return new AmqpClientOptions()
      .setHost("localhost")
      .setPort(5672)
      .setUsername("admin")
      .setPassword("admin");
  }

  static Map<String, String> kafkaConsumerConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("bootstrap.servers", "localhost:9092");
    config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    config.put("value.deserializer", "io.vertx.kafka.client.serialization.JsonObjectDeserializer");
    config.put("auto.offset.reset", "earliest");
    config.put("enable.auto.commit", "false");
    config.put("group.id", "ingester-group");
    return config;
  }

  @Override
  public Completable rxStart() {
    updateProducer = KafkaProducer.create(vertx, kafkaConfig());
    kafkaConsumer = KafkaConsumer.create(vertx, kafkaConsumerConfig());
    amqpClient = AmqpClient.create(vertx, amqpConfig());

    AmqpClientOptions amqpOptions = amqpConfig();
    AmqpReceiverOptions receiverOptions = new AmqpReceiverOptions()
      .setAutoAcknowledgement(false)
      .setDurable(true);

    AmqpClient.create(vertx, amqpOptions)
      .rxConnect()
      .flatMap(conn -> conn.rxCreateReceiver("step-events", receiverOptions))
      .flatMapPublisher(AmqpReceiver::toFlowable)
      .doOnError(this::logAmqpError)
      .retryWhen(this::retryLater)
      .subscribe(this::handleAmqpMessage);

    Router router = Router.router(vertx);
    router.post().handler(BodyHandler.create());
    router.post("/ingest").handler(this::httpIngest);


    //consumer kafka
    kafkaConsumer.subscribe("incoming.steps")
      .toFlowable()
      .subscribe(
        record -> {
          JsonObject json = record.value();
          System.out.printf("Offset: %d, Key: %s, Value: %s%n", record.offset(), record.key(), record.value());
          kafkaConsumer.commit();
        },
        err -> {
          logger.error("AMQP ingestion failed", err);
        });

    //send msg to queue
    JsonObject body = new JsonObject()
      .put("deviceId", "10")
      .put("deviceSync", 1L)
      .put("stepsCount", Math.random());

    amqpClient.rxConnect()
      .flatMap(connection -> connection.rxCreateSender("step-events"))
      .subscribe(
        sender -> {
          System.out.println("amqpClient producer: " + body);

          AmqpMessage msg = AmqpMessage.create()
            .durable(true)
            .ttl(5000)
            .withJsonObjectAsBody(body).build();
          sender.send(msg);
        },
        err -> {
          logger.error("AMQP ingestion failed", err);
        });

    return vertx.createHttpServer()
      .requestHandler(router)
      .rxListen(HTTP_PORT)
      .ignoreElement();
  }

  private void logAmqpError(Throwable err) {
    logger.error("Woops AMQP", err);
  }

  private void handleAmqpMessage(AmqpMessage message) {
    if (!"application/json".equals(message.contentType()) || invalidIngestedJson(message.bodyAsJsonObject())) {
      logger.error("Invalid AMQP message (discarded): {}", message.bodyAsBinary());
      message.accepted();
      return;
    }

    JsonObject payload = message.bodyAsJsonObject();
    KafkaProducerRecord<String, JsonObject> record = makeKafkaRecord(payload);

    updateProducer.rxSend(record).subscribe(
      ok -> message.accepted(),
      err -> {
        logger.error("AMQP ingestion failed", err);
        message.rejected();
      });
  }


  private Flowable<Throwable> retryLater(Flowable<Throwable> errs) {
    return errs.delay(10, TimeUnit.SECONDS, RxHelper.scheduler(vertx));
  }

  private void httpIngest(RoutingContext ctx) {
    JsonObject payload = ctx.getBodyAsJson();
    if (invalidIngestedJson(payload)) {
      logger.error("Invalid HTTP JSON (discarded): {}", payload.encode());
      ctx.fail(400);
      return;
    }

    KafkaProducerRecord<String, JsonObject> record = makeKafkaRecord(payload);
    updateProducer.rxSend(record).subscribe(
      ok -> ctx.response().end(),
      err -> {
        logger.error("HTTP ingestion failed", err);
        ctx.fail(500);
      });
  }

  private boolean invalidIngestedJson(JsonObject payload) {
    return !payload.containsKey("deviceId") || !payload.containsKey("deviceSync") || !payload.containsKey("stepsCount");
  }

  private KafkaProducerRecord<String, JsonObject> makeKafkaRecord(JsonObject payload) {
    String deviceId = payload.getString("deviceId");
    JsonObject recordData = new JsonObject()
      .put("deviceId", payload.getString("deviceId"))
      .put("deviceSync", payload.getLong("deviceSync"))
      .put("stepsCount", payload.getInteger("stepsCount"));

    return KafkaProducerRecord.create("incoming.steps", deviceId, recordData);
  }


  public static void main(String[] args) {
    System.setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory");
    Vertx vertx = Vertx.vertx();
    vertx.rxDeployVerticle(new IngestionVerticle())
      .subscribe(
        ok -> logger.info("HTTP server started on port {}", HTTP_PORT),
        err -> logger.error("Woops", err));
  }
}